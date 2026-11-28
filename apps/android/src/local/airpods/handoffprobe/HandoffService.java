package local.airpods.handoffprobe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final long IDLE_REMOTE_RELEASE_DELAY_MS = 1500L;
    private static final long YIELD_COOLDOWN_MS = 4000L;
    private static final long TAKEOVER_GRACE_MS = 5000L;
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
    public static final String EXTRA_LISTENING_MODE = "listening_mode";
    public static final String EXTRA_PENDING_ACTION = "pending_action";
    public static final String EXTRA_AUTO_STARTED = "auto_started";
    public static final String ACTION_SET_LISTENING_MODE = "local.airpods.handoffprobe.action.SET_LISTENING_MODE";
    public static final String ACTION_MANUAL_HANDOFF = "local.airpods.handoffprobe.action.MANUAL_HANDOFF";
    private static final int NOTIFICATION_ID = 42;
    private static final int AUTO_START_BLOCKED_NOTIFICATION_ID = 43;
    private volatile SessionMode mode = SessionMode.CONTROL_ONLY;
    private volatile String pendingListeningModeLabel = "";
    private volatile String lastNotificationSignature = "";
    private volatile boolean autoStartedSession;
    private volatile long routingModeGeneration;
    private volatile long yieldGeneration;
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
    // Last usable ownership report. AudioLinkPolicy owns the local routing intent.
    private Boolean owns;
    private String sourceMac;
    private int sourceType;
    private String lastActiveSourceMac;
    private Runnable pendingRemoteRelease;
    private String dueRemoteReleaseMac;
    private Boolean lastMediaState;
    private Runnable pendingReconnect;
    private long handoffAttemptGeneration;
    private Runnable pendingHandoffCheck;
    private Runnable takeoverGracePending;
    private boolean ownershipLostDuringGrace;
    private boolean takeoverRetryUsed;
    private final Set<String> yieldedPlayers = new HashSet<>();
    private long yieldCooldownUntil;
    private volatile String handoffNotice = "";
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
            updateForegroundNotification();
        }
        @Override public void onPacket(byte[] data) {
            if (stopped || current != HandoffService.this) return;
            receive(data);
            syncPendingListeningMode();
            updateForegroundNotification();
        }
        @Override public void onEnded(long connectionGeneration, String address, boolean compatibilityRequired) {
            if (stopped || current != HandoffService.this || deviceConfig == null || !address.equals(deviceConfig.target)) return;
            pendingListeningModeLabel = "";
            owns = null;
            clearAudioSource();
            log("SESSION_ENDED");
            log("BACKEND " + BackendPolicy.select(false, false, false, false));
            applyConnection(connection.ended(connectionGeneration, address, compatibilityRequired, !autoStartedSession));
            updateForegroundNotification();
        }
        @Override public void onStateChanged() {
            if (stopped || current != HandoffService.this) return;
            syncPendingListeningMode();
            updateForegroundNotification();
        }
    };
    private final ControlSession controlSession = new ControlSession(handler, this::log, controlEvents);
    private final PlaybackWatch.Listener playbackEvents = new PlaybackWatch.Listener() {
        @Override public void onPlayersSeeded(Map<String, Integer> states, boolean restarted) {
            if (stopped || !mode.allowsRouting()) return;
            if (restarted) policy.reseed(states);
            else states.forEach(policy::seed);
            if (controlSession.isReady()) mediaInformation(hasActiveHostStream());
            if (restarted && policy.isPending()) finishPendingTake("watch-restart-player-play");
        }
        @Override public void onPlayerStateChanged(String playerId, String packageName, int state) {
            if (stopped || !mode.allowsRouting()) return;
            boolean ready = controlSession.isReady();
            log("PLAYER package=" + packageName + " token=" + playerId
                + " state=" + state + " ready=" + ready);
            if (state == 3) cancelPendingRemoteRelease();
            boolean bounce = SystemClock.elapsedRealtime() < yieldCooldownUntil
                    && yieldedPlayers.contains(playerId);
            RoutingPolicy.Action action = policy.onPlayback(playerId, state, !bounce);
            if (action == RoutingPolicy.Action.PAUSE) {
                log("IGNORE yield-cooldown player-play package=" + packageName);
                if (playbackWatch != null) playbackWatch.pausePlayer(playerId);
            } else if (action == RoutingPolicy.Action.TAKE) {
                finishPendingTake("player-play");
            }
            syncInactiveMediaState();
        }
        @Override public void onPlayerRemoved(String playerId) {
            if (stopped || !mode.allowsRouting()) return;
            policy.remove(playerId);
            syncInactiveMediaState();
        }
        @Override public void onSessionsSynced() {
            if (stopped || !mode.allowsRouting()) return;
            syncInactiveMediaState();
        }
        @Override public void onWatchStopped(boolean accessLost) {
            cancelPendingRemoteRelease();
            policy.reset();
            if (accessLost && !stopped && mode.allowsRouting() && controlSession.isReady() && !inCallAudioMode()) {
                mediaInformation(false);
            }
            if (accessLost && !stopped) updateForegroundNotification();
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
        boolean a2dpDisconnected = service.audioMonitor == null
                || service.audioMonitor.snapshot(targetAddress).a2dpState == SamsungAudioSnapshot.ConnectionState.DISCONNECTED;
        boolean audioYielded = mode == SessionMode.AUTO_HANDOFF && service.audioLink.isYielded() && a2dpDisconnected;
        boolean audioYieldPending = mode == SessionMode.AUTO_HANDOFF && service.audioLink.isYielded() && !a2dpDisconnected;
        ConnectionPolicy.Snapshot conn = service.connection.snapshot(targetAddress);
        String ctrl = ready ? (service.nativeBackendReady ? "桥接控制通道已连接" : "直连控制通道已连接") : conn.description;
        String handoff;
        if (mode == SessionMode.AUTO_HANDOFF) {
            if (!watchActive) {
                handoff = "已开启（等待媒体权限）";
            } else if (!service.handoffNotice.isEmpty()) {
                handoff = service.handoffNotice;
            } else if (audioYielded) {
                handoff = "已让出（其他设备使用中）";
            } else if (audioYieldPending) {
                handoff = "正在让出音频（等待系统断开）";
            } else {
                handoff = "运行中（随播放交接）";
            }
        } else {
            handoff = "关闭（纯控制模式）";
        }
        return new SessionStatus(targetAddress, mode, true, ready, ctrl, handoff, service.handoffNotice, audioYielded, audioYieldPending);
    }

    /** Evaluates the shared domain StatusSummary for both home UI and persistent notification. */
    public static StatusSummary evaluateSummary(Context context, String targetAddress, boolean bridgeRequired) {
        boolean btAllowed = context == null || Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean mediaAllowed = hasNotificationAccess(context);
        boolean targetSelected = targetAddress != null && !targetAddress.isEmpty();
        SessionStatus status = sessionStatus(targetAddress);
        EarbudSnapshot earbud = earbudSnapshot(targetAddress);
        SamsungAudioSnapshot audioState = audioSnapshot(targetAddress);
        return StatusSummaryPolicy.evaluate(btAllowed, mediaAllowed, targetSelected, status, earbud, audioState, bridgeRequired);
    }

    public static boolean hasNotificationAccess(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 27) return true;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return true;
        try {
            return nm.isNotificationListenerAccessGranted(new ComponentName(context, PlaybackListener.class));
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** Notifies user with an actionable solution when background auto-start cannot proceed. */
    public static void notifyAutoStartBlocked(Context context, String headline, String detail, StatusSummary.PrimaryAction action) {
        if (context == null) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureChannel(nm);
        Notification.Builder builder = baseNotificationBuilder(
                context,
                headline == null ? "无法自动启用自动交接" : headline,
                detail == null ? "" : detail,
                detail == null ? "" : detail,
                activityPendingIntent(context, StatusSummary.PrimaryAction.NONE, 901),
                false)
                .setAutoCancel(true);
        if (action != null && action != StatusSummary.PrimaryAction.NONE) {
            builder.addAction(new Notification.Action.Builder(
                    null, action.label, activityPendingIntent(context, action, 902)).build());
        }
        nm.notify(AUTO_START_BLOCKED_NOTIFICATION_ID, builder.build());
    }

    private static Notification.Builder baseNotificationBuilder(
            Context context,
            String title,
            String body,
            String expanded,
            PendingIntent contentIntent,
            boolean ongoing) {
        return new Notification.Builder(context, "handoff")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(expanded))
                .setContentIntent(contentIntent)
                .setOngoing(ongoing);
    }

    private static void ensureChannel(NotificationManager nm) {
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel("handoff", "LinkPods 控制服务", NotificationManager.IMPORTANCE_LOW));
        }
    }

    public static boolean manualHandoff(String targetAddress) {
        HandoffService service = current;
        if (service == null || service.stopped || service.deviceConfig == null) return false;
        if (!service.deviceConfig.target.equalsIgnoreCase(targetAddress)) return false;
        if (!service.mode.allowsRouting() || !service.controlSession.isReady()) return false;
        service.cancelPendingHandoffCheck();
        service.setHandoffNotice("");
        service.take("manual");
        return true;
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
        @Override public void onAudioSnapshotChanged(SamsungAudioSnapshot snapshot) {
            updateForegroundNotification();
        }
        @Override public void onA2dpStateChanged(String address, int state) {
            if (stopped || target == null || !target.getAddress().equalsIgnoreCase(address)) return;
            log("A2DP_STATE " + state);
            if (state == BluetoothProfile.STATE_CONNECTED && !audioLink.isYielded() && Boolean.TRUE.equals(owns)) {
                cancelPendingHandoffCheck();
                setHandoffNotice("");
            }
            if (!mode.allowsRouting()) return;
            AudioLinkPolicy.Action action = audioLink.update(state);
            if (action == AudioLinkPolicy.Action.DISCONNECT && audioLink.isYielded())
                log("BLOCK yielded-a2dp-reconnect state=" + state);
            applyAudioLink(action);
            if (state == BluetoothProfile.STATE_CONNECTED) finishDueRemoteRelease(state);
            if (state == BluetoothProfile.STATE_DISCONNECTED && audioLink.isYielded())
                finishPendingTake("yielded-a2dp-disconnected");
            updateForegroundNotification();
        }
        @Override public void onA2dpProxyReady(BluetoothProfile proxy) {
            log("PROFILE ready=" + BluetoothProfile.A2DP);
            if (!stopped && target != null && mode.allowsRouting()) {
                int state = safeConnectionState(proxy);
                if (state >= 0) {
                    applyAudioLink(audioLink.update(state));
                    if (audioLink.isYielded()) finishPendingTake("yielded-a2dp-proxy-ready");
                    else if (state == BluetoothProfile.STATE_CONNECTED) finishDueRemoteRelease(state);
                }
            }
            updateForegroundNotification();
        }
        @Override public void onAudioModeChanged(int audioMode) {
            log("AUDIO_MODE " + audioMode);
            if (!controlSession.isReady() || !mode.allowsRouting()) return;
            if (isCallAudioMode(audioMode)) take("call");
            else syncInactiveMediaState();
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
        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureChannel(nm);
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, "handoff")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LinkPods 耳机控制")
            .setContentText("保持耳机控制通道；可在应用内停止")
            .setOngoing(true).build());
        audioMonitor = new SamsungAudioMonitor(this, handler, audioMonitorListener);
        audioMonitor.start();
        log("SERVICE created");
    }

    private void setHandoffNotice(String notice) {
        String next = notice == null ? "" : notice;
        if (!next.equals(handoffNotice)) {
            handoffNotice = next;
            updateForegroundNotification();
        }
    }

    public static void setListeningModeFromNotification(int mode) {
        HandoffService service = current;
        if (service != null && !service.stopped) {
            service.applyListeningModeAction(mode);
        }
    }

    private void applyListeningModeAction(int requestedListeningMode) {
        if (deviceConfig == null || !controlSession.isReady() || requestedListeningMode < 0) return;
        EarbudSnapshot earbud = controlSession.snapshot(deviceConfig.target);
        if (!earbud.supportsListeningMode(requestedListeningMode)) return;
        String label = StatusSummaryPolicy.modeDisplayName(requestedListeningMode);
        String previousPending = pendingListeningModeLabel;
        pendingListeningModeLabel = label;
        if (!controlSession.setSetting(deviceConfig.target, EarbudCommands.LISTENING_MODE, requestedListeningMode)) {
            EarbudSnapshot refreshed = controlSession.snapshot(deviceConfig.target);
            pendingListeningModeLabel = refreshed.pending.contains(EarbudCommands.LISTENING_MODE) ? previousPending : "";
        } else {
            handler.postDelayed(() -> {
                if (!stopped && current == this) {
                    syncPendingListeningMode();
                    updateForegroundNotification();
                }
            }, 5100L);
        }
        updateForegroundNotification();
    }

    private void syncPendingListeningMode() {
        if (pendingListeningModeLabel.isEmpty() || deviceConfig == null) return;
        EarbudSnapshot earbud = controlSession.snapshot(deviceConfig.target);
        if (!earbud.pending.contains(EarbudCommands.LISTENING_MODE)) {
            pendingListeningModeLabel = "";
            return;
        }
        Integer currentMode = earbud.value(EarbudCommands.LISTENING_MODE);
        if (currentMode != null && StatusSummaryPolicy.modeDisplayName(currentMode).equals(pendingListeningModeLabel)) {
            pendingListeningModeLabel = "";
        }
    }

    private static PendingIntent activityPendingIntent(Context context, StatusSummary.PrimaryAction action, int requestCode) {
        boolean hasAction = action != StatusSummary.PrimaryAction.NONE;
        Intent launch = new Intent(context, hasAction ? MainActivity.NotificationEntry.class : MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (hasAction) launch.putExtra(EXTRA_PENDING_ACTION, action.name());
        return PendingIntent.getActivity(context, requestCode, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent receiverPendingIntent(String actionName, int modeValue, int requestCode) {
        Intent intent = new Intent(this, NotificationActionReceiver.class).setAction(actionName);
        if (modeValue >= 0) {
            intent.putExtra(EXTRA_LISTENING_MODE, modeValue);
        }
        if (ACTION_MANUAL_HANDOFF.equals(actionName) && deviceConfig != null) {
            intent.putExtra("address", deviceConfig.target);
        }
        return PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateForegroundNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        String targetAddress = deviceConfig == null ? "" : deviceConfig.target;
        SessionStatus status = sessionStatus(targetAddress);
        EarbudSnapshot earbud = targetAddress.isEmpty()
                ? EarbudSnapshot.empty("", "尚未选择目标耳机")
                : controlSession.snapshot(targetAddress);
        if (!earbud.pending.contains(EarbudCommands.LISTENING_MODE)) {
            pendingListeningModeLabel = "";
        }
        ConnectionPolicy.Snapshot conn = connection.snapshot(targetAddress);
        boolean bridgeNeeded = !status.channelReady && conn.compatibilityRequired;
        StatusSummary summary = evaluateSummary(this, targetAddress, bridgeNeeded);
        String telemetry = StatusSummaryPolicy.formatNotificationTelemetry(status, earbud, pendingListeningModeLabel);
        List<StatusSummary.NotificationAction> actions = StatusSummaryPolicy.selectNotificationActions(summary, status, earbud);

        String title = summary.level == StatusSummary.Level.NORMAL
                ? "LinkPods · " + summary.headline
                : summary.levelLabel() + " · " + summary.headline;
        String body = telemetry.isEmpty() ? summary.detail : telemetry;
        String expanded = telemetry.isEmpty() ? summary.detail : telemetry + "\n" + summary.detail;

        StringBuilder sig = new StringBuilder().append(title).append('|').append(body).append('|').append(expanded);
        for (StatusSummary.NotificationAction action : actions) {
            sig.append('|').append(action.kind).append(':').append(action.label).append(':').append(action.listeningMode);
        }
        String signature = sig.toString();
        if (signature.equals(lastNotificationSignature)) {
            return;
        }
        lastNotificationSignature = signature;

        Notification.Builder builder = baseNotificationBuilder(
                this,
                title,
                body,
                expanded,
                activityPendingIntent(this, StatusSummary.PrimaryAction.NONE, 100),
                true);
        for (int i = 0; i < actions.size(); i++) {
            StatusSummary.NotificationAction action = actions.get(i);
            PendingIntent pi;
            if (action.kind == StatusSummary.NotificationAction.Kind.LISTENING_MODE) {
                pi = receiverPendingIntent(ACTION_SET_LISTENING_MODE, action.listeningMode, 200 + action.listeningMode);
            } else if (action.primaryAction == StatusSummary.PrimaryAction.MANUAL_HANDOFF) {
                pi = receiverPendingIntent(ACTION_MANUAL_HANDOFF, -1, 300 + i);
            } else {
                pi = activityPendingIntent(this, action.primaryAction, 300 + i);
            }
            builder.addAction(new Notification.Action.Builder(null, action.label, pi).build());
        }
        nm.notify(NOTIFICATION_ID, builder.build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (stopped) { return START_NOT_STICKY; }
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String intentAction = intent.getAction();
        if (ACTION_SET_LISTENING_MODE.equals(intentAction)) {
            applyListeningModeAction(intent.getIntExtra(EXTRA_LISTENING_MODE, -1));
            return START_NOT_STICKY;
        }
        if (ACTION_MANUAL_HANDOFF.equals(intentAction)) {
            if (deviceConfig != null) {
                manualHandoff(deviceConfig.target);
            }
            return START_NOT_STICKY;
        }
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
        autoStartedSession = intent.getBooleanExtra(EXTRA_AUTO_STARTED, false);
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
            clearAudioSource();
            log("SESSION_MODE changed: " + previousMode + " -> " + requestedMode);
            updateForegroundNotification();
            if (requestedMode != SessionMode.AUTO_HANDOFF) {
                if (!intent.getBooleanExtra(EXTRA_AUTO_STARTED, false)) {
                    BluetoothAutoStartReceiver.onUserStoppedOrDowngraded();
                }
                if (playbackWatch != null) playbackWatch.stop();
                policy.reset();
                peerPolicy.reset();
                audioLink.reset();
                lastMediaState = null;
                owns = null;
                cancelPendingHandoffCheck();
                cancelYieldCooldown();
                handoffNotice = "";
            }
        }
        if (requestedMode == SessionMode.AUTO_HANDOFF
                && (previousMode != requestedMode || !watch().isWatching())) {
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
            clearAudioSource();
            controlSession.reset();
            applyConnection(next);
        }
        if (mode.allowsRouting() && intent.getBooleanExtra("yield_audio",false)) release("transport-baseline",true);
        return START_NOT_STICKY;
    }

    void restartSessionWatch() {
        if (stopped || mode != SessionMode.AUTO_HANDOFF) return;
        watch().restart();
        if (!watch().isActive() || policy.hasPlayback()) cancelPendingRemoteRelease();
        updateForegroundNotification();
    }

    void onMediaAccessLost() {
        if (stopped) return;
        if (playbackWatch != null) playbackWatch.onAccessLost();
        updateForegroundNotification();
    }

    static void stopAutoStartedSession(String targetAddress) {
        HandoffService service = current;
        if (service != null && !service.stopped && service.autoStartedSession
                && service.deviceConfig != null && service.deviceConfig.target.equalsIgnoreCase(targetAddress)) {
            service.stopSelf();
        }
    }

    private static boolean isCallAudioMode(int audioMode) {
        return audioMode == AudioManager.MODE_IN_CALL
            || audioMode == AudioManager.MODE_IN_COMMUNICATION;
    }

    private boolean inCallAudioMode() {
        return audio != null && isCallAudioMode(audio.getMode());
    }

    private boolean hasSyncedPlayback() {
        return playbackWatch != null && playbackWatch.isActive() && policy.hasPlayback();
    }

    private boolean hasActiveHostStream() {
        return hasSyncedPlayback() || inCallAudioMode();
    }

    private boolean isHostConfirmedIdle() {
        return playbackWatch != null && playbackWatch.isActive() && !policy.hasPlayback() && !inCallAudioMode();
    }

    private void syncInactiveMediaState() {
        if (controlSession.isReady() && isHostConfirmedIdle()) {
            mediaInformation(false);
        }
    }

    private void finishPendingTake(String reason) {
        if (!mode.allowsRouting() || !watch().isActive() || deviceConfig == null) return;
        SamsungAudioSnapshot audioState = audioMonitor == null
                ? SamsungAudioSnapshot.unavailable(deviceConfig.target)
                : audioMonitor.snapshot(deviceConfig.target);
        boolean yielded = audioLink.isYielded();
        if (yielded && audioState.permissionGranted
                && audioState.a2dpState != SamsungAudioSnapshot.ConnectionState.DISCONNECTED) return;
        boolean targetConfirmed = SamsungAudioPolicy.isTargetOutputConfirmed(audioState)
                || (yielded && audioState.permissionGranted
                    && audioState.a2dpState == SamsungAudioSnapshot.ConnectionState.DISCONNECTED);
        RoutingPolicy.TakeDecision decision = policy.evaluateTake(
                controlSession.isReady(),
                !peers.isEmpty(),
                targetConfirmed);
        if (decision == RoutingPolicy.TakeDecision.TAKE) {
            setHandoffNotice("");
            take(reason);
        } else if (decision == RoutingPolicy.TakeDecision.SKIP) {
            setHandoffNotice("目标输出未指向此耳机，已保留当前播放（可手动切换）");
            log("TAKE skipped; target-output-not-confirmed routeState=" + audioState.routeState
                    + " mediaAvailable=" + audioState.mediaOutputAvailable);
        }
    }

    private void cancelPendingHandoffCheck() {
        ++handoffAttemptGeneration;
        if (pendingHandoffCheck != null) {
            handler.removeCallbacks(pendingHandoffCheck);
            pendingHandoffCheck = null;
        }
    }

    private void scheduleHandoffCheck() {
        cancelPendingHandoffCheck();
        final long routingGeneration = routingModeGeneration;
        final long attemptGeneration = handoffAttemptGeneration;
        final Runnable[] ref = new Runnable[1];
        ref[0] = () -> {
            if (pendingHandoffCheck == ref[0]) pendingHandoffCheck = null;
            if (stopped || current != this || !mode.allowsRouting()
                    || routingGeneration != routingModeGeneration
                    || attemptGeneration != handoffAttemptGeneration) {
                return;
            }
            BluetoothProfile a2dp = a2dpProxy();
            boolean a2dpConnected = a2dp != null && target != null
                    && a2dp.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED;
            if (Boolean.TRUE.equals(owns) && a2dpConnected) {
                setHandoffNotice("");
                return;
            }
            if (!hasActiveHostStream()) {
                setHandoffNotice("自动交接未成功，请点击手动切换或重新播放");
                log("HANDOFF_CHECK failed-audio-interrupted");
            } else {
                log("HANDOFF_CHECK unconfirmed; keeping current playback");
            }
        };
        pendingHandoffCheck = ref[0];
        handler.postDelayed(ref[0], 4000);
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
        peerPolicy.reset();
        lastMediaState = null;
        lastActiveSourceMac = null;
        controlSession.connect(target, address, connectionGeneration);
    }

    private void receive(byte[] data) {
        if (stopped) return;
        Boolean ownership = RoutingPackets.ownershipEvent(data, data.length);
        if (ownership != null && mode.allowsRouting() && !audioLink.isYielded()) {
            Boolean previous = owns;
            owns = ownership;
            log("OWNERSHIP " + ownership);
            if (ownership) {
                if (!hasActivePeerSource()) ownershipLostDuringGrace = false;
                BluetoothProfile a2dp = a2dpProxy();
                if (a2dp != null && target != null && a2dp.getConnectionState(target) == BluetoothProfile.STATE_CONNECTED) {
                    cancelPendingHandoffCheck();
                    setHandoffNotice("");
                }
            }
            if (!ownership && !deferTakeoverLoss("ownership-loss")
                    && (!Boolean.FALSE.equals(previous) || audioLink.isWanted())) {
                release("ownership-loss", false);
            }
        }
        SmartRouting.Message message = RoutingPackets.header(data, data.length, 0x11)
                ? SmartRouting.decode(data) : null;
        if (mode.allowsRouting() && message != null && !peerMac.isEmpty() && peerMac.equalsIgnoreCase(message.sender)) {
            boolean localActive = !audioLink.isYielded() && !inCallAudioMode()
                && (Boolean.TRUE.equals(owns) || audioLink.isWanted()
                    || (sourceType != 0 && selfMac.equalsIgnoreCase(sourceMac)));
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
                announcePeers();
            }
        }
        if (RoutingPackets.header(data, data.length, 0x0e) && data.length >= 13) {
            sourceMac = RoutingPackets.audioSourceMac(data);
            sourceType = data[12] & 255;
            log("AUDIO_SOURCE mac=" + sourceMac + " type=" + sourceType + " local=" + selfMac.equalsIgnoreCase(sourceMac));
            boolean activeSource = sourceType != 0
                    && BluetoothAdapter.checkBluetoothAddress(sourceMac)
                    && !"00:00:00:00:00:00".equals(sourceMac);
            if (!activeSource) {
                cancelPendingRemoteRelease();
                return;
            }
            // Idle-source gaps of any length still belong to the last local stream; a switch
            // straight to the peer is a takeover, not a reason to keep the tablet attached.
            boolean wasLocal = selfMac.equalsIgnoreCase(lastActiveSourceMac);
            if (selfMac.equalsIgnoreCase(sourceMac)) {
                lastActiveSourceMac = sourceMac;
                takeoverRetryUsed = false;
                if (!Boolean.FALSE.equals(owns)) ownershipLostDuringGrace = false;
                cancelPendingRemoteRelease();
                return;
            }
            if (peerMac.isEmpty()) peerMac = sourceMac;
            if (!peerMac.equalsIgnoreCase(sourceMac)) {
                cancelPendingRemoteRelease();
                return;
            }
            lastActiveSourceMac = sourceMac;
            boolean discoveredPeer = peers.add(sourceMac);
            if (mode.allowsRouting() && (wasLocal || takeoverRetryUsed) && !audioLink.isYielded() && !inCallAudioMode()) {
                if (!deferTakeoverLoss("audio-source-remote-takeover")) {
                    release("audio-source-remote-takeover", false);
                }
                return;
            }
            if (discoveredPeer) finishPendingTake("audio-source-peer");
            scheduleRemoteSourceRelease(sourceMac);
        }
    }

    private void clearAudioSource() {
        cancelTakeoverGrace();
        sourceMac = null;
        sourceType = 0;
        lastActiveSourceMac = null;
        cancelPendingRemoteRelease();
    }

    private void cancelPendingRemoteRelease() {
        dueRemoteReleaseMac = null;
        if (pendingRemoteRelease != null) {
            handler.removeCallbacks(pendingRemoteRelease);
            pendingRemoteRelease = null;
        }
    }

    private void scheduleRemoteSourceRelease(final String expectedRemoteMac) {
        if (!mode.allowsRouting() || audioLink.isYielded() || ownershipLostDuringGrace || !isHostConfirmedIdle()) {
            cancelPendingRemoteRelease();
            return;
        }
        if (expectedRemoteMac.equalsIgnoreCase(dueRemoteReleaseMac)) return;
        cancelPendingRemoteRelease();
        pendingRemoteRelease = () -> {
            pendingRemoteRelease = null;
            dueRemoteReleaseMac = expectedRemoteMac;
            BluetoothProfile a2dp = a2dpProxy();
            finishDueRemoteRelease(a2dp != null && target != null
                    ? a2dp.getConnectionState(target) : BluetoothProfile.STATE_DISCONNECTED);
        };
        handler.postDelayed(pendingRemoteRelease, IDLE_REMOTE_RELEASE_DELAY_MS);
    }

    private void finishDueRemoteRelease(int a2dpState) {
        if (dueRemoteReleaseMac == null) return;
        boolean eligible = !stopped && current == this && mode.allowsRouting() && !audioLink.isYielded()
                && sourceType != 0 && dueRemoteReleaseMac.equalsIgnoreCase(sourceMac) && isHostConfirmedIdle();
        if (eligible && a2dpState == BluetoothProfile.STATE_CONNECTING) return;
        dueRemoteReleaseMac = null;
        if (eligible && a2dpState == BluetoothProfile.STATE_CONNECTED) release("audio-source-remote", false);
    }

    private void announcePeers() {
        if (!mode.allowsRouting() || !controlSession.isReady() || !BluetoothAdapter.checkBluetoothAddress(selfMac)) return;
        for (String peer : peers) {
            send(RoutingPackets.announce(selfMac, peer), "announce", false);
        }
        if (!peers.isEmpty()) {
            lastMediaState = null;
            mediaInformation(hasActiveHostStream());
            finishPendingTake("peer-ready-player-play");
        }
    }

    private void send(byte[] data, String label, boolean cancelOnYield) {
        if (!mode.allowsRouting()) {
            log("SEND skipped mode=" + mode + " label=" + label);
            return;
        }
        final long routingGeneration=routingModeGeneration;
        final long localYieldGeneration = yieldGeneration;
        boolean accepted=controlSession.sendPacket(
            data,
            () -> !stopped && current==this && mode.allowsRouting() && routingGeneration==routingModeGeneration
                && (!cancelOnYield || localYieldGeneration == yieldGeneration),
            sent -> log("SEND " + label + " ok=" + sent));
        if(!accepted) log("SEND " + label + " ok=false");
    }

    private void take(String reason) {
        take(reason, false);
    }

    private void take(String reason, boolean reclaim) {
        if (!mode.allowsRouting()) {
            log("TAKE skipped; mode=" + mode + " reason=" + reason);
            return;
        }
        log("TAKE reason=" + reason + " owns=" + owns + " source=" + sourceMac + " type=" + sourceType);
        boolean manual = "manual".equals(reason);
        boolean wasYielded = audioLink.isYielded();
        if (manual) policy.clearPendingTake();
        if (manual || wasYielded) cancelTakeoverGrace();
        cancelPendingRemoteRelease();
        cancelYieldCooldown();
        // A fresh play edge can claim, but a deferred loss is resolved only by the grace timer.
        boolean canClaim = manual || (!ownershipLostDuringGrace && !takeoverRetryUsed);
        boolean claim = reclaim || (canClaim && policy.needsClaim(
                Boolean.TRUE.equals(owns), selfMac.equalsIgnoreCase(sourceMac) && sourceType != 0));
        BluetoothProfile a2dp = a2dpProxy();
        int a2dpState = safeConnectionState(a2dp);
        AudioLinkPolicy.Action linkAction = audioLink.want(true, a2dpState < 0 ? 0 : a2dpState);
        if (wasYielded) updateForegroundNotification();
        if (claim) {
            if (!manual && !reclaim && takeoverGracePending == null) {
                takeoverGracePending = this::finishTakeoverGrace;
                handler.postDelayed(takeoverGracePending, TAKEOVER_GRACE_MS);
            }
            send(RoutingPackets.ownership(true), "ownership-true", true);
            lastMediaState = null;
        } else if (canClaim) {
            log("KEEP existing-local-ownership");
        }
        mediaInformation(true);
        if (claim) for (String peer : peers) {
            send(RoutingPackets.showNearbyUi(peer, manual ? "ManualRoute" : "hijackv2"), "show-nearby-ui", true);
            if (manual) send(RoutingPackets.hijack(peer,"ManualRoute"), "manual-route-request", true);
            else send(RoutingPackets.hijack(peer), "hijack-request", true);
        }
        BluetoothProfile headset = headsetProxy();
        applyAudioLink(linkAction);
        if (reason.equals("call") && headset != null && safeConnectionState(headset) == BluetoothProfile.STATE_DISCONNECTED)
            profileAction(headset, "connect");
        if (!"call".equals(reason) && !"manual".equals(reason)) {
            scheduleHandoffCheck();
        }
    }

    private boolean deferTakeoverLoss(String reason) {
        if (takeoverGracePending == null || takeoverRetryUsed) return false;
        ownershipLostDuringGrace = true;
        cancelPendingRemoteRelease();
        log("DEFER " + reason + " during-takeover-grace");
        return true;
    }

    private boolean hasActivePeerSource() {
        return sourceType != 0 && !peerMac.isEmpty() && peerMac.equalsIgnoreCase(sourceMac);
    }

    private void finishTakeoverGrace() {
        takeoverGracePending = null;
        if (!ownershipLostDuringGrace) return;
        ownershipLostDuringGrace = false;
        if (stopped || current != this || !mode.allowsRouting() || audioLink.isYielded()) return;
        if (hasActiveHostStream() && !takeoverRetryUsed) {
            takeoverRetryUsed = true;
            log("RECLAIM after-takeover-grace");
            take("takeover-grace", true);
        } else {
            release("ownership-loss-after-grace", false);
        }
    }

    private void cancelTakeoverGrace() {
        if (takeoverGracePending != null) {
            handler.removeCallbacks(takeoverGracePending);
            takeoverGracePending = null;
        }
        ownershipLostDuringGrace = false;
        takeoverRetryUsed = false;
    }

    private void mediaInformation(boolean active) {
        if (!mode.allowsRouting()) return;
        active = active && !audioLink.isYielded();
        if (Boolean.valueOf(active).equals(lastMediaState)) return;
        if (BluetoothAdapter.checkBluetoothAddress(selfMac) && !peers.isEmpty()) {
            lastMediaState = active;
            for (String peer : peers) send(RoutingPackets.mediaInformation(selfMac, peer, active), "media-state-" + active, active);
        }
    }

    private void release(String reason, boolean acknowledge) {
        if (!mode.allowsRouting()) {
            log("RELEASE skipped mode=" + mode + " reason=" + reason);
            return;
        }
        if (audioLink.isYielded() || inCallAudioMode()) return;
        ++yieldGeneration;
        cancelTakeoverGrace();
        cancelPendingHandoffCheck();
        cancelPendingRemoteRelease();
        setHandoffNotice("");
        policy.onRemoteRequest();
        owns = false;
        BluetoothProfile a2dp = a2dpProxy();
        int a2dpState = safeConnectionState(a2dp);
        AudioLinkPolicy.Action linkAction = a2dpState < 0
                ? AudioLinkPolicy.Action.NONE
                : audioLink.want(false, a2dpState);
        yieldCooldownUntil = SystemClock.elapsedRealtime() + YIELD_COOLDOWN_MS;
        log("RELEASE reason=" + reason);
        if (acknowledge) send(RoutingPackets.ownership(false), "ownership-false", false);
        mediaInformation(false);
        if (playbackWatch != null) playbackWatch.pauseActive(yieldedPlayers);
        if (a2dpState < 0) {
            audioLink.reset();
            log("RELEASE a2dp-proxy-unavailable");
            setHandoffNotice("未能断开系统音频，当前可能仍由本机输出（可手动切换）");
        } else {
            applyAudioLink(linkAction);
        }
        updateForegroundNotification();
    }

    private void cancelYieldCooldown() {
        yieldCooldownUntil = 0;
        yieldedPlayers.clear();
    }

    private int safeConnectionState(BluetoothProfile profile) {
        if (profile == null || target == null) return -1;
        try {
            return profile.getConnectionState(target);
        } catch (RuntimeException error) {
            log("PROFILE state failed=" + error);
            return -1;
        }
    }

    private boolean applyAudioLink(AudioLinkPolicy.Action action) {
        if (!mode.allowsRouting()) return false;
        BluetoothProfile a2dp = a2dpProxy();
        if (action == AudioLinkPolicy.Action.CONNECT) return profileAction(a2dp,"connect");
        if (action == AudioLinkPolicy.Action.DISCONNECT) {
            if (profileAction(a2dp,"disconnect")) return true;
            audioLink.reset();
            setHandoffNotice("未能断开系统音频，当前可能仍由本机输出（可手动切换）");
            if (policy.isPending()) finishPendingTake("a2dp-disconnect-failed");
            return false;
        }
        return true;
    }

    private boolean profileAction(BluetoothProfile profile, String method) {
        if (!mode.allowsRouting()) {
            log("PROFILE " + method + " skipped; mode=" + mode);
            return false;
        }
        if (profile == null) { log("PROFILE " + method + " unavailable"); return false; }
        try {
            Object result = profile.getClass().getMethod(method, BluetoothDevice.class).invoke(profile, target);
            log("PROFILE " + profile.getClass().getSimpleName() + " " + method + " returned=" + result);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Exception error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                ? ((java.lang.reflect.InvocationTargetException)error).getTargetException() : error;
            log("PROFILE " + method + " failed=" + cause);
            return false;
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
        clearAudioSource();
        cancelPendingHandoffCheck();
        cancelYieldCooldown();
        handoffNotice = "";
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
        policy.reset();
        peerPolicy.reset();
        audioLink.reset();
        log("SERVICE stopped");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
