package local.airpods.handoffprobe;

public final class SessionModeTest {
    static void expect(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        expect(!SessionMode.CONTROL_ONLY.allowsRouting(), "CONTROL_ONLY must disallow routing");
        expect(SessionMode.AUTO_HANDOFF.allowsRouting(), "AUTO_HANDOFF must allow routing");

        expect(SessionMode.valueOf("CONTROL_ONLY") == SessionMode.CONTROL_ONLY, "CONTROL_ONLY name lookup");
        expect(SessionMode.valueOf("AUTO_HANDOFF") == SessionMode.AUTO_HANDOFF, "AUTO_HANDOFF name lookup");
        expect(SessionMode.values().length == 2, "exactly two session modes exist");

        // Verify mode invariants
        SessionMode mode = SessionMode.CONTROL_ONLY;
        expect(!mode.allowsRouting(), "initial CONTROL_ONLY mode blocks routing");

        // Transition to AUTO_HANDOFF
        mode = SessionMode.AUTO_HANDOFF;
        expect(mode.allowsRouting(), "upgraded AUTO_HANDOFF mode permits routing");

        // Downgrade back to CONTROL_ONLY
        mode = SessionMode.CONTROL_ONLY;
        expect(!mode.allowsRouting(), "downgraded CONTROL_ONLY mode blocks routing");

        // Bridge preparation preserves running session mode or falls back to CONTROL_ONLY
        expect(SessionMode.forBridgePreparation(null) == SessionMode.CONTROL_ONLY, "null status defaults to CONTROL_ONLY");
        expect(SessionMode.forBridgePreparation(SessionStatus.idle("AA:BB:CC:DD:EE:FF")) == SessionMode.CONTROL_ONLY, "idle status defaults to CONTROL_ONLY");
        expect(SessionMode.forBridgePreparation(new SessionStatus("AA:BB:CC:DD:EE:FF", SessionMode.AUTO_HANDOFF, true, true, "ready", "running")) == SessionMode.AUTO_HANDOFF, "running AUTO_HANDOFF status preserved for bridge preparation");
        expect(SessionMode.forBridgePreparation(new SessionStatus("AA:BB:CC:DD:EE:FF", SessionMode.CONTROL_ONLY, true, true, "ready", "off")) == SessionMode.CONTROL_ONLY, "running CONTROL_ONLY status preserved for bridge preparation");
        expect(SessionMode.forBridgePreparation(new SessionStatus("AA:BB:CC:DD:EE:FF", SessionMode.AUTO_HANDOFF, false, false, "idle", "off")) == SessionMode.CONTROL_ONLY, "stopped service status defaults to CONTROL_ONLY");

        System.out.println("PASS: session mode permissions and routing isolation invariants");
    }
}
