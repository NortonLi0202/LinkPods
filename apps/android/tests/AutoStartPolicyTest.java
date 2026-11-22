package local.airpods.handoffprobe;

public final class AutoStartPolicyTest {
    private static final String TARGET = "AA:BB:CC:DD:EE:FF";
    private static final String OTHER = "11:22:33:44:55:66";

    private static void expect(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) {
        AutoStartPolicy policy = new AutoStartPolicy();

        expect(policy.onTargetConnected(OTHER, TARGET, true, true, false) == AutoStartPolicy.ConnectDecision.IGNORE,
                "Non-target device does not auto-start");
        expect(policy.onTargetConnected(TARGET, "", true, true, false) == AutoStartPolicy.ConnectDecision.IGNORE,
                "Empty remembered target is ignored");
        expect(policy.onTargetConnected(TARGET, TARGET, false, true, false) == AutoStartPolicy.ConnectDecision.IGNORE,
                "Without explicit opt-in, remembered target does not auto-start AUTO_HANDOFF");
        expect(policy.onTargetConnected(TARGET, TARGET, true, false, false) == AutoStartPolicy.ConnectDecision.IGNORE,
                "Missing Bluetooth permission is ignored by receiver");
        expect(policy.onTargetConnected(TARGET, TARGET, true, true, false) == AutoStartPolicy.ConnectDecision.START_AUTO_HANDOFF,
                "Matching opted-in target auto-starts AUTO_HANDOFF");
        expect(policy.isAutoStarted(), "Auto-started flag set");

        policy.onUserStoppedOrDowngraded();
        expect(policy.onTargetConnected(TARGET, TARGET, true, true, false) == AutoStartPolicy.ConnectDecision.IGNORE,
                "Manual stop or CONTROL_ONLY suppresses re-auto-start during same connection");
        expect(!policy.shouldAutoStopOnDisconnect(true), "Does not stop while another profile remains connected");

        expect(!policy.shouldAutoStopOnDisconnect(false),
                "Manually stopped session does not trigger auto-stop, but clears suppression");
        expect(!policy.isUserSuppressed(), "Disconnect clears user suppression");

        expect(policy.onTargetConnected(TARGET.toLowerCase(), TARGET, true, true, false) == AutoStartPolicy.ConnectDecision.START_AUTO_HANDOFF,
                "Reconnect after full disconnect auto-starts again");
        expect(policy.shouldAutoStopOnDisconnect(false), "Full disconnect of auto-started session triggers auto-stop");

        System.out.println("PASS: AutoStartPolicy opt-in, target matching, user suppression, and disconnect reset");
    }
}
