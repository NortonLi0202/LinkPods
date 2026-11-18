package local.airpods.handoffprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Runtime AACP experiment; no boot receiver or persistent Root setup. */
public final class HandoffService extends Service {
    static volatile HandoffService current;
    private static final java.util.concurrent.atomic.AtomicReference<RootEnvironment.Prepared> pendingBridge=
        new java.util.concurrent.atomic.AtomicReference<>();
    static boolean adoptPreparedBridge(RootEnvironment.Prepared prepared) {
        return RootEnvironment.bridgeUsable(prepared)
            && pendingBridge.compareAndSet(null,prepared);
    }
    static void discardPreparedBridge(RootEnvironment.Prepared prepared) {
        if(pendingBridge.compareAndSet(prepared,null))
            RootEnvironment.queueBridgeStop(prepared);
    }
    public static final String EXTRA_SESSION_MODE = "session_mode";
    private volatile SessionMode mode = SessionMode.CONTROL_ONLY;
    private volatile long routingModeGeneration;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RoutingPolicy policy = new RoutingPolicy();
    private final PeerRoutingPolicy peerPolicy = new PeerRoutingPolicy();
    private DeviceConfig deviceConfig;
    private String peerMac = "";
    private boolean nativeBackendReady;
    private final ConnectionPolicy connection = new ConnectionPolicy();
    private java.util.concurrent.Future<?> bridgePreparation;
    private final java.util.concurrent.atomic.AtomicReference<RootEnvironment.Prepared> ownedBridge=
        new java.util.concurrent.atomic.AtomicReference<>();
    private final AudioLinkPolicy audioLink = new AudioLinkPolicy();
    private final Set<String> peers = new HashSet<>();
    private BluetoothAdapter adapter;
    private BluetoothDevice target;
    private AudioManager audio;
    private SamsungAudioMonitor audioMonitor;
    private volatile boolean stopped;
    private String selfMac = "";
    private Boolean owns;
    private String sourceMac;
    private int sourceType;
    private Boolean lastMediaState;
    private Runnable pendingReconnect;
    private final ControlSession.Listener controlEvents = new ControlSession.Listener() {
        @Override public void onReady(long connectionGeneration, String address) {
            if (stopped || current != HandoffService.this || deviceConfig == null || !address.equals(deviceConfig.target)) return;
            connection.ready(connectionGeneration, address);
            log("SESSION_READY");
            log("CONNECTION " + connection.snapshot(address).description);
            log("BACKEND " + BackendPolicy.select(!nativeBackendReady, nativeBackendReady, nativeBackendReady, nativeBackendReady)
                + " handoff=not-verified");
            finishPendingTake("pending-player-play");
            announcePeers();
        }
        @Override public void onPacket(byte[] data) {
            if (stopped || current != HandoffService.this) return;
            receive(data);
        }
        @Override public void onEnded(long connectionGeneration, String address, boolean compatibilityRequired) {
            if (stopped || current != HandoffService.this || deviceConfig == null || !address.equals(deviceConfig.target)) return;
            log("SESSION_ENDED");
            log("BACKEND " + BackendPolicy.select(false, false, false, false));
            applyConnection(connection.ended(connectionGeneration, address, compatibilityRequired));
        }
    };
    private final ControlSession controlSession = new ControlSession(handler, this::log, controlEvents);
    private final PlaybackWatch.Listener playbackEvents = new PlaybackWatch.Listener() {
        @Override public void onPlayersSeeded(Map<String, Integer> states, boolean restarted) {
            if (stopped || !mode.allowsRouting()) return;
            boolean keepPending = restarted && policy.isPending();
            if (restarted) policy.reset();
            for (Map.Entry<String, Integer> entry : states.entrySet()) {
                policy.seed(entry.getKey(), entry.getValue());
            }
            if (keepPending) policy.restorePendingIfPlaying();
            if (controlSession.isReady()) mediaInformation(policy.hasPlayback());
        }
        @Override public void onPlayerStateChanged(String playerId, String packageName, int state) {
            if (stopped || !mode.allowsRouting()) return;
            boolean ready = controlSession.isReady();
            log("PLAYER package=" + packageName + " token=" + playerId
                + " state=" + state + " ready=" + ready);
            if (policy.onPlayback(playerId, state) == RoutingPolicy.Action.TAKE) {
                finishPendingTake("player-play");
            }
            if (ready && !policy.hasPlayback()) mediaInformation(false);
        }
        @Override public void onPlayerRemoved(String playerId) {
            if (stopped || !mode.allowsRouting()) return;
            policy.remove(playerId);
            if (controlSession.isReady() && !policy.hasPlayback()) mediaInformation(false);
        }
        @Override public void onSessionsSynced() {
            if (stopped || !mode.allowsRouting()) return;
            if (controlSession.isReady() && !policy.hasPlayback()) mediaInformation(false);
        }
        @Override public void onWatchStopped(boolean accessLost) {
            policy.reset();
            if (accessLost && !stopped && mode.allowsRouting() && controlSession.isReady()) {
                mediaInformation(false);
            }
        }
    };
    private PlaybackWatch playbackWatch;

    private PlaybackWatch watch() {
        if (playbackWatch == null) {
            playbackWatch = new PlaybackWatch(
                this, handler, new ComponentName(this, PlaybackListener.class), this::log, playbackEvents);
        }
        return playbackWatch;
    }

    public SessionMode mode() { return mode; }
    boolean isStopped() { return stopped; }
    public static boolean isRunning() {
        HandoffService service = current;
        return service != null && !service.stopped;
    }
    public static String activeTarget() {
        HandoffService service = current;
        return service == null || service.stopped || service.deviceConfig == null ? "" : service.deviceConfig.target;
    }
    public static SessionMode currentMode() {
        HandoffService service = current;
        return service == null || service.stopped ? null : service.mode;
    }

    /** Read-only observed connection state; no Root probes or session creation. */
    public static ConnectionPolicy.Snapshot capabilities(String targetAddress) {
        HandoffService service=current;
        return service==null ? new ConnectionPolicy().snapshot(targetAddress) : service.connection.snapshot(targetAddress);
    }

    /** Read-only: this never starts the service, opens a socket, or sends a query. */
    public static EarbudSnapshot earbudSnapshot(String targetAddress) {
        HandoffService service=current;
        return service==null ? EarbudSnapshot.empty(targetAddress,"控制服务未运行")
            : service.controlSession.snapshot(targetAddress);
    }
    /** Read-only system audio and profile snapshot from the active service monitor. */
    public static SamsungAudioSnapshot audioSnapshot(String targetAddress) {
        HandoffService service = current;
        if (service == null || service.stopped || service.audioMonitor == null) {
            return SamsungAudioSnapshot.unavailable(targetAddress);
        }
        return service.audioMonitor.snapshot(targetAddress);
    }
    public static boolean bridgePrepared() {
        HandoffService service=current;
        return service!=null && service.nativeBackendReady;
    }

    public static SessionStatus sessionStatus(String targetAddress) {
        HandoffService service = current;
        if (service == null || service.stopped) {
            return SessionStatus.idle(targetAddress);
        }
        boolean matches = targetAddress != null && service.deviceConfig != null && targetAddress.equalsIgnoreCase(service.deviceConfig.target);
        if (!matches) {
            return SessionStatus.idle(targetAddress);
        }
        SessionMode mode = service.mode;
        boolean ready = service.controlSession.isReady();
        boolean watchActive = service.playbackWatch != null && service.playbackWatch.isActive();
        ConnectionPolicy.Snapshot conn = service.connection.snapshot(targetAddress);
        String ctrl = ready ? (service.nativeBackendReady ? "桥接控制通道已连接" : "直连控制通道已连接") : conn.description;
        String handoff = mode == SessionMode.AUTO_HANDOFF
            ? (watchActive ? "运行中（随播放交接）" : "已开启（等待媒体权限）")
            : "关闭（纯控制模式）";
        return new SessionStatus(targetAddress, mode, true, ready, ctrl, handoff);
    }

    /** Accept a validated request on the existing session; only a real reply confirms it. */
    public static boolean setEarbudSetting(String targetAddress,int id,int value) {
        HandoffService service=current;
        return service!=null && !service.stopped && service.controlSession.setSetting(targetAddress,id,value);
    }

    public static boolean refreshEarbudState(String targetAddress) {
        HandoffService service=current;
        return service!=null && !service.stopped && service.controlSession.refreshState(targetAddress);
    }
    private final SamsungAudioMonitor.Listener audioMonitorListener = new SamsungAudioMonitor.Listener() {
        @Override public void onAudioSnapshotChanged(SamsungAudioSnapshot snapshot) {}
        @Override public void onA2dpStateChanged(String address, int state) {
            if (stopped || target == null || !target.getAddress().equalsIgnoreCase(address)) return;
            log("A2DP_STATE " + state);
            if (mode.allowsRouting()) applyAudioLink(audioLink.update(state));
        }
        @Override public void onA2dpProxyReady(BluetoothProfile proxy) {
            log("PROFILE ready=" + BluetoothProfile.A2DP);
            if (!stopped && target != null && mode.allowsRouting()) {
                applyAudioLink(audioLink.update(proxy.getConnectionState(target)));
            }
        }
        @Override public void onAudioModeChanged(int audioMode) {
            log("AUDIO_MODE " + audioMode);
            if (controlSession.isReady() && mode.allowsRouting() && (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION)) take("call");
        }
    };

    private BluetoothProfile a2dpProxy() {
        return audioMonitor == null ? null : audioMonitor.a2dpProxy();
    }

    private BluetoothProfile headsetProxy() {
        return audioMonitor == null ? null : audioMonitor.headsetProxy();
    }

    @Override public void onCreate() {
        super.onCreate();
        watch();
        current = this;
        audio = getSystemService(AudioManager.class);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/");
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("handoff", "LinkPods 控制服务", NotificationManager.IMPORTANCE_LOW));
        startForeground(42, new Notification.Builder(this, "handoff")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LinkPods 耳机控制")
            .setContentText("保持耳机控制通道；可在应用内停止")
            .setOngoing(true).build());
        audioMonitor = new SamsungAudioMonitor(this, handler, audioMonitorListener);
        audioMonitor.start();
        log("SERVICE created");
    }

    private void updateForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        boolean handoff = mode == SessionMode.AUTO_HANDOFF;
        Notification notification = new Notification.Builder(this, "handoff")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(handoff ? "LinkPods 自动交接" : "LinkPods 耳机控制")
            .setContentText(handoff ? "保持控制通道，随媒体播放交接；可在应用内停止" : "保持耳机控制通道；可在应用内停止")
            .setOngoing(true).build();
        nm.notify(42, notification);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopped) { return START_NOT_STICKY; }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (adapter == null) { log("SERVICE bluetooth adapter unavailable"); stopSelf(); return START_NOT_STICKY; }
        final DeviceConfig requested;
        try { requested = DeviceConfig.create(intent.getStringExtra("address"), intent.getStringExtra("local_mac"), intent.getStringExtra("peer_address")); }
        catch (IllegalArgumentException error) { log("SERVICE invalid device configuration"); stopSelf(); return START_NOT_STICKY; }
        final DeviceConfig merged;
        try {merged=DeviceConfig.mergeLocal(deviceConfig,requested);}
        catch(IllegalArgumentException error){
            RootEnvironment.Prepared rejected=pendingBridge.getAndSet(null);
            if(rejected!=null)RootEnvironment.queueBridgeStop(rejected);
            log("SERVICE stop before selecting a different device configuration");return START_NOT_STICKY;
        }
        if (deviceConfig == null) controlSession.begin(requested.target);
        deviceConfig = merged;
        selfMac = deviceConfig.local;
        if (audioMonitor != null) audioMonitor.setTargetAddress(deviceConfig.target);
        SessionMode requestedMode = SessionMode.CONTROL_ONLY;
        String modeName = intent.getStringExtra(EXTRA_SESSION_MODE);
        if (modeName != null) {
            try { requestedMode = SessionMode.valueOf(modeName); }
            catch (IllegalArgumentException ignored) {}
        }
        SessionMode previousMode = this.mode;
        if (previousMode != requestedMode) ++routingModeGeneration;
        this.mode = requestedMode;
        if (previousMode != requestedMode) {
            log("SESSION_MODE changed: " + previousMode + " -> " + requestedMode);
            updateForegroundNotification();
            if (requestedMode == SessionMode.AUTO_HANDOFF) {
                watch().start();
                announcePeers();
            } else {
                if (playbackWatch != null) playbackWatch.stop();
                policy.reset();
                peerPolicy.reset();
                audioLink.reset();
                lastMediaState = null;
                owns = null;
            }
        } else if (requestedMode == SessionMode.AUTO_HANDOFF && !watch().isWatching()) {
            watch().start();
            announcePeers();
        }
        boolean preparedBridge=false;
        if(intent.getBooleanExtra("native_backend_ready",false)) {
            RootEnvironment.Prepared handedOff=pendingBridge.getAndSet(null);
            if(handedOff!=null && handedOff.targetAddress.equalsIgnoreCase(deviceConfig.target)
                    && RootEnvironment.acceptBridge(handedOff)) {
                preparedBridge=true;
                retainBridgeOwnership(handedOff);
            } else if(handedOff!=null)RootEnvironment.queueBridgeStop(handedOff);
        }
        if (peerMac.isEmpty()) peerMac = deviceConfig.peer;
        target = adapter.getRemoteDevice(deviceConfig.target);
        ConnectionPolicy.Action next=connection.start(deviceConfig.target,preparedBridge);
        if (next==ConnectionPolicy.Action.CONNECT_DIRECT || next==ConnectionPolicy.Action.CONNECT_BRIDGE) {
            // An explicitly prepared bridge can replace a working direct socket. The policy
            // generation already invalidated callbacks before the old socket is closed.
            if (preparedBridge) {
                if (bridgePreparation!=null) { bridgePreparation.cancel(true); bridgePreparation=null; }
            }
            controlSession.reset();
            applyConnection(next);
        }
        if (mode.allowsRouting() && intent.getBooleanExtra("yield_audio",false)) release("transport-baseline",true);
        return START_NOT_STICKY;
    }

    void restartSessionWatch() {
        if (stopped || mode != SessionMode.AUTO_HANDOFF) return;
        watch().restart();
    }

    void onMediaAccessLost() {
        if (stopped) return;
        if (playbackWatch != null) playbackWatch.onAccessLost();
    }

    private void finishPendingTake(String reason) {
        if (!mode.allowsRouting()) return;
        if (policy.consumeTake(controlSession.isReady(),!peers.isEmpty())) take(reason);
    }

    private void cancelPendingReconnect() {
        if (pendingReconnect != null) {
            handler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    private void applyConnection(ConnectionPolicy.Action action) {
        if (stopped || target == null) return;
        log("CONNECTION " + connection.snapshot(deviceConfig.target).description);
        if (connection.snapshot(deviceConfig.target).phase==ConnectionPolicy.Phase.FAILED) cleanupOwnedBridge();
        if (action == ConnectionPolicy.Action.NONE) return;
        cancelPendingReconnect();
        if (action == ConnectionPolicy.Action.CONNECT_DIRECT || action == ConnectionPolicy.Action.CONNECT_BRIDGE) {
            nativeBackendReady=action == ConnectionPolicy.Action.CONNECT_BRIDGE;
            connectSession();
        } else if (action == ConnectionPolicy.Action.RECONNECT) {
            final long generation=connection.generation();
            final String address=deviceConfig.target;
            final Runnable[] ref = new Runnable[1];
            ref[0] = () -> {
                if (pendingReconnect == ref[0]) pendingReconnect = null;
                if (!stopped && current==this && deviceConfig!=null && address.equals(deviceConfig.target))
                    applyConnection(connection.retry(generation,address));
            };
            pendingReconnect = ref[0];
            handler.postDelayed(ref[0], 5000);
        } else if (action == ConnectionPolicy.Action.PREPARE_BRIDGE) {
            final long generation=connection.generation();
            final String address=deviceConfig.target;
            // The serial queue is shared with explicit preparation/cleanup. Never request temporary Root here.
            bridgePreparation=RootEnvironment.WORK.submit(() -> {
                if (stopped || generation!=connection.generation()) return;
                RootEnvironment.Prepared prepared=RootEnvironment.prepare(getApplicationContext(),address,true,false);
                if (prepared.bridgeOwned) ownedBridge.set(prepared);
                if (Thread.currentThread().isInterrupted() || stopped || generation!=connection.generation()) {
                    boolean interrupted=Thread.interrupted();
                    try {
                        if (RootEnvironment.stopOwnedBridge(prepared)) ownedBridge.compareAndSet(prepared,null);
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                    return;
                }
                handler.post(() -> {
                    if (stopped || current!=this || deviceConfig==null || !address.equals(deviceConfig.target)
                            || generation!=connection.generation()) {
                        RootEnvironment.markBridgeStopping(prepared);
                        RootEnvironment.WORK.submit(() -> {
                            if (RootEnvironment.stopOwnedBridge(prepared)) ownedBridge.compareAndSet(prepared,null);
                        });return;
                    }
                    bridgePreparation=null;
                    boolean valid=RootEnvironment.acceptBridge(prepared);
                    if(valid)retainBridgeOwnership(prepared);
                    ConnectionPolicy.Action next=connection.bridgePrepared(generation,address,valid,
                        prepared.adapterId,prepared.missing,prepared.diagnostic);
                    if (next==ConnectionPolicy.Action.CONNECT_BRIDGE && BluetoothAdapter.checkBluetoothAddress(prepared.local)) {
                        try {updateLocalIdentity(prepared.local);}
                        catch(IllegalArgumentException error){
                            connection.unavailable(connection.generation(),address,"本机蓝牙身份与当前配置冲突，请停止后重新选择设备");
                            cleanupOwnedBridge();applyConnection(ConnectionPolicy.Action.NONE);return;
                        }
                    }
                    log("BRIDGE_PREPARED missing="+prepared.missing+" diagnostic="+prepared.diagnostic);
                    if (!valid) cleanupOwnedBridge();
                    applyConnection(next);
                });
            });
        }
    }

    private void retainBridgeOwnership(RootEnvironment.Prepared prepared) {
        if(!prepared.bridgeOwned)return;
        RootEnvironment.Prepared previousOwned=ownedBridge.getAndSet(prepared);
        if(previousOwned!=null && previousOwned!=prepared
                && (previousOwned.lease==null || !previousOwned.lease.sameInstance(prepared.lease)))
            RootEnvironment.queueBridgeStop(previousOwned);
    }
    private void cleanupOwnedBridge() {
        nativeBackendReady=false;
        RootEnvironment.Prepared prepared=ownedBridge.getAndSet(null);
        if(prepared!=null) RootEnvironment.queueBridgeStop(prepared);
    }
    private void updateLocalIdentity(String local) {
        deviceConfig=DeviceConfig.mergeLocal(deviceConfig,DeviceConfig.create(deviceConfig.target,local,deviceConfig.peer));
        selfMac=deviceConfig.local;
        android.content.SharedPreferences.Editor editor=getSharedPreferences("devices",MODE_PRIVATE).edit();
        for(Map.Entry<String,String> entry:deviceConfig.save().entrySet())editor.putString(entry.getKey(),entry.getValue());
        editor.apply();
    }

    private void connectSession() {
        if (stopped || target == null || controlSession.isConnectingOrConnected()) return;
        final long connectionGeneration=connection.generation();
        final String address=deviceConfig.target;
        if (adapter == null || !adapter.isEnabled()) {
            connection.unavailable(connectionGeneration,address,"Bluetooth 已关闭");
            cleanupOwnedBridge();
            log("CONNECTION " + connection.snapshot(address).description);return;
        }
        peers.clear();
        lastMediaState = null;
        controlSession.connect(target, address, connectionGeneration);
    }

    private void receive(byte[] data) {
        if (stopped) return;
        Boolean owns = RoutingPackets.ownershipEvent(data, data.length);
        if (owns != null) {
            Boolean previous = this.owns;
            this.owns = owns;
            log("OWNERSHIP " + owns);
            if (mode.allowsRouting() && !owns && !Boolean.FALSE.equals(previous)) release("ownership-loss", false);
        }
        SmartRouting.Message message = SmartRouting.decode(data);
        if (message != null && !peerMac.isEmpty() && peerMac.equalsIgnoreCase(message.sender)) {
            boolean localActive = Boolean.TRUE.equals(this.owns) || audioLink.isWanted()
                || (sourceType != 0 && selfMac.equalsIgnoreCase(sourceMac));
            boolean yield = peerPolicy.receive(message.fields,localActive);
            log("PEER_ROUTING fields=" + message.fields + " localActive=" + localActive + " yield=" + yield);
            if (mode.allowsRouting()) {
                if (yield) release("peer-play-call-or-manual",true);
                else if (Boolean.TRUE.equals(message.fields.get("audioRoutingSetOwnershipToFalse")))
                    log("IGNORE remote-retry-without-new-peer-play");
            }
        }
        List<String> freshPeers = RoutingPackets.connectedDevices(data,data.length);
        boolean completePeers = RoutingPackets.header(data,data.length,0x2e) && data.length>=9
            && data.length>=9+(data[8]&255)*8;
        if (deviceConfig != null && peerMac.isEmpty()) peerMac = DeviceConfig.choosePeer(freshPeers, deviceConfig, completePeers);
        if (completePeers) peers.retainAll(freshPeers);
        for (String peer : freshPeers) {
            if (peerMac.isEmpty() || !peerMac.equalsIgnoreCase(peer)) continue;
            if (!peer.equalsIgnoreCase(selfMac) && peers.add(peer)) {
                log("REMOTE_PEER discovered");
                announcePeers();
            }
        }
        if (RoutingPackets.header(data, data.length, 0x0e) && data.length >= 13) {
            sourceMac = RoutingPackets.audioSourceMac(data);
            sourceType = data[12] & 255;
            log("AUDIO_SOURCE mac=" + sourceMac + " type=" + sourceType + " local=" + selfMac.equalsIgnoreCase(sourceMac));
        }
    }

    private void announcePeers() {
        if (!mode.allowsRouting() || !controlSession.isReady() || !BluetoothAdapter.checkBluetoothAddress(selfMac)) return;
        for (String peer : peers) {
            send(RoutingPackets.announce(selfMac, peer), "announce");
        }
        if (!peers.isEmpty()) {
            lastMediaState = null;
            mediaInformation(policy.hasPlayback());
            finishPendingTake("peer-ready-player-play");
        }
    }

    private void send(byte[] data, String label) {
        if (!mode.allowsRouting()) {
            log("SEND skipped mode=" + mode + " label=" + label);
            return;
        }
        final long routingGeneration=routingModeGeneration;
        boolean accepted=controlSession.sendPacket(
            data,
            () -> !stopped && current==this && mode.allowsRouting() && routingGeneration==routingModeGeneration,
            sent -> log("SEND " + label + " ok=" + sent));
        if(!accepted) log("SEND " + label + " ok=false");
    }

    private void take(String reason) {
        if (!mode.allowsRouting()) {
            log("TAKE skipped; mode=" + mode + " reason=" + reason);
            return;
        }
        log("TAKE reason=" + reason + " owns=" + owns + " source=" + sourceMac + " type=" + sourceType);
        boolean claim = policy.needsClaim(Boolean.TRUE.equals(owns),selfMac.equalsIgnoreCase(sourceMac) && sourceType != 0);
        if (claim) send(RoutingPackets.ownership(true), "ownership-true");
        else log("KEEP existing-local-ownership");
        mediaInformation(true);
        if (claim) for (String peer : peers) send(RoutingPackets.hijack(peer,"ManualRoute"), "manual-route-request");
        BluetoothProfile a2dp = a2dpProxy();
        BluetoothProfile headset = headsetProxy();
        applyAudioLink(audioLink.want(true,a2dp == null ? 0 : a2dp.getConnectionState(target)));
        if (reason.equals("call") && headset != null && headset.getConnectionState(target) == BluetoothProfile.STATE_DISCONNECTED)
            profileAction(headset, "connect");
    }

    private void mediaInformation(boolean active) {
        if (!mode.allowsRouting()) return;
        if (Boolean.valueOf(active).equals(lastMediaState)) return;
        if (BluetoothAdapter.checkBluetoothAddress(selfMac) && !peers.isEmpty()) {
            lastMediaState = active;
            for (String peer : peers) send(RoutingPackets.mediaInformation(selfMac, peer, active), "media-state-" + active);
        }
    }

    private void release(String reason, boolean acknowledge) {
        if (!mode.allowsRouting()) {
            log("RELEASE skipped mode=" + mode + " reason=" + reason);
            return;
        }
        policy.onRemoteRequest();
        owns = false;
        log("RELEASE reason=" + reason);
        if (acknowledge) send(RoutingPackets.ownership(false), "ownership-false");
        mediaInformation(false);
        if (playbackWatch != null) playbackWatch.pauseActive();
        BluetoothProfile a2dp = a2dpProxy();
        applyAudioLink(audioLink.want(false,a2dp == null ? 0 : a2dp.getConnectionState(target)));
    }

    private void applyAudioLink(AudioLinkPolicy.Action action) {
        if (!mode.allowsRouting()) return;
        BluetoothProfile a2dp = a2dpProxy();
        if (action == AudioLinkPolicy.Action.CONNECT) profileAction(a2dp,"connect");
        if (action == AudioLinkPolicy.Action.DISCONNECT) profileAction(a2dp,"disconnect");
    }

    private void profileAction(BluetoothProfile profile, String method) {
        if (!mode.allowsRouting()) {
            log("PROFILE " + method + " skipped; mode=" + mode);
            return;
        }
        if (profile == null) { log("PROFILE " + method + " unavailable"); return; }
        try {
            Object result = profile.getClass().getMethod(method, BluetoothDevice.class).invoke(profile, target);
            log("PROFILE " + profile.getClass().getSimpleName() + " " + method + " returned=" + result);
        } catch (Exception error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                ? ((java.lang.reflect.InvocationTargetException)error).getTargetException() : error;
            log("PROFILE " + method + " failed=" + cause);
        }
    }

    private synchronized void log(String message) {
        String line = System.currentTimeMillis() + " " + message + "\n";
        Log.i("AirPodsAutoHandoff", message);
        try (FileOutputStream file = openFileOutput("handoff.log", MODE_APPEND)) {
            file.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { Log.e("AirPodsAutoHandoff", "log write", error); }
    }

    @Override public void onDestroy() {
        stopped = true;
        cancelPendingReconnect();
        connection.stop();
        RootEnvironment.Prepared pending=pendingBridge.getAndSet(null);
        if(pending!=null)RootEnvironment.queueBridgeStop(pending);
        cleanupOwnedBridge();
        if (bridgePreparation!=null) { bridgePreparation.cancel(true); bridgePreparation=null; }
        ++routingModeGeneration;
        if (current == this) current = null;
        handler.removeCallbacksAndMessages(null);
        controlSession.stop();
        if (playbackWatch != null) playbackWatch.stop();
        if (audioMonitor != null) audioMonitor.stop();
        target = null;
        peers.clear();
        owns = null;
        lastMediaState = null;
        sourceMac = null;
        sourceType = 0;
        policy.reset();
        peerPolicy.reset();
        audioLink.reset();
        log("SERVICE stopped");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
