package local.airpods.handoffprobe;

public final class RequestGateTest {
    static void expect(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) {
        RequestGate gate=new RequestGate();
        long request=gate.begin(); gate.cancel();
        expect(!gate.complete(request), "stop while root is pending prevents later start");
        long next=gate.begin();
        expect(!gate.complete(request), "old completion cannot consume new request");
        expect(gate.pending(), "new check stays pending");
        expect(gate.complete(next), "new completion accepted");
        expect(!gate.complete(next), "completion accepted only once");
        expect(!gate.pending(), "idle after completion");
        System.out.println("PASS: stop invalidates root checks and stale completions cannot restart service");
    }
}
