package local.airpods.handoffprobe;

/** Immutable snapshot of system Bluetooth audio profiles and observed routing for a target device. */
public final class SamsungAudioSnapshot {
    public enum ConnectionState {
        UNKNOWN,
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    public enum RouteState {
        UNKNOWN,
        DEVICE_AVAILABLE,
        ROUTE_OBSERVED
    }

    public final String targetAddress;
    public final boolean permissionGranted;
    public final ConnectionState a2dpState;
    public final ConnectionState headsetState;
    public final boolean mediaOutputAvailable;
    public final String communicationDevice;
    public final boolean communicationDeviceMatchesTarget;
    public final int audioMode;
    public final boolean routeEvidenceSupported;
    public final RouteState routeState;
    public final String routeDetail;
    public final long lastUpdateElapsedMs;

    public SamsungAudioSnapshot(
            String targetAddress,
            boolean permissionGranted,
            ConnectionState a2dpState,
            ConnectionState headsetState,
            boolean mediaOutputAvailable,
            String communicationDevice,
            boolean communicationDeviceMatchesTarget,
            int audioMode,
            boolean routeEvidenceSupported,
            RouteState routeState,
            String routeDetail,
            long lastUpdateElapsedMs) {
        this.targetAddress = targetAddress == null ? "" : targetAddress;
        this.permissionGranted = permissionGranted;
        this.a2dpState = a2dpState == null ? ConnectionState.UNKNOWN : a2dpState;
        this.headsetState = headsetState == null ? ConnectionState.UNKNOWN : headsetState;
        this.mediaOutputAvailable = mediaOutputAvailable;
        this.communicationDevice = communicationDevice == null ? "" : communicationDevice;
        this.communicationDeviceMatchesTarget = communicationDeviceMatchesTarget;
        this.audioMode = audioMode;
        this.routeEvidenceSupported = routeEvidenceSupported;
        this.routeState = routeState == null ? RouteState.UNKNOWN : routeState;
        this.routeDetail = routeDetail == null ? "" : routeDetail;
        this.lastUpdateElapsedMs = lastUpdateElapsedMs;
    }

    public static SamsungAudioSnapshot unavailable(String targetAddress) {
        return empty(targetAddress, false, 0L);
    }

    public static SamsungAudioSnapshot empty(String targetAddress, boolean permissionGranted, long elapsedMs) {
        String normalized = SamsungAudioPolicy.normalizeAddress(targetAddress);
        String detail = !permissionGranted
                ? "等待附近设备权限"
                : normalized.isEmpty() ? "未选择目标耳机" : "系统音频状态尚未读取";
        return new SamsungAudioSnapshot(
                normalized,
                permissionGranted,
                ConnectionState.UNKNOWN,
                ConnectionState.UNKNOWN,
                false,
                "",
                false,
                SamsungAudioPolicy.AUDIO_MODE_INVALID,
                false,
                RouteState.UNKNOWN,
                detail,
                elapsedMs);
    }
}
