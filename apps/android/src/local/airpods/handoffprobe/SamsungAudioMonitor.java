package local.airpods.handoffprobe;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Read-only observer of Android audio devices, Bluetooth audio profiles, and routing evidence.
 * Never mutates Bluetooth profile connections, audio routing, or playback state.
 */
final class SamsungAudioMonitor {
    interface Listener {
        void onAudioSnapshotChanged(SamsungAudioSnapshot snapshot);
        default void onA2dpStateChanged(String address, int state) {}
        default void onA2dpProxyReady(BluetoothProfile proxy) {}
        default void onAudioModeChanged(int audioMode) {}
    }

    private static final AudioAttributes MEDIA_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final AudioManager audio;
    private final BluetoothAdapter adapter;

    private boolean started;
    private boolean receiverRegistered;
    private boolean audioCallbacksRegistered;
    private boolean a2dpRequested;
    private boolean headsetRequested;
    private boolean a2dpConnected;
    private boolean headsetConnected;
    private long a2dpGeneration;
    private long headsetGeneration;
    private BluetoothProfile ownedA2dp;
    private BluetoothProfile ownedHeadset;
    private String targetAddress = "";
    private SamsungAudioSnapshot latest = SamsungAudioSnapshot.unavailable("");

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            refresh();
        }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            refresh();
        }
    };

    private final AudioManager.AudioPlaybackCallback playbackCallback = new AudioManager.AudioPlaybackCallback() {
        @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            refresh();
        }
    };

    private final AudioManager.OnModeChangedListener modeListener = new AudioManager.OnModeChangedListener() {
        @Override public void onModeChanged(int mode) {
            refresh();
            if (started && listener != null) {
                listener.onAudioModeChanged(mode);
            }
        }
    };

    private final AudioManager.OnCommunicationDeviceChangedListener commDeviceListener =
            new AudioManager.OnCommunicationDeviceChangedListener() {
                @Override public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                    refresh();
                }
            };

    private BluetoothProfile.ServiceListener newProfileListener(final int expectedKind, final long generation) {
        return new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int kind, BluetoothProfile proxy) {
                onProfileConnected(kind == 0 ? expectedKind : kind, proxy, generation);
            }

            @Override public void onServiceDisconnected(int kind) {
                onProfileDisconnected(kind == 0 ? expectedKind : kind, generation);
            }
        };
    }

    private synchronized void onProfileConnected(int kind, BluetoothProfile proxy, long generation) {
        long currentGen = kind == BluetoothProfile.A2DP ? a2dpGeneration
                : kind == BluetoothProfile.HEADSET ? headsetGeneration : -1L;
        if (!started || adapter == null || generation != currentGen) {
            if (adapter != null && proxy != null) {
                try { adapter.closeProfileProxy(kind, proxy); } catch (RuntimeException ignored) {}
            }
            return;
        }
        if (kind == BluetoothProfile.A2DP) {
            if (ownedA2dp != null && ownedA2dp != proxy) {
                closeOwnedProxy(BluetoothProfile.A2DP);
            }
            ownedA2dp = proxy;
            a2dpConnected = proxy != null;
            refresh();
            if (proxy != null && listener != null) {
                listener.onA2dpProxyReady(proxy);
            }
        } else if (kind == BluetoothProfile.HEADSET) {
            if (ownedHeadset != null && ownedHeadset != proxy) {
                closeOwnedProxy(BluetoothProfile.HEADSET);
            }
            ownedHeadset = proxy;
            headsetConnected = proxy != null;
            refresh();
        }
    }

    private synchronized void onProfileDisconnected(int kind, long generation) {
        if (kind == BluetoothProfile.A2DP) {
            if (generation != a2dpGeneration) {
                return;
            }
            a2dpConnected = false;
            a2dpRequested = false;
            ++a2dpGeneration;
            closeOwnedProxy(BluetoothProfile.A2DP);
        } else if (kind == BluetoothProfile.HEADSET) {
            if (generation != headsetGeneration) {
                return;
            }
            headsetConnected = false;
            headsetRequested = false;
            ++headsetGeneration;
            closeOwnedProxy(BluetoothProfile.HEADSET);
        } else {
            return;
        }
        refresh();
        requestProxiesIfPermitted();
    }

    private void closeOwnedProxy(int kind) {
        if (kind == BluetoothProfile.A2DP) {
            BluetoothProfile proxy = ownedA2dp;
            ownedA2dp = null;
            a2dpConnected = false;
            if (adapter != null && proxy != null) {
                try { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy); } catch (RuntimeException ignored) {}
            }
        } else if (kind == BluetoothProfile.HEADSET) {
            BluetoothProfile proxy = ownedHeadset;
            ownedHeadset = null;
            headsetConnected = false;
            if (adapter != null && proxy != null) {
                try { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy); } catch (RuntimeException ignored) {}
            }
        }
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!started || intent == null) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = parcelableDevice(intent);
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                refresh();
                if (device != null && listener != null) {
                    listener.onA2dpStateChanged(device.getAddress(), state);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                synchronized (SamsungAudioMonitor.this) {
                    if (started && adapter != null && adapter.isEnabled()) {
                        requestProxiesIfPermitted();
                    }
                }
                refresh();
            } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED.equals(action)) {
                refresh();
            }
        }
    };

    SamsungAudioMonitor(Context context, Handler handler, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.listener = listener;
        this.audio = this.context.getSystemService(AudioManager.class);
        BluetoothManager btManager = this.context.getSystemService(BluetoothManager.class);
        this.adapter = btManager == null ? null : btManager.getAdapter();
    }

    synchronized void start() {
        if (started) {
            requestProxiesIfPermitted();
            refresh();
            return;
        }
        started = true;
        if (audio != null && !audioCallbacksRegistered) {
            Executor executor = context.getMainExecutor();
            audio.registerAudioDeviceCallback(deviceCallback, handler);
            audio.registerAudioPlaybackCallback(playbackCallback, handler);
            audio.addOnModeChangedListener(executor, modeListener);
            audio.addOnCommunicationDeviceChangedListener(executor, commDeviceListener);
            audioCallbacksRegistered = true;
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(bluetoothReceiver, filter);
            }
            receiverRegistered = true;
        }
        requestProxiesIfPermitted();
        refresh();
    }

    synchronized void stop() {
        if (!started && !audioCallbacksRegistered && !receiverRegistered && ownedA2dp == null && ownedHeadset == null) {
            return;
        }
        started = false;
        ++a2dpGeneration;
        ++headsetGeneration;
        if (audio != null && audioCallbacksRegistered) {
            try { audio.unregisterAudioDeviceCallback(deviceCallback); } catch (RuntimeException ignored) {}
            try { audio.unregisterAudioPlaybackCallback(playbackCallback); } catch (RuntimeException ignored) {}
            try { audio.removeOnModeChangedListener(modeListener); } catch (RuntimeException ignored) {}
            try { audio.removeOnCommunicationDeviceChangedListener(commDeviceListener); } catch (RuntimeException ignored) {}
            audioCallbacksRegistered = false;
        }
        if (receiverRegistered) {
            try { context.unregisterReceiver(bluetoothReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        closeOwnedProxy(BluetoothProfile.A2DP);
        closeOwnedProxy(BluetoothProfile.HEADSET);
        a2dpRequested = false;
        headsetRequested = false;
        latest = SamsungAudioSnapshot.unavailable(targetAddress);
    }

    synchronized void setTargetAddress(String address) {
        String normalized = SamsungAudioPolicy.normalizeAddress(address);
        if (!normalized.equals(targetAddress)) {
            targetAddress = normalized;
        }
        if (started) {
            requestProxiesIfPermitted();
        }
        refresh();
    }

    synchronized void refreshPermission() {
        if (started) {
            requestProxiesIfPermitted();
        }
        refresh();
    }

    synchronized BluetoothProfile a2dpProxy() {
        return a2dpConnected ? ownedA2dp : null;
    }

    synchronized BluetoothProfile headsetProxy() {
        return headsetConnected ? ownedHeadset : null;
    }

    synchronized SamsungAudioSnapshot snapshot(String address) {
        String normalized = SamsungAudioPolicy.normalizeAddress(address);
        if (!started) {
            return SamsungAudioSnapshot.unavailable(normalized);
        }
        return captureSnapshot(normalized);
    }

    synchronized void refresh() {
        if (!started) {
            return;
        }
        SamsungAudioSnapshot next = captureSnapshot(targetAddress);
        latest = next;
        if (listener != null) {
            listener.onAudioSnapshotChanged(next);
        }
    }

    private void requestProxiesIfPermitted() {
        if (!started || adapter == null || !adapter.isEnabled() || !hasBluetoothPermission()) {
            return;
        }
        if (!a2dpRequested) {
            closeOwnedProxy(BluetoothProfile.A2DP);
            long gen = ++a2dpGeneration;
            try {
                a2dpRequested = adapter.getProfileProxy(
                        context,
                        newProfileListener(BluetoothProfile.A2DP, gen),
                        BluetoothProfile.A2DP);
            } catch (SecurityException ignored) {
                a2dpRequested = false;
            }
        }
        if (!headsetRequested) {
            closeOwnedProxy(BluetoothProfile.HEADSET);
            long gen = ++headsetGeneration;
            try {
                headsetRequested = adapter.getProfileProxy(
                        context,
                        newProfileListener(BluetoothProfile.HEADSET, gen),
                        BluetoothProfile.HEADSET);
            } catch (SecurityException ignored) {
                headsetRequested = false;
            }
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private SamsungAudioSnapshot captureSnapshot(String target) {
        long now = SystemClock.elapsedRealtime();
        boolean permitted = hasBluetoothPermission();
        if (!permitted || target.isEmpty()) {
            return SamsungAudioSnapshot.empty(target, permitted, now);
        }

        int rawA2dp = -1;
        int rawHeadset = -1;
        boolean a2dpReady = a2dpConnected && ownedA2dp != null && adapter != null && adapter.isEnabled();
        boolean headsetReady = headsetConnected && ownedHeadset != null && adapter != null && adapter.isEnabled();
        if (adapter != null && BluetoothAdapter.checkBluetoothAddress(target)) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(target);
                if (a2dpReady) {
                    rawA2dp = ownedA2dp.getConnectionState(device);
                }
                if (headsetReady) {
                    rawHeadset = ownedHeadset.getConnectionState(device);
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                a2dpReady = false;
                headsetReady = false;
            }
        }

        List<SamsungAudioPolicy.OutputDevice> outputDevices = readOutputDevices();
        int audioMode = audio == null ? SamsungAudioPolicy.AUDIO_MODE_INVALID : safeAudioMode();
        AudioDeviceInfo commDevice = audio == null ? null : safeCommunicationDevice();
        String commLabel = formatDeviceLabel(commDevice);
        String commAddress = commDevice == null ? "" : safeAddress(commDevice);
        SamsungAudioPolicy.RouteEvidence evidence = collectRouteEvidence(target, audioMode, commDevice);

        return SamsungAudioPolicy.evaluate(
                target,
                true,
                a2dpReady,
                rawA2dp,
                headsetReady,
                rawHeadset,
                outputDevices,
                commLabel,
                commAddress,
                audioMode,
                evidence,
                now);
    }

    private List<SamsungAudioPolicy.OutputDevice> readOutputDevices() {
        if (audio == null) {
            return Collections.emptyList();
        }
        AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices == null || devices.length == 0) {
            return Collections.emptyList();
        }
        List<SamsungAudioPolicy.OutputDevice> result = new ArrayList<>(devices.length);
        for (AudioDeviceInfo info : devices) {
            if (info == null) {
                continue;
            }
            int type = info.getType();
            String name = info.getProductName() == null ? "" : info.getProductName().toString();
            result.add(new SamsungAudioPolicy.OutputDevice(
                    safeAddress(info),
                    name,
                    isMediaBluetoothOutput(type),
                    isCommunicationBluetoothOutput(type)));
        }
        return result;
    }

    private SamsungAudioPolicy.RouteEvidence collectRouteEvidence(
            String target,
            int audioMode,
            AudioDeviceInfo commDevice) {
        if (audio == null) {
            return SamsungAudioPolicy.RouteEvidence.none();
        }
        // AudioPlaybackConfiguration.getAudioDeviceInfo() is deprecated in API 36 and left
        // unpopulated in Android framework builds, so it is not used as routing evidence.
        // On API 32 outside call/communication mode, Android provides no media route query API.
        boolean mediaEvidenceSupported = Build.VERSION.SDK_INT >= 33;
        boolean attrsAvailable = false;
        boolean attrsMatches = false;
        if (mediaEvidenceSupported) {
            try {
                List<AudioDeviceInfo> routed = audio.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES);
                if (routed != null && !routed.isEmpty()) {
                    attrsAvailable = true;
                    for (AudioDeviceInfo device : routed) {
                        if (device != null && SamsungAudioPolicy.matchesAddress(target, safeAddress(device))) {
                            attrsMatches = true;
                        }
                    }
                }
            } catch (RuntimeException ignored) {}
        }

        boolean commEvidenceSupported = SamsungAudioPolicy.isCallMode(audioMode);
        boolean commRouteAvailable = commEvidenceSupported && commDevice != null;
        boolean commRouteMatches = commRouteAvailable
                && SamsungAudioPolicy.matchesAddress(target, safeAddress(commDevice));

        return new SamsungAudioPolicy.RouteEvidence(
                mediaEvidenceSupported,
                attrsAvailable,
                attrsMatches,
                commEvidenceSupported,
                commRouteAvailable,
                commRouteMatches);
    }

    private int safeAudioMode() {
        try {
            return audio.getMode();
        } catch (RuntimeException ignored) {
            return SamsungAudioPolicy.AUDIO_MODE_INVALID;
        }
    }

    private AudioDeviceInfo safeCommunicationDevice() {
        try {
            return audio.getCommunicationDevice();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String safeAddress(AudioDeviceInfo info) {
        try {
            String addr = info.getAddress();
            return addr == null ? "" : addr;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean isMediaBluetoothOutput(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                || type == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private static boolean isCommunicationBluetoothOutput(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                || type == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private static String formatDeviceLabel(AudioDeviceInfo device) {
        if (device == null) {
            return "";
        }
        String typeLabel = deviceTypeLabel(device.getType());
        CharSequence product = device.getProductName();
        String name = product == null ? "" : product.toString().trim();
        if (name.isEmpty()) {
            return typeLabel;
        }
        return name + " (" + typeLabel + ")";
    }

    private static String deviceTypeLabel(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "蓝牙通话 (SCO)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "蓝牙媒体 (A2DP)";
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return "LE Audio 耳机";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return "LE Audio 扬声器";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "手机听筒";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "手机扬声器";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "有线耳机";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB 音频";
            default:
                return "设备类型 " + type;
        }
    }

    private static BluetoothDevice parcelableDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }
}
