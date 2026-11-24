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
        String target = "AA:BB:CC:DD:EE:03";
        for (boolean manual : new boolean[]{false, true}) {
            String hijackReason = manual ? "ManualRoute" : "Hijackv2";
            String nearbyReason = manual ? "ManualRoute" : "hijackv2";
            expectRequest(RoutingPackets.hijack(target, hijackReason), target, hijackReason, false);
            expectRequest(RoutingPackets.showNearbyUi(target, nearbyReason), target, nearbyReason, true);
        }
        PeerRoutingPolicy resetPeer = new PeerRoutingPolicy();
        resetPeer.receive(report("example.media.player", false, 301), true);
        resetPeer.reset();
        expect(!resetPeer.receive(report("example.media.player", true, 301), true), "reset clears armed rising-edge, play without armed edge does not yield");
        PeerRoutingPolicy dualLive = new PeerRoutingPolicy();
        expect(!dualLive.receive(report("live.stream", true, 501), true), "initial ongoing high-category peer stream must not pull audio back");
        expect(!dualLive.receive(report("live.stream", true, 501), true), "continued high-category peer stream must not pull audio back");
        dualLive.receive(report("live.stream", false, 301), true);
        expect(dualLive.receive(report("live.stream", true, 501), true), "new high-category replay after peer pause is honored");
        PeerRoutingPolicy chatter = new PeerRoutingPolicy();
        chatter.receive(report("live.stream", true, 301), false);
        expect(!chatter.receive(report("Unknown", false, 100), true), "route-only NO is ignored");
        expect(!chatter.receive(report("live.stream", true, 301), true), "route-only NO must not clear peer playback and fake a rising edge");
        chatter.receive(report("tv.danmaku.bilianime", false, 301), true);
        expect(!chatter.receive(report("Unknown", true, 100), true), "route-only YES cannot consume an armed edge");
        expect(chatter.receive(report("tv.danmaku.bilianime", true, 301), true), "Bilibili play after a real pause yields");
        chatter.reset();
        chatter.receive(report("Unknown", false, 100), true);
        expect(!chatter.receive(report("tv.danmaku.bilianime", true, 301), true),
            "route-only NO cannot arm playback before the first stream report");
        Map<String,Object> malformed = report("tv.danmaku.bilianime", true, 301);
        malformed.put("hostStreamingState", "UNKNOWN");
        chatter.receive(malformed, true);
        expect(!chatter.receive(report("tv.danmaku.bilianime", true, 301), true),
            "an unrecognized streaming state cannot manufacture a stop");
        System.out.println("PASS: stale retry versus real phone replay/call/manual routing and sanitized OPACK decoding");
    }
    static void expectRequest(byte[] wire, String target, String reason, boolean nearby) {
        expect(RoutingPackets.header(wire, wire.length, 0x10)
            && wire[14] == 1 && (wire[15] & 255) == (nearby ? 0xe6 : 0xe5), "routing request envelope");
        Map<String,Object> expected = new HashMap<>(Map.of(
            "localscore", 301L, "reason", reason, "audioRoutingScore", 301L,
            "audioRoutingSetOwnershipToFalse", true, "remotescore", 301L));
        if (nearby) expected.put("SmartRoutingKeyShowNearbyUI", true);
        SmartRouting.Message message = SmartRouting.decode(wire);
        expect(message != null && target.equals(message.sender) && expected.equals(message.fields),
            "complete routing request for " + reason + " nearby=" + nearby);
    }
    static byte[] hex(String s) { byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b; }
}
