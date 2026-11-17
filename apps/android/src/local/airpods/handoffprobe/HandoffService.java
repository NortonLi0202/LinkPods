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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RoutingPolicy policy = new RoutingPolicy();
    private final PeerRoutingPolicy peerPolicy = new PeerRoutingPolicy();
    private final EarbudCommands.State earbudState = new EarbudCommands.State();
    private volatile long earbudSessionGeneration;
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
    private BluetoothProfile a2dp, headset;
    private AudioManager audio;
    private volatile AapProbe session;
    private volatile boolean stopped;
    private boolean ready;
    private String selfMac = "";
    private MediaSessionManager mediaSessions;
    private boolean watching;
    private Boolean owns;
    private String sourceMac;
    private int sourceType;
    private Boolean lastMediaState;

    /** Read-only observed connection state; no Root probes or session creation. */
    public static ConnectionPolicy.Snapshot capabilities(String targetAddress) {
        HandoffService service=current;
        return service==null ? new ConnectionPolicy().snapshot(targetAddress) : service.connection.snapshot(targetAddress);
    }

    /** Read-only: this never starts the service, opens a socket, or sends a query. */
    public static EarbudSnapshot earbudSnapshot(String targetAddress) {
        HandoffService service=current;
        return service==null ? EarbudSnapshot.empty(targetAddress,"控制服务未运行")
            : service.earbudState.snapshot(targetAddress,SystemClock.elapsedRealtime());
    }
    public static boolean bridgePrepared() {
        HandoffService service=current;
        return service!=null && service.nativeBackendReady;
    }

    /** Accept a validated request on the existing session; only a real reply confirms it. */
    public static boolean setEarbudSetting(String targetAddress,int id,int value) {
        HandoffService service=current;
        if(service==null || service.stopped) return false;
        final long generation=service.earbudSessionGeneration;
        final AapProbe active=service.session;
        if(active==null) return false;
        final byte[] packet=service.earbudState.queue(targetAddress,id,value,SystemClock.elapsedRealtime());
        if(packet==null) return false;
        boolean queued=service.handler.post(() -> {
            if(current!=service || service.stopped || generation!=service.earbudSessionGeneration || active!=service.session) return;
            active.sendPacketAsync(packet,sent -> service.handler.post(() -> {
                if(current!=service || service.stopped || generation!=service.earbudSessionGeneration || active!=service.session)return;
                if(!sent) service.earbudState.failed(id);
                else active.requestState();
                service.log("EARBUD_SETTING id="+id+" sent="+sent+" confirmed=false");
            }));
        });
        if(!queued && generation==service.earbudSessionGeneration) service.earbudState.failed(id);
        return queued;
    }

    public static boolean refreshEarbudState(String targetAddress) {
        HandoffService service=current;
        if(service==null || service.stopped || !service.earbudState.snapshot(targetAddress,SystemClock.elapsedRealtime()).ready)return false;
        final long generation=service.earbudSessionGeneration;
        final AapProbe active=service.session;
        if(active==null)return false;
        return service.handler.post(() -> {
            if(current==service && !service.stopped && generation==service.earbudSessionGeneration && active==service.session)
                active.requestState();
        });
    }
    private final BroadcastReceiver audioStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = android.os.Build.VERSION.SDK_INT>=33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (stopped || target == null || !target.equals(device)) return;
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1);
            log("A2DP_STATE " + state);
            applyAudioLink(audioLink.update(state));
        }
    };
    private final Map<MediaSession.Token, MediaController> players = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> playerCallbacks = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> watchPlayers(list, false);
    private final AudioManager.OnModeChangedListener modeListener = mode -> {
        log("AUDIO_MODE " + mode);
        if (ready && (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION)) take("call");
    };
    private final BluetoothProfile.ServiceListener profiles = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int kind, BluetoothProfile proxy) {
            if (stopped) { adapter.closeProfileProxy(kind, proxy); return; }
            if (kind == BluetoothProfile.A2DP) a2dp = proxy;
            if (kind == BluetoothProfile.HEADSET) headset = proxy;
            log("PROFILE ready=" + kind);
            if (kind == BluetoothProfile.A2DP && target != null)
                applyAudioLink(audioLink.update(a2dp.getConnectionState(target)));
        }
        @Override public void onServiceDisconnected(int kind) {
            if (kind == BluetoothProfile.A2DP) a2dp = null;
            if (kind == BluetoothProfile.HEADSET) headset = null;
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        current = this;
        audio = getSystemService(AudioManager.class);
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/");
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("handoff", "LinkPods 自动交接", NotificationManager.IMPORTANCE_LOW));
        startForeground(42, new Notification.Builder(this, "handoff")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LinkPods 自动交接")
            .setContentText("保持控制通道，随媒体播放交接；可在应用内停止")
            .setOngoing(true).build());
        adapter.getProfileProxy(this, profiles, BluetoothProfile.A2DP);
        adapter.getProfileProxy(this, profiles, BluetoothProfile.HEADSET);
        IntentFilter audioFilter=new IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(audioStateReceiver,audioFilter,Context.RECEIVER_EXPORTED);
        else registerReceiver(audioStateReceiver,audioFilter);
        startSessionWatch();
        audio.addOnModeChangedListener(getMainExecutor(), modeListener);
        log("SERVICE created");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
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
        if (deviceConfig == null) earbudState.begin(requested.target);
        deviceConfig = merged;
        boolean preparedBridge=false;
        if(intent.getBooleanExtra("native_backend_ready",false)) {
            RootEnvironment.Prepared handedOff=pendingBridge.getAndSet(null);
            if(handedOff!=null && handedOff.targetAddress.equalsIgnoreCase(deviceConfig.target)
                    && RootEnvironment.acceptBridge(handedOff)) {
                preparedBridge=true;
                retainBridgeOwnership(handedOff);
            } else if(handedOff!=null)RootEnvironment.queueBridgeStop(handedOff);
        }
        selfMac = deviceConfig.local;
        if (peerMac.isEmpty()) peerMac = deviceConfig.peer;
        target = adapter.getRemoteDevice(deviceConfig.target);
        ConnectionPolicy.Action next=connection.start(deviceConfig.target,preparedBridge);
        if (next==ConnectionPolicy.Action.CONNECT_DIRECT || next==ConnectionPolicy.Action.CONNECT_BRIDGE) {
            // An explicitly prepared bridge can replace a working direct socket. The policy
            // generation already invalidated callbacks before the old socket is closed.
            if (preparedBridge) {
                if (bridgePreparation!=null) { bridgePreparation.cancel(true); bridgePreparation=null; }
            }
            final AapProbe previous=session;
            if (previous!=null) {
                ++earbudSessionGeneration;
                session=null;ready=false;
                earbudState.disconnected();
                previous.stop();
            }
            applyConnection(next);
        }
        if (intent.getBooleanExtra("yield_audio",false)) release("transport-baseline",true);
        return START_NOT_STICKY;
    }

    void startSessionWatch() {
        if (watching || stopped) return;
        try {
            mediaSessions = getSystemService(MediaSessionManager.class);
            ComponentName listener = new ComponentName(this, PlaybackListener.class);
            watchPlayers(mediaSessions.getActiveSessions(listener), true);
            mediaSessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
            watching = true;
            log("MEDIA_SESSION_WATCH ready");
        } catch (SecurityException error) { log("MEDIA_SESSION_WATCH access-required " + error); }
    }

    private void watchPlayers(List<MediaController> list, boolean passive) {
        if (list == null) return;
        Set<MediaSession.Token> present = new HashSet<>();
        for (MediaController controller : list) {
            if (controller.getPackageName().equals("com.android.server.telecom")) continue;
            MediaSession.Token token = controller.getSessionToken();
            present.add(token);
            if (players.containsKey(token)) continue;
            players.put(token, controller);
            PlaybackState state = controller.getPlaybackState();
            if (passive) policy.seed(token.toString(), state == null ? 0 : state.getState());
            else playerState(controller, state);
            MediaController.Callback callback = new MediaController.Callback() {
                @Override public void onPlaybackStateChanged(PlaybackState state) { playerState(controller, state); }
            };
            playerCallbacks.put(token, callback);
            controller.registerCallback(callback, handler);
        }
        for (MediaSession.Token token : new HashSet<>(players.keySet())) {
            if (!present.contains(token)) {
                players.remove(token).unregisterCallback(playerCallbacks.remove(token));
                policy.remove(token.toString());
            }
        }
        if (ready && !policy.hasPlayback()) mediaInformation(false);
    }

    private void playerState(MediaController controller, PlaybackState state) {
        if (stopped) return;
        int value = state == null ? 0 : state.getState();
        log("PLAYER package=" + controller.getPackageName() + " token=" + controller.getSessionToken()
            + " state=" + value + " ready=" + ready);
        if (policy.onPlayback(controller.getSessionToken().toString(), value) == RoutingPolicy.Action.TAKE) {
            finishPendingTake("player-play");
        }
        if (ready && !policy.hasPlayback()) mediaInformation(false);
    }

    private void finishPendingTake(String reason) {
        if (policy.consumeTake(ready,!peers.isEmpty())) take(reason);
    }

    private void applyConnection(ConnectionPolicy.Action action) {
        if (stopped || target == null) return;
        log("CONNECTION " + connection.snapshot(deviceConfig.target).description);
        if (connection.snapshot(deviceConfig.target).phase==ConnectionPolicy.Phase.FAILED) cleanupOwnedBridge();
        if (action == ConnectionPolicy.Action.CONNECT_DIRECT || action == ConnectionPolicy.Action.CONNECT_BRIDGE) {
            nativeBackendReady=action == ConnectionPolicy.Action.CONNECT_BRIDGE;
            connectSession();
        } else if (action == ConnectionPolicy.Action.RECONNECT) {
            final long generation=connection.generation();
            final String address=deviceConfig.target;
            handler.postDelayed(() -> {
                if (!stopped && current==this && deviceConfig!=null && address.equals(deviceConfig.target))
                    applyConnection(connection.retry(generation,address));
            },5000);
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
        if (stopped || target == null || session != null) return;
        final long connectionGeneration=connection.generation();
        final String address=deviceConfig.target;
        if (!adapter.isEnabled()) {
            connection.unavailable(connectionGeneration,address,"Bluetooth 已关闭");
            cleanupOwnedBridge();
            log("CONNECTION " + connection.snapshot(address).description);return;
        }
        peers.clear();
        lastMediaState = null;
        final long generation=++earbudSessionGeneration;
        final AapProbe[] active=new AapProbe[1];
        active[0] = new AapProbe(target, this::log, new AapProbe.Listener() {
            private volatile boolean compatibilityRequired;
            @Override public void failure(AapProbe.Failure failure) {
                if(failure==AapProbe.Failure.COMPATIBILITY_REQUIRED)compatibilityRequired=true;
            }
            private boolean valid() {
                return !stopped && current==HandoffService.this && generation==earbudSessionGeneration
                    && active[0]==session && connectionGeneration==connection.generation()
                    && deviceConfig!=null && address.equals(deviceConfig.target);
            }
            @Override public void ready() { handler.post(() -> {
                if(!valid()) return;
                connection.ready(connectionGeneration,address);
                earbudState.connected();
                ready = true; log("SESSION_READY");
                log("CONNECTION " + connection.snapshot(address).description);
                log("BACKEND " + BackendPolicy.select(!nativeBackendReady,nativeBackendReady,nativeBackendReady,nativeBackendReady)
                    + " handoff=not-verified");
                finishPendingTake("pending-player-play");
            }); }
            @Override public void packet(byte[] data) { handler.post(() -> {
                if(valid()) receive(data);
            }); }
            @Override public void ended() { handler.post(() -> {
                if(!valid()) return;
                earbudState.disconnected();
                ready = false; session = null; log("SESSION_ENDED");
                log("BACKEND " + BackendPolicy.select(false,false,false,false));
                applyConnection(connection.ended(connectionGeneration,address,compatibilityRequired));
            }); }
        });
        session=active[0];
        session.start();
    }

    private void receive(byte[] data) {
        if (stopped) return;
        boolean accepted=earbudState.receive(data);
        byte[] statePacket=AapPackets.normalize(data);
        if(statePacket.length>=6 && statePacket[0]==4 && statePacket[1]==0 && statePacket[2]==4
                && statePacket[3]==0 && statePacket[5]==0
                && (statePacket[4]==4 || statePacket[4]==9 || statePacket[4]==0x1d))
            log("EARBUD_STATE opcode="+(statePacket[4]&255)+" len="+statePacket.length+" accepted="+accepted);
        if(accepted && statePacket[4]==0x1d && deviceConfig!=null) {
            EarbudSnapshot identity=earbudState.snapshot(deviceConfig.target,SystemClock.elapsedRealtime());
            log("EARBUD_MODEL "+identity.modelNumber+" known="+identity.modelKnown());
        }
        Boolean owns = RoutingPackets.ownershipEvent(data, data.length);
        if (owns != null) {
            Boolean previous = this.owns;
            this.owns = owns;
            log("OWNERSHIP " + owns);
            if (!owns && !Boolean.FALSE.equals(previous)) release("ownership-loss", false);
        }
        SmartRouting.Message message = SmartRouting.decode(data);
        if (message != null && !peerMac.isEmpty() && peerMac.equalsIgnoreCase(message.sender)) {
            boolean localActive = Boolean.TRUE.equals(this.owns) || audioLink.isWanted()
                || (sourceType != 0 && selfMac.equalsIgnoreCase(sourceMac));
            boolean yield = peerPolicy.receive(message.fields,localActive);
            log("PEER_ROUTING fields=" + message.fields + " localActive=" + localActive + " yield=" + yield);
            if (yield) release("peer-play-call-or-manual",true);
            else if (Boolean.TRUE.equals(message.fields.get("audioRoutingSetOwnershipToFalse")))
                log("IGNORE remote-retry-without-new-peer-play");
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
                if (BluetoothAdapter.checkBluetoothAddress(selfMac)) {
                    send(RoutingPackets.announce(selfMac, peer), "announce");
                    lastMediaState = null;
                    mediaInformation(policy.hasPlayback());
                    finishPendingTake("peer-ready-player-play");
                }
            }
        }
        if (RoutingPackets.header(data, data.length, 0x0e) && data.length >= 13) {
            sourceMac = RoutingPackets.audioSourceMac(data);
            sourceType = data[12] & 255;
            log("AUDIO_SOURCE mac=" + sourceMac + " type=" + sourceType + " local=" + selfMac.equalsIgnoreCase(sourceMac));
        }
    }

    private void send(byte[] data, String label) {
        final AapProbe active=session;
        final long generation=earbudSessionGeneration;
        if(active==null){log("SEND " + label + " ok=false");return;}
        active.sendPacketAsync(data,sent -> handler.post(() -> {
            if(!stopped && active==session && generation==earbudSessionGeneration)
                log("SEND " + label + " ok=" + sent);
        }));
    }

    private void take(String reason) {
        log("TAKE reason=" + reason + " owns=" + owns + " source=" + sourceMac + " type=" + sourceType);
        boolean claim = policy.needsClaim(Boolean.TRUE.equals(owns),selfMac.equalsIgnoreCase(sourceMac) && sourceType != 0);
        if (claim) send(RoutingPackets.ownership(true), "ownership-true");
        else log("KEEP existing-local-ownership");
        mediaInformation(true);
        if (claim) for (String peer : peers) send(RoutingPackets.hijack(peer,"ManualRoute"), "manual-route-request");
        applyAudioLink(audioLink.want(true,a2dp == null ? 0 : a2dp.getConnectionState(target)));
        if (reason.equals("call") && headset != null && headset.getConnectionState(target) == BluetoothProfile.STATE_DISCONNECTED)
            profileAction(headset, "connect");
    }

    private void mediaInformation(boolean active) {
        if (Boolean.valueOf(active).equals(lastMediaState)) return;
        if (BluetoothAdapter.checkBluetoothAddress(selfMac) && !peers.isEmpty()) {
            lastMediaState = active;
            for (String peer : peers) send(RoutingPackets.mediaInformation(selfMac, peer, active), "media-state-" + active);
        }
    }

    private void release(String reason, boolean acknowledge) {
        policy.onRemoteRequest();
        owns = false;
        log("RELEASE reason=" + reason);
        if (acknowledge) send(RoutingPackets.ownership(false), "ownership-false");
        mediaInformation(false);
        for (MediaController player : players.values()) {
            PlaybackState state = player.getPlaybackState();
            if (state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING
                || state.getState() == PlaybackState.STATE_CONNECTING)) {
                player.getTransportControls().pause();
                log("PAUSE yielded-media package=" + player.getPackageName());
            }
        }
        applyAudioLink(audioLink.want(false,a2dp == null ? 0 : a2dp.getConnectionState(target)));
    }

    private void applyAudioLink(AudioLinkPolicy.Action action) {
        if (action == AudioLinkPolicy.Action.CONNECT) profileAction(a2dp,"connect");
        if (action == AudioLinkPolicy.Action.DISCONNECT) profileAction(a2dp,"disconnect");
    }

    private void profileAction(BluetoothProfile profile, String method) {
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
        stopped = true; ready = false;
        connection.stop();
        RootEnvironment.Prepared pending=pendingBridge.getAndSet(null);
        if(pending!=null)RootEnvironment.queueBridgeStop(pending);
        cleanupOwnedBridge();
        if (bridgePreparation!=null) { bridgePreparation.cancel(true); bridgePreparation=null; }
        ++earbudSessionGeneration;
        earbudState.disconnected();
        if (current == this) current = null;
        handler.removeCallbacksAndMessages(null);
        if (session != null) session.stop();
        if (watching) mediaSessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (MediaSession.Token token : players.keySet()) players.get(token).unregisterCallback(playerCallbacks.get(token));
        audio.removeOnModeChangedListener(modeListener);
        unregisterReceiver(audioStateReceiver);
        if (a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        if (headset != null) adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
        log("SERVICE stopped");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
