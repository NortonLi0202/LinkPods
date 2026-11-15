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
        System.out.println("PASS: UI disables unknown/pending/disconnected writes and clamps mode drags");
    }
}
