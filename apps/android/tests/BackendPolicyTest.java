package local.airpods.handoffprobe;

public final class BackendPolicyTest {
    static void expect(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    public static void main(String[] args) {
        expect(BackendPolicy.select(true,false,false,false)==BackendPolicy.Path.CONTROL_CHANNEL, "observed channel usable independently of root");
        expect(BackendPolicy.select(false,true,true,true)==BackendPolicy.Path.NATIVE_BRIDGE, "validated matching bridge");
        expect(BackendPolicy.select(false,true,false,true)==BackendPolicy.Path.UNAVAILABLE, "unknown binary never selects bridge");
        expect(BackendPolicy.select(false,true,true,false)==BackendPolicy.Path.UNAVAILABLE, "profile alone is not runtime validation");
        expect(BackendPolicy.select(false,false,true,true)==BackendPolicy.Path.UNAVAILABLE, "root required for native path");
        expect(!BackendPolicy.handoffVerified(), "backend choice is not audible handoff acceptance");
        System.out.println("PASS: observed control channel, validated native bridge and unsupported path");
    }
}
