package local.airpods.handoffprobe;

enum SessionMode {
    CONTROL_ONLY,
    AUTO_HANDOFF;

    boolean allowsRouting() {
        return this == AUTO_HANDOFF;
    }
}
