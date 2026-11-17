package local.airpods.handoffprobe;

enum SessionMode {
    CONTROL_ONLY,
    AUTO_HANDOFF;

    boolean allowsRouting() {
        return this == AUTO_HANDOFF;
    }

    static SessionMode forBridgePreparation(SessionStatus status) {
        if (status != null && status.serviceRunning && status.mode != null) {
            return status.mode;
        }
        return CONTROL_ONLY;
    }
}
