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
    private BluetoothProfile a2dp;
    private BluetoothProfile headset;
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

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int kind, BluetoothProfile proxy) {
            if (!started || adapter == null) {
                if (adapter != null && proxy != null) {
                    adapter.closeProfileProxy(kind, proxy);
                }
                return;
            }
            if (kind == BluetoothProfile.A2DP) {
                a2dp = proxy;
                refresh();
                if (listener != null) {
                    listener.onA2dpProxyReady(proxy);
                }
            } else if (kind == BluetoothProfile.HEADSET) {
                headset = proxy;
                refresh();
            }
        }

        @Override public void onServiceDisconnected(int kind) {
            if (kind == BluetoothProfile.A2DP) {
                a2dp = null;
                a2dpRequested = false;
            } else if (kind == BluetoothProfile.HEADSET) {
                headset = null;
                headsetRequested = false;
            }
            refresh();
        }
    };

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
            } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED.equals(action)
                    || BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
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
        if (!started && !audioCallbacksRegistered && !receiverRegistered && a2dp == null && headset == null) {
            return;
        }
        started = false;
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
        if (adapter != null) {
            if (a2dp != null) {
                try { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp); } catch (RuntimeException ignored) {}
                a2dp = null;
            }
            if (headset != null) {
                try { adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset); } catch (RuntimeException ignored) {}
                headset = null;
            }
        }
        a2dpRequested = false;
        headsetRequested = false;
        latest = SamsungAudioSnapshot.unavailable(targetAddress);
    }

    synchronized void setTargetAddress(String address) {
        String normalized = SamsungAudioPolicy.normalizeAddress(address);
        if (!normalized.equals(targetAddress)) {
            targetAddress = normalized;
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
        return a2dp;
    }

    synchronized BluetoothProfile headsetProxy() {
        return headset;
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
        if (adapter == null || !hasBluetoothPermission()) {
            return;
        }
        if (!a2dpRequested && a2dp == null) {
            try {
                a2dpRequested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP);
            } catch (SecurityException ignored) {
                a2dpRequested = false;
            }
        }
        if (!headsetRequested && headset == null) {
            try {
                headsetRequested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET);
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
        boolean a2dpReady = a2dp != null && adapter != null && adapter.isEnabled();
        boolean headsetReady = headset != null && adapter != null && adapter.isEnabled();
        if (adapter != null && BluetoothAdapter.checkBluetoothAddress(target)) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(target);
                if (a2dpReady) {
                    rawA2dp = a2dp.getConnectionState(device);
                }
                if (headsetReady) {
                    rawHeadset = headset.getConnectionState(device);
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
        boolean playbackAvailable = false;
        boolean playbackMatches = false;
        try {
            List<AudioPlaybackConfiguration> configs = audio.getActivePlaybackConfigurations();
            if (configs != null) {
                for (AudioPlaybackConfiguration config : configs) {
                    if (config == null) {
                        continue;
                    }
                    AudioAttributes attrs = config.getAudioAttributes();
                    if (attrs != null && !isMediaLikeUsage(attrs.getUsage())) {
                        continue;
                    }
                    AudioDeviceInfo device = config.getAudioDeviceInfo();
                    if (device != null) {
                        playbackAvailable = true;
                        if (SamsungAudioPolicy.matchesAddress(target, safeAddress(device))) {
                            playbackMatches = true;
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        boolean attrsAvailable = false;
        boolean attrsMatches = false;
        if (Build.VERSION.SDK_INT >= 33) {
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

        boolean commRouteAvailable = commDevice != null && SamsungAudioPolicy.isCallMode(audioMode);
        boolean commRouteMatches = commRouteAvailable
                && SamsungAudioPolicy.matchesAddress(target, safeAddress(commDevice));

        return new SamsungAudioPolicy.RouteEvidence(
                playbackAvailable,
                playbackMatches,
                attrsAvailable,
                attrsMatches,
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

    private static boolean isMediaLikeUsage(int usage) {
        return usage == AudioAttributes.USAGE_MEDIA
                || usage == AudioAttributes.USAGE_GAME
                || usage == AudioAttributes.USAGE_UNKNOWN;
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
