package local.airpods.handoffprobe;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure Java normalization and read-only routing assessment for Samsung/Android system audio state. */
public final class SamsungAudioPolicy {
    public static final int PROFILE_DISCONNECTED = 0;
    public static final int PROFILE_CONNECTING = 1;
    public static final int PROFILE_CONNECTED = 2;
    public static final int PROFILE_DISCONNECTING = 3;

    public static final int AUDIO_MODE_INVALID = -2;
    public static final int AUDIO_MODE_NORMAL = 0;
    public static final int AUDIO_MODE_RINGTONE = 1;
    public static final int AUDIO_MODE_IN_CALL = 2;
    public static final int AUDIO_MODE_IN_COMMUNICATION = 3;
    public static final int AUDIO_MODE_CALL_SCREENING = 4;
    public static final int AUDIO_MODE_CALL_REDIRECT = 5;
    public static final int AUDIO_MODE_COMMUNICATION_REDIRECT = 6;

    public static final class OutputDevice {
        public final String address;
        public final String name;
        public final boolean mediaCapable;
        public final boolean communicationCapable;

        public OutputDevice(String address, String name, boolean mediaCapable, boolean communicationCapable) {
            this.address = normalizeAddress(address);
            this.name = name == null ? "" : name.trim();
            this.mediaCapable = mediaCapable;
            this.communicationCapable = communicationCapable;
        }
    }

    public static final class RouteEvidence {
        public final boolean mediaEvidenceSupported;
        public final boolean mediaAttributesRouteAvailable;
        public final boolean mediaAttributesMatchesTarget;
        public final boolean communicationEvidenceSupported;
        public final boolean communicationRouteAvailable;
        public final boolean communicationMatchesTarget;

        public RouteEvidence(
                boolean mediaEvidenceSupported,
                boolean mediaAttributesRouteAvailable,
                boolean mediaAttributesMatchesTarget,
                boolean communicationEvidenceSupported,
                boolean communicationRouteAvailable,
                boolean communicationMatchesTarget) {
            this.mediaEvidenceSupported = mediaEvidenceSupported;
            this.mediaAttributesRouteAvailable = mediaAttributesRouteAvailable;
            this.mediaAttributesMatchesTarget = mediaAttributesMatchesTarget;
            this.communicationEvidenceSupported = communicationEvidenceSupported;
            this.communicationRouteAvailable = communicationRouteAvailable;
            this.communicationMatchesTarget = communicationMatchesTarget;
        }

        public boolean hasAnySupportedSource() {
            return mediaEvidenceSupported || communicationEvidenceSupported;
        }

        public static RouteEvidence none() {
            return new RouteEvidence(false, false, false, false, false, false);
        }
    }

    private SamsungAudioPolicy() {}

    public static String normalizeAddress(String address) {
        if (address == null) {
            return "";
        }
        String trimmed = address.trim();
        return trimmed.isEmpty() ? "" : trimmed.toUpperCase(Locale.ROOT);
    }

    public static boolean matchesAddress(String targetAddress, String candidateAddress) {
        String target = normalizeAddress(targetAddress);
        String candidate = normalizeAddress(candidateAddress);
        return !target.isEmpty() && target.equals(candidate);
    }

    public static boolean isCallMode(int audioMode) {
        return audioMode == AUDIO_MODE_IN_CALL
                || audioMode == AUDIO_MODE_IN_COMMUNICATION
                || audioMode == AUDIO_MODE_CALL_REDIRECT
                || audioMode == AUDIO_MODE_COMMUNICATION_REDIRECT;
    }

    public static SamsungAudioSnapshot.ConnectionState normalizeConnectionState(
            int profileState,
            boolean permissionGranted,
            boolean proxyReady,
            boolean hasTarget) {
        if (!permissionGranted || !proxyReady || !hasTarget) {
            return SamsungAudioSnapshot.ConnectionState.UNKNOWN;
        }
        switch (profileState) {
            case PROFILE_DISCONNECTED:
                return SamsungAudioSnapshot.ConnectionState.DISCONNECTED;
            case PROFILE_CONNECTING:
                return SamsungAudioSnapshot.ConnectionState.CONNECTING;
            case PROFILE_CONNECTED:
                return SamsungAudioSnapshot.ConnectionState.CONNECTED;
            case PROFILE_DISCONNECTING:
                return SamsungAudioSnapshot.ConnectionState.DISCONNECTING;
            default:
                return SamsungAudioSnapshot.ConnectionState.UNKNOWN;
        }
    }

    public static boolean hasMediaOutput(String targetAddress, List<OutputDevice> outputDevices) {
        String target = normalizeAddress(targetAddress);
        if (target.isEmpty() || outputDevices == null) {
            return false;
        }
        for (OutputDevice device : outputDevices) {
            if (device != null && device.mediaCapable && matchesAddress(target, device.address)) {
                return true;
            }
        }
        return false;
    }

    public static SamsungAudioSnapshot.RouteState resolveRouteState(
            String targetAddress,
            boolean permissionGranted,
            boolean mediaOutputAvailable,
            RouteEvidence evidence) {
        if (!permissionGranted || normalizeAddress(targetAddress).isEmpty()) {
            return SamsungAudioSnapshot.RouteState.UNKNOWN;
        }
        RouteEvidence safe = evidence == null ? RouteEvidence.none() : evidence;
        if (safe.communicationMatchesTarget || safe.mediaAttributesMatchesTarget) {
            return SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED;
        }
        boolean otherRouteObserved =
                (safe.mediaAttributesRouteAvailable && !safe.mediaAttributesMatchesTarget)
                || (safe.communicationRouteAvailable && !safe.communicationMatchesTarget);
        if (mediaOutputAvailable && otherRouteObserved) {
            return SamsungAudioSnapshot.RouteState.DEVICE_AVAILABLE;
        }
        return SamsungAudioSnapshot.RouteState.UNKNOWN;
    }

    public static String describeRoute(
            String targetAddress,
            boolean permissionGranted,
            boolean mediaOutputAvailable,
            RouteEvidence evidence,
            SamsungAudioSnapshot.RouteState routeState) {
        if (!permissionGranted) {
            return "等待附近设备权限";
        }
        if (normalizeAddress(targetAddress).isEmpty()) {
            return "未选择目标耳机";
        }
        RouteEvidence safe = evidence == null ? RouteEvidence.none() : evidence;
        if (routeState == SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED) {
            if (safe.communicationMatchesTarget) {
                return "已观察到通话设备路由指向此耳机（实际出声需试听确认）";
            }
            return "已观察到系统媒体路由指向此耳机（实际出声需试听确认）";
        }
        if (routeState == SamsungAudioSnapshot.RouteState.DEVICE_AVAILABLE) {
            return "设备可用，当前系统路由指向其他音频设备";
        }
        if (!safe.hasAnySupportedSource()) {
            return mediaOutputAvailable
                    ? "设备在可用输出列表中；当前 Android 版本在此模式下不提供系统媒体路由查询（不可用）"
                    : "未在可用输出列表中发现目标耳机；当前 Android 版本在此模式下不提供系统媒体路由查询（不可用）";
        }
        if (mediaOutputAvailable) {
            return "设备在可用输出列表中，但路由证据不足（未知）";
        }
        return "未观察到目标输出路由";
    }

    public static SamsungAudioSnapshot evaluate(
            String targetAddress,
            boolean permissionGranted,
            boolean a2dpProxyReady,
            int rawA2dpState,
            boolean headsetProxyReady,
            int rawHeadsetState,
            List<OutputDevice> outputDevices,
            String communicationDeviceLabel,
            String communicationDeviceAddress,
            int audioMode,
            RouteEvidence evidence,
            long elapsedMs) {
        String target = normalizeAddress(targetAddress);
        boolean hasTarget = !target.isEmpty();
        SamsungAudioSnapshot.ConnectionState a2dpState =
                normalizeConnectionState(rawA2dpState, permissionGranted, a2dpProxyReady, hasTarget);
        SamsungAudioSnapshot.ConnectionState headsetState =
                normalizeConnectionState(rawHeadsetState, permissionGranted, headsetProxyReady, hasTarget);
        List<OutputDevice> safeDevices = outputDevices == null ? Collections.<OutputDevice>emptyList() : outputDevices;
        boolean mediaOutputAvailable = permissionGranted && hasTarget && hasMediaOutput(target, safeDevices);
        boolean commMatches = permissionGranted && hasTarget && matchesAddress(target, communicationDeviceAddress);
        String commLabel = communicationDeviceLabel == null ? "" : communicationDeviceLabel.trim();
        RouteEvidence safeEvidence = evidence == null ? RouteEvidence.none() : evidence;
        boolean evidenceSupported = permissionGranted && hasTarget && safeEvidence.hasAnySupportedSource();
        SamsungAudioSnapshot.RouteState routeState =
                resolveRouteState(target, permissionGranted, mediaOutputAvailable, safeEvidence);
        String detail = describeRoute(target, permissionGranted, mediaOutputAvailable, safeEvidence, routeState);
        return new SamsungAudioSnapshot(
                target,
                permissionGranted,
                a2dpState,
                headsetState,
                mediaOutputAvailable,
                commLabel,
                commMatches,
                audioMode,
                evidenceSupported,
                routeState,
                detail,
                elapsedMs);
    }

    public static String connectionLabel(SamsungAudioSnapshot.ConnectionState state) {
        if (state == null) {
            return "未知";
        }
        switch (state) {
            case DISCONNECTED:
                return "未连接";
            case CONNECTING:
                return "正在连接";
            case CONNECTED:
                return "已连接";
            case DISCONNECTING:
                return "正在断开";
            case UNKNOWN:
            default:
                return "未知";
        }
    }

    public static String audioModeLabel(int mode) {
        switch (mode) {
            case AUDIO_MODE_NORMAL:
                return "正常";
            case AUDIO_MODE_RINGTONE:
                return "响铃";
            case AUDIO_MODE_IN_CALL:
                return "通话中";
            case AUDIO_MODE_IN_COMMUNICATION:
                return "语音通话中";
            case AUDIO_MODE_CALL_SCREENING:
                return "来电筛选";
            case AUDIO_MODE_CALL_REDIRECT:
                return "通话转接";
            case AUDIO_MODE_COMMUNICATION_REDIRECT:
                return "语音转接";
            default:
                return "未知";
        }
    }

    public static String routeLabel(SamsungAudioSnapshot.RouteState state) {
        if (state == null) {
            return "未知";
        }
        switch (state) {
            case DEVICE_AVAILABLE:
                return "仅设备可用";
            case ROUTE_OBSERVED:
                return "已观察到系统路由";
            case UNKNOWN:
            default:
                return "未知";
        }
    }

    public static String formatA2dpSummary(SamsungAudioSnapshot snapshot) {
        if (snapshot == null || !snapshot.permissionGranted) {
            return "等待附近设备权限";
        }
        if (snapshot.targetAddress.isEmpty()) {
            return "请在 LinkPods 设置中选择耳机";
        }
        return connectionLabel(snapshot.a2dpState);
    }

    public static String formatHeadsetSummary(SamsungAudioSnapshot snapshot) {
        if (snapshot == null || !snapshot.permissionGranted) {
            return "等待附近设备权限";
        }
        if (snapshot.targetAddress.isEmpty()) {
            return "未选择耳机";
        }
        String base = connectionLabel(snapshot.headsetState) + " · 模式：" + audioModeLabel(snapshot.audioMode);
        if (!snapshot.communicationDevice.isEmpty()) {
            base += " · 通话设备：" + snapshot.communicationDevice;
        }
        return base;
    }

    public static String formatRouteSummary(SamsungAudioSnapshot snapshot) {
        if (snapshot == null || !snapshot.permissionGranted) {
            return "等待附近设备权限";
        }
        if (snapshot.targetAddress.isEmpty()) {
            return "未选择耳机";
        }
        String available = snapshot.mediaOutputAvailable ? "可用" : "不可用";
        if (snapshot.routeState == SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED) {
            return "输出设备：" + available + " · 路由：" + routeLabel(snapshot.routeState) + "（需试听确认）";
        }
        if (snapshot.routeState == SamsungAudioSnapshot.RouteState.DEVICE_AVAILABLE) {
            return "输出设备：" + available + " · 路由：" + routeLabel(snapshot.routeState);
        }
        if (!snapshot.routeEvidenceSupported) {
            return "输出设备：" + available + " · 路由：接口不可用（需试听确认）";
        }
        return "输出设备：" + available + " · 路由：" + routeLabel(snapshot.routeState);
    }

    /** Returns true only when system routing explicitly points to the target AirPods. */
    public static boolean isTargetOutputConfirmed(SamsungAudioSnapshot snapshot) {
        return snapshot != null
                && snapshot.permissionGranted
                && !snapshot.targetAddress.isEmpty()
                && snapshot.routeState == SamsungAudioSnapshot.RouteState.ROUTE_OBSERVED;
    }
}
