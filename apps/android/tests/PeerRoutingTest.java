package local.airpods.handoffprobe;
import java.util.*;

public final class PeerRoutingTest {
    static void expect(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    static Map<String,Object> report(String app, boolean playing, int category) {
        Map<String,Object> m = new HashMap<>();
        m.put("playingApp",app); m.put("hostStreamingState",playing ? "YES" : "NO");
        m.put("otherDeviceAudioCategory",category); return m;
    }
    static Map<String,Object> hijack(String reason) {
        Map<String,Object> m = new HashMap<>(); m.put("reason",reason);
        m.put("audioRoutingSetOwnershipToFalse",true); return m;
    }
    public static void main(String[] args) {
        PeerRoutingPolicy p = new PeerRoutingPolicy();
        expect(!p.receive(report("example.media.player",true,301),false),"initial peer enumeration is passive");
        expect(!p.receive(report("Unknown",true,100),true),"empty route YES is not new media intent");
        expect(!p.receive(report("Unknown",false,100),true),"handoff route NO must not arm a replay");
        expect(!p.receive(hijack("Hijackv2"),true),"automatic stale retry must not force an acknowledgement");
        expect(!p.receive(report("example.media.player",false,301),true),"real app pause only arms the next play");
        expect(p.receive(report("example.media.player",true,301),true),"explicit phone replay gives it the audio");
        expect(!p.receive(report("example.media.player",true,301),true),"same still-playing report cannot fight new tablet intent");
        expect(p.receive(report("com.apple.TelephonyUtilities",true,501),true),"phone call can always preempt media");
        expect(p.receive(hijack("ReverseBannerTapped"),true),"explicit manual reverse is honored");
        byte[] fixture = hex("04000400110003eeddccbbaa7e0001e65b536d617274526f7574696e674b657953686f774e65617262795549014a6c6f63616c73636f7265312d0146726561736f6e4848696a61636b763251617564696f526f7574696e6753636f7265a25f617564696f526f7574696e675365744f776e657273686970546f46616c7365014b72656d6f746573636f7265a2");
        SmartRouting.Message decoded = SmartRouting.decode(fixture);
        expect(decoded != null && "AA:BB:CC:DD:EE:03".equals(decoded.sender),"synthetic sender MAC decoded");
        expect(Long.valueOf(301).equals(decoded.fields.get("audioRoutingScore")),"OPACK back-reference resolves to actual score");
        expect(Boolean.TRUE.equals(decoded.fields.get("audioRoutingSetOwnershipToFalse")),"actual release Boolean decoded");
        expect(SmartRouting.decode(Arrays.copyOf(fixture,fixture.length-1)) == null,"truncated relay is rejected");
        byte[] manual = RoutingPackets.hijack("AA:BB:CC:DD:EE:03","ManualRoute");
        manual[4]=0x11; // Same OPACK envelope delivered to the other host as a relay.
        SmartRouting.Message manualMessage=SmartRouting.decode(manual);
        expect(manualMessage != null && "ManualRoute".equals(manualMessage.fields.get("reason")),"manual route request is complete OPACK with correct frame length");
        expect(Long.valueOf(301).equals(manualMessage.fields.get("remotescore")),"changing reason preserves object-reference indices");
        PeerRoutingPolicy resetPeer = new PeerRoutingPolicy();
        resetPeer.receive(report("example.media.player", false, 301), true);
        resetPeer.reset();
        expect(!resetPeer.receive(report("example.media.player", true, 301), true), "reset clears armed rising-edge, play without armed edge does not yield");
        System.out.println("PASS: stale retry versus real phone replay/call/manual routing and sanitized OPACK decoding");
    }
    static byte[] hex(String s) { byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b; }
}
