package local.airpods.handoffprobe;

import java.util.Arrays;

public final class RoutingTest {
    static int at;
    static Object opack(byte[] data) {
        int tag = data[at++] & 255;
        if (tag >= 0x40 && tag <= 0x60) {
            int size = tag - 0x40;
            String s = new String(data, at, size, java.nio.charset.StandardCharsets.UTF_8);
            at += size; return s;
        }
        if (tag == 0x31) { int v = (data[at]&255) | ((data[at+1]&255)<<8); at += 2; return v; }
        if (tag >= 0xe0 && tag <= 0xee) {
            java.util.Map<Object,Object> map = new java.util.LinkedHashMap<>();
            for (int i=0; i<tag-0xe0; i++) map.put(opack(data),opack(data));
            return map;
        }
        throw new AssertionError("invalid OPACK tag at " + (at-1) + ": " + tag);
    }
    static void expect(boolean result, String label) {
        if (!result) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        RoutingPolicy p = new RoutingPolicy();
        p.seed("music", 2);
        expect(p.onPlayback("music", 3) == RoutingPolicy.Action.TAKE, "player play starts takeover");
        expect(p.onPlayback("music", 3) == RoutingPolicy.Action.NONE, "same player state must not repeat");
        expect(p.onPlayback("music", 6) == RoutingPolicy.Action.NONE, "live buffering must not take over");
        expect(p.onPlayback("music", 3) == RoutingPolicy.Action.NONE, "buffering recovery must not retrigger");
        expect(p.onPlayback("music", 0) == RoutingPolicy.Action.NONE, "temporary missing playback state is passive");
        expect(p.onPlayback("music", 3) == RoutingPolicy.Action.NONE, "NONE recovery must not send a second takeover");
        expect(p.onRemoteRequest() == RoutingPolicy.Action.RELEASE, "remote request releases");
        expect(p.onPlayback("music", 3) == RoutingPolicy.Action.NONE, "ongoing stream must not fight remote owner");
        expect(p.onPlayback("music", 2) == RoutingPolicy.Action.NONE, "pause does not take over");
        expect(p.onPlayback("music", 3) == RoutingPolicy.Action.TAKE, "explicit replay can immediately reclaim after remote handoff");
        p.seed("other", 3);
        expect(p.onPlayback("other", 3) == RoutingPolicy.Action.NONE, "initial enumeration is passive");
        RoutingPolicy queued = new RoutingPolicy();
        queued.onPlayback("music",3);
        expect(!queued.consumeTake(false,false), "disconnected play waits for channel");
        queued.onPlayback("music",2);
        expect(!queued.consumeTake(true,true), "pause cancels queued play before channel readiness");
        queued.onPlayback("music",3);
        expect(!queued.consumeTake(true,false), "takeover waits for peer discovery so hijack is not omitted");
        expect(queued.consumeTake(true,true), "fresh peer completes pending takeover");
        expect(!queued.consumeTake(true,true), "pending takeover completes only once");
        queued.onPlayback("music",2); queued.onPlayback("music",3); queued.remove("music");
        expect(!queued.consumeTake(true,true), "removed session cancels stale takeover");
        queued.seed("yielded", 2);
        queued.onPlayback("new", 3);
        expect(queued.onPlayback("yielded", 3, false) == RoutingPolicy.Action.PAUSE,
            "yield bounce updates playback and only requests a pause");
        expect(queued.isPending(), "yield bounce preserves another player's pending take");
        queued.onPlayback("new", 2);
        expect(!queued.consumeTake(true,true), "continuing bounce cannot inherit a stopped player's take");
        RoutingPolicy gated = new RoutingPolicy();
        gated.onPlayback("music", 3);
        expect(gated.evaluateTake(false, true, false) == RoutingPolicy.TakeDecision.WAIT, "unready channel waits without dropping pending take");
        expect(gated.evaluateTake(true, true, false) == RoutingPolicy.TakeDecision.SKIP, "unconfirmed target output skips takeover");
        expect(gated.evaluateTake(true, true, true) == RoutingPolicy.TakeDecision.WAIT, "skipped playback does not trigger a delayed takeover later");
        gated.onPlayback("music", 2);
        gated.onPlayback("music", 3);
        expect(gated.evaluateTake(true, true, true) == RoutingPolicy.TakeDecision.TAKE, "confirmed target output on new play triggers takeover");
        RoutingPolicy resetPolicy = new RoutingPolicy();
        resetPolicy.onPlayback("music", 3);
        expect(resetPolicy.hasPlayback(), "playback active before reset");
        resetPolicy.reset();
        expect(!resetPolicy.hasPlayback(), "playback cleared after reset");
        expect(!resetPolicy.consumeTake(true, true), "pending take cleared after reset");
        expect(resetPolicy.onPlayback("music", 3) == RoutingPolicy.Action.TAKE, "fresh playback edge can be detected after reset");
        expect(resetPolicy.isPending(), "pending take reported while playback active");
        RoutingPolicy reseed = new RoutingPolicy();
        reseed.seed("music", 3);
        expect(!reseed.isPending(), "passive seed alone does not create pending take");
        reseed.onPlayback("music", 2);
        reseed.onPlayback("music", 3);
        reseed.reseed(java.util.Map.of("music", 3));
        expect(reseed.isPending() && reseed.consumeTake(true, true), "pending take restored when active playback remains");
        RoutingPolicy stoppedReseed = new RoutingPolicy();
        stoppedReseed.onPlayback("music", 3);
        stoppedReseed.reseed(java.util.Map.of("music", 2, "unrelated", 3));
        expect(!stoppedReseed.isPending(), "stopped playback does not restore stale pending take");
        expect(!p.needsClaim(true,true), "already-owned local media must not ask the phone to yield again");
        expect(p.needsClaim(false,true), "local source alone does not establish ownership");
        expect(p.needsClaim(true,false), "ownership without a local source still permits recovery");
        expect(Arrays.equals(RoutingPackets.ownership(false), new byte[]{4,0,4,0,9,0,6,0,0,0,0}), "release wire packet");
        expect(Arrays.equals(RoutingPackets.ownership(true), new byte[]{4,0,4,0,9,0,6,1,0,0,0}), "takeover wire packet");
        byte[] loss = new byte[]{4,0,4,0,9,0,6,0,0,0,0};
        expect(Boolean.FALSE.equals(RoutingPackets.ownershipEvent(loss, loss.length)), "ownership loss decoded");
        expect(RoutingPackets.ownershipEvent(loss, 7) == null, "truncated ownership ignored");
        expect(!RoutingPackets.releaseRequest(new byte[]{4,0,4,0,0x17,0}, 6), "headtracking must not release");
        expect(RoutingPackets.hijack("11:22:33:44:55:66").length == 112, "hijack has complete wire payload");
        expect(RoutingPackets.announce("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66").length == 96, "announcement has complete wire payload");
        byte[] media = RoutingPackets.mediaInformation("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66", true);
        expect(media[6] == 0x66 && media[11] == 0x11, "media declaration targets the remote address");
        for (boolean active : new boolean[]{true,false}) {
            byte[] wire = RoutingPackets.mediaInformation("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66", active);
            at=15;
            java.util.Map<?,?> decoded = (java.util.Map<?,?>)opack(wire);
            expect((active ? "YES" : "NO").equals(decoded.get("hostStreamingState")), "stream state uses the key actually observed from iPhone");
            expect(decoded.containsKey("playingApp"), "playing app uses the observed case-sensitive key");
            expect("Android".equals(decoded.get("btName")), "device name has a valid encoded key");
            expect("AA:BB:CC:DD:EE:FF".equals(decoded.get("btAddress")), "source MAC survives decoding");
            expect(at == wire.length && (((wire[12]&255)|((wire[13]&255)<<8)) == wire.length-14), "payload length excludes unused padding");
        }
        byte[] source = new byte[]{4,0,4,0,14,0,0x66,0x55,0x44,0x33,0x22,0x11,2};
        expect("11:22:33:44:55:66".equals(RoutingPackets.audioSourceMac(source)), "audio source address decoded");
        System.out.println("PASS: player play edges, immediate reclaim, live buffering, passive enumeration, ownership wire packets, malformed events");
    }
}
