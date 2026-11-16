package local.airpods.handoffprobe;
public final class BridgeRuntimeLeaseTest {
    public static void main(String[] args) {
        BridgeRuntimeLease lease=new BridgeRuntimeLease("AA:BB:CC:DD:EE:01","a",42,"session-one");
        if(!lease.matches("AA:BB:CC:DD:EE:01","a",42,"session-one"))throw new AssertionError("owned instance");
        if(lease.matches("AA:BB:CC:DD:EE:01","a",42,"replacement"))throw new AssertionError("never stop a replacement on the same Bluetooth PID");
        if(lease.matches("AA:BB:CC:DD:EE:02","a",42,"session-one"))throw new AssertionError("target isolation");
        if(lease.matches("AA:BB:CC:DD:EE:01","b",42,"session-one"))throw new AssertionError("profile isolation");
        if(lease.matches("AA:BB:CC:DD:EE:01","a",43,"session-one"))throw new AssertionError("Bluetooth restart isolation");
        try {new BridgeRuntimeLease("x","y",42,"");throw new AssertionError("no ownership from an unknown instance");}
        catch(IllegalArgumentException expected){}
        System.out.println("PASS: bridge cleanup cannot target another runtime instance");
    }
}
