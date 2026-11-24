package local.airpods.handoffprobe;

/** Immutable snapshot of current service session and handoff state for UI presentation. */
public final class SessionStatus {
    public final String targetAddress;
    public final SessionMode mode;
    public final boolean serviceRunning;
    public final boolean channelReady;
    public final String controlDescription;
    public final String handoffDescription;
    public final String handoffNotice;
    public final boolean audioYielded;

    public SessionStatus(String targetAddress, SessionMode mode, boolean serviceRunning, boolean channelReady, String controlDescription, String handoffDescription) {
        this(targetAddress, mode, serviceRunning, channelReady, controlDescription, handoffDescription, "", false);
    }

    public SessionStatus(String targetAddress, SessionMode mode, boolean serviceRunning, boolean channelReady, String controlDescription, String handoffDescription, String handoffNotice) {
        this(targetAddress, mode, serviceRunning, channelReady, controlDescription, handoffDescription, handoffNotice, false);
    }

    public SessionStatus(String targetAddress, SessionMode mode, boolean serviceRunning, boolean channelReady, String controlDescription, String handoffDescription, String handoffNotice, boolean audioYielded) {
        this.targetAddress = targetAddress == null ? "" : targetAddress;
        this.mode = mode;
        this.serviceRunning = serviceRunning;
        this.channelReady = channelReady;
        this.controlDescription = controlDescription == null ? "" : controlDescription;
        this.handoffDescription = handoffDescription == null ? "" : handoffDescription;
        this.handoffNotice = handoffNotice == null ? "" : handoffNotice;
        this.audioYielded = audioYielded;
    }

    public static SessionStatus idle(String targetAddress) {
        return new SessionStatus(targetAddress, null, false, false, "控制服务未运行", "关闭", "", false);
    }
}
