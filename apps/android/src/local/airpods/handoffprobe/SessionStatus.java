package local.airpods.handoffprobe;

/** Immutable snapshot of current service session and handoff state for UI presentation. */
public final class SessionStatus {
    public final String targetAddress;
    public final SessionMode mode;
    public final boolean serviceRunning;
    public final boolean channelReady;
    public final String controlDescription;
    public final String handoffDescription;

    public SessionStatus(String targetAddress, SessionMode mode, boolean serviceRunning, boolean channelReady, String controlDescription, String handoffDescription) {
        this.targetAddress = targetAddress == null ? "" : targetAddress;
        this.mode = mode;
        this.serviceRunning = serviceRunning;
        this.channelReady = channelReady;
        this.controlDescription = controlDescription == null ? "" : controlDescription;
        this.handoffDescription = handoffDescription == null ? "" : handoffDescription;
    }

    public static SessionStatus idle(String targetAddress) {
        return new SessionStatus(targetAddress, null, false, false, "控制服务未运行", "关闭");
    }
}
