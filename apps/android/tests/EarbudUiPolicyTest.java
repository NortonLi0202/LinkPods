package local.airpods.handoffprobe;

/** Host checks for the UI's unknown/pending safety boundary. */
public final class EarbudUiPolicyTest {
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        expect(!EarbudUiPolicy.canWrite(false, 2, false), "disconnected observations cannot enable writes");
        expect(!EarbudUiPolicy.canWrite(true, null, false), "unknown settings cannot enable writes");
        expect(!EarbudUiPolicy.canWrite(true, 2, true), "pending commands cannot be resubmitted");
        expect(EarbudUiPolicy.canWrite(true, 2, false), "live observed setting is writable");
        expect(EarbudUiPolicy.nearestSegment(-100, 300, 3) == 0, "left drag clamps to first mode");
        expect(EarbudUiPolicy.nearestSegment(149, 300, 3) == 1, "middle drag chooses middle mode");
        expect(EarbudUiPolicy.nearestSegment(400, 300, 3) == 2, "right drag clamps to last mode");
        expect(EarbudUiPolicy.nearestSegment(0, 0, 3) == 0, "unlaid-out control is safe");
        int[] modes = {EarbudCommands.MODE_TRANSPARENCY, EarbudCommands.MODE_ANC};
        String[] names = {"通透", "降噪"};
        expect("尚未读取".equals(EarbudUiPolicy.listeningModeLabel(null, modes, names)),
            "unknown observation is not rendered as a selectable mode");
        expect("通透".equals(EarbudUiPolicy.listeningModeLabel(EarbudCommands.MODE_TRANSPARENCY, modes, names)),
            "observed selectable mode keeps its label");
        expect("未知或不支持的模式".equals(EarbudUiPolicy.listeningModeLabel(EarbudCommands.MODE_ADAPTIVE, modes, names)),
            "mode outside the capability list is reported as unsupported, not as the first option");
        System.out.println("PASS: UI disables unknown/pending/disconnected writes and clamps mode drags");
    }
}
