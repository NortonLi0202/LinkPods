package local.airpods.handoffprobe;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;

/** Listens for target AirPods connection changes to auto-start opted-in AUTO_HANDOFF or stop on full disconnect. */
public final class BluetoothAutoStartReceiver extends BroadcastReceiver {
    static final String PREF_AUTO_START_OPT_IN = "auto_start_handoff";
    private static final String PREFS_DEVICES = "devices";
    private static final long DISCONNECT_DEBOUNCE_MS = 4000L;
    private static final AutoStartPolicy POLICY = new AutoStartPolicy();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable pendingStop;
    private static PendingResult pendingDisconnect;
    private static String observedTarget = "";
    private static boolean classicDisconnected;
    private static boolean leConnected;

    public static void onUserStoppedOrDowngraded() {
        POLICY.onUserStoppedOrDowngraded();
    }

    public static void onManualAutoHandoffEnabled() {
        cancelPendingStop();
        POLICY.onManualAutoHandoffEnabled();
    }

    private static SharedPreferences devicePrefs(Context context) {
        return context.getSharedPreferences(PREFS_DEVICES, Context.MODE_PRIVATE);
    }

    public static void setAutoStartOptIn(Context context, boolean optedIn) {
        if (context == null) return;
        devicePrefs(context)
                .edit()
                .putBoolean(PREF_AUTO_START_OPT_IN, optedIn)
                .apply();
    }

    public static boolean isAutoStartOptedIn(Context context) {
        return context != null && devicePrefs(context)
                .getBoolean(PREF_AUTO_START_OPT_IN, false);
    }

    private static synchronized void cancelPendingStop() {
        if (pendingStop != null) {
            HANDLER.removeCallbacks(pendingStop);
            pendingStop = null;
        }
        if (pendingDisconnect != null) {
            pendingDisconnect.finish();
            pendingDisconnect = null;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        boolean hasBtPermission = Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        if (!hasBtPermission) return;

        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) return;
        String address = device.getAddress();
        if (address == null || address.isEmpty()) return;

        DeviceConfig saved = loadSavedConfig(context);
        if (saved == null || !saved.target.equalsIgnoreCase(address)) return;
        if (!saved.target.equalsIgnoreCase(observedTarget)) {
            cancelPendingStop();
            observedTarget = saved.target;
            classicDisconnected = false;
            leConnected = false;
        }

        boolean leTransport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, BluetoothDevice.TRANSPORT_BREDR)
                == BluetoothDevice.TRANSPORT_LE;
        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            if (leTransport) {
                leConnected = false;
            } else {
                classicDisconnected = true;
            }
            if (classicDisconnected) scheduleDebouncedStop(context.getApplicationContext(), saved.target, goAsync());
            return;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) && leTransport) {
            leConnected = true;
            return;
        }

        if (isConnectEvent(action, intent)) {
            classicDisconnected = false;
            cancelPendingStop();
            boolean optedIn = isAutoStartOptedIn(context);
            boolean running = HandoffService.current != null;
            AutoStartPolicy.ConnectDecision decision =
                    POLICY.onTargetConnected(address, saved.target, optedIn, true, running);
            if (decision == AutoStartPolicy.ConnectDecision.START_AUTO_HANDOFF) {
                startAutoHandoff(context, saved);
            }
        } else if (classicDisconnected && isProfileState(action, intent, BluetoothProfile.STATE_DISCONNECTED)) {
            // A profile may report its teardown after the classic ACL broadcast.
            scheduleDebouncedStop(context.getApplicationContext(), saved.target, goAsync());
        }
    }

    private static boolean isConnectEvent(String action, Intent intent) {
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) return true;
        return isProfileState(action, intent, BluetoothProfile.STATE_CONNECTED);
    }

    private static boolean isProfileState(String action, Intent intent, int state) {
        return (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action))
                && intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == state;
    }

    private static void startAutoHandoff(Context context, DeviceConfig saved) {
        Intent service = new Intent(context, HandoffService.class)
                .putExtra("address", saved.target)
                .putExtra("local_mac", saved.local)
                .putExtra("peer_address", saved.peer)
                .putExtra(HandoffService.EXTRA_SESSION_MODE, SessionMode.AUTO_HANDOFF.name())
                .putExtra(HandoffService.EXTRA_AUTO_STARTED, true);
        try {
            context.startForegroundService(service);
        } catch (RuntimeException error) {
            POLICY.onAutoStartRejected();
            HandoffService.notifyAutoStartBlocked(
                    context,
                    "无法自动启用自动交接",
                    "系统限制后台启动控制服务，可在应用内启用自动交接。",
                    StatusSummary.PrimaryAction.ENABLE_HANDOFF);
        }
    }

    private static synchronized void scheduleDebouncedStop(
            Context appContext, String targetAddress, PendingResult result) {
        cancelPendingStop();
        pendingDisconnect = result;
        pendingStop = () -> {
            pendingStop = null;
            pendingDisconnect = null;
            try {
                DeviceConfig saved = loadSavedConfig(appContext);
                if (saved != null && targetAddress.equalsIgnoreCase(saved.target)
                        && POLICY.shouldAutoStopOnDisconnect(isAnyConnectionRemaining(appContext, targetAddress))) {
                    HandoffService.stopAutoStartedSession(targetAddress);
                }
            } finally {
                if (result != null) result.finish();
            }
        };
        if (!HANDLER.postDelayed(pendingStop, DISCONNECT_DEBOUNCE_MS)) cancelPendingStop();
    }

    private static boolean isAnyConnectionRemaining(Context context, String targetAddress) {
        SamsungAudioSnapshot audio = HandoffService.audioSnapshot(targetAddress);
        if (leConnected || isProfileActive(audio.a2dpState) || isProfileActive(audio.headsetState)) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= 31
                && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        BluetoothManager bm = context.getSystemService(BluetoothManager.class);
        if (bm == null) return true;
        try {
            for (BluetoothDevice device : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                if (device != null && targetAddress.equalsIgnoreCase(device.getAddress())) return true;
            }
        } catch (SecurityException ignored) {
            return true;
        }
        return false;
    }

    private static boolean isProfileActive(SamsungAudioSnapshot.ConnectionState state) {
        return state == SamsungAudioSnapshot.ConnectionState.CONNECTED
                || state == SamsungAudioSnapshot.ConnectionState.CONNECTING;
    }

    private static DeviceConfig loadSavedConfig(Context context) {
        SharedPreferences prefs = devicePrefs(context);
        Map<String, String> values = new HashMap<>();
        for (String key : new String[]{"target", "local", "peer"}) {
            values.put(key, prefs.getString(key, null));
        }
        return DeviceConfig.restore(values);
    }
}
