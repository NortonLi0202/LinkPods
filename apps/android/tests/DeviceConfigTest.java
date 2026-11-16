package local.airpods.handoffprobe;

import java.util.*;

public final class DeviceConfigTest {
    static void expect(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        DeviceConfig config = DeviceConfig.create("aa:bb:cc:dd:ee:01", "AA:BB:CC:DD:EE:02", null);
        expect(config.target.equals("AA:BB:CC:DD:EE:01"), "normalize target");
        expect(config.peer.isEmpty(), "no personal peer default");
        expect(DeviceConfig.restore(config.save()).target.equals(config.target), "roundtrip saved selection");
        expect(DeviceConfig.restore(Collections.emptyMap()) == null, "no saved selection means no default");
        Map<String,String> bad = new HashMap<>(); bad.put("target", "invalid");
        expect(DeviceConfig.restore(bad) == null, "corrupt saved selection ignored");
        for (String address : new String[]{"", "invalid", "00:00:00:00:00:00", "02:00:00:00:00:00", "AA:BB:CC:DD:EE:01;id"}) {
            try { DeviceConfig.create(address, null, null); throw new AssertionError("accepted bad target"); }
            catch (IllegalArgumentException expected) {}
        }
        expect(DeviceConfig.choosePeer(Arrays.asList(config.local, config.target, "AA:BB:CC:DD:EE:03"), config).equals("AA:BB:CC:DD:EE:03"), "learn unique remote");
        expect(DeviceConfig.choosePeer(Arrays.asList("AA:BB:CC:DD:EE:03", "AA:BB:CC:DD:EE:04"), config).isEmpty(), "ambiguous peer stays unselected");
        expect(DeviceConfig.choosePeer(Arrays.asList(config.local, config.target), config).isEmpty(), "exclude local and earbuds");
        DeviceConfig noLocal=DeviceConfig.create(config.target,null,null);
        expect(DeviceConfig.choosePeer(Arrays.asList("AA:BB:CC:DD:EE:03"), noLocal).isEmpty(), "unknown local address cannot identify unique remote");
        expect(DeviceConfig.choosePeer(Arrays.asList("AA:BB:CC:DD:EE:03"), config, false).isEmpty(), "partial list cannot select peer");
        DeviceConfig chosen = DeviceConfig.create(config.target, config.local, "AA:BB:CC:DD:EE:04");
        expect(DeviceConfig.choosePeer(Arrays.asList("AA:BB:CC:DD:EE:03"), chosen).equals(chosen.peer), "explicit peer never replaced");
        try { DeviceConfig.create(config.target, config.local, config.target); throw new AssertionError("target used as peer"); }
        catch (IllegalArgumentException expected) {}
        DeviceConfig direct=DeviceConfig.create(config.target,"",config.peer);
        DeviceConfig enriched=DeviceConfig.mergeLocal(direct,config);
        expect(enriched.local.equals(config.local),"bridge preparation can enrich direct local address");
        expect(DeviceConfig.mergeLocal(enriched,direct).local.equals(config.local),"empty duplicate preserves observed local");
        expect(DeviceConfig.restore(enriched.save()).local.equals(config.local),"learned local survives preference roundtrip");
        for(DeviceConfig conflict:Arrays.asList(DeviceConfig.create(config.target,"AA:BB:CC:DD:EE:09",config.peer),
                DeviceConfig.create("AA:BB:CC:DD:EE:09",config.local,config.peer),
                DeviceConfig.create(config.target,config.local,"AA:BB:CC:DD:EE:09"))) {
            try {DeviceConfig.mergeLocal(enriched,conflict);throw new AssertionError("accepted conflicting live config");}
            catch(IllegalArgumentException expected) {}
        }
        System.out.println("PASS: explicit device selection, persistence validation and unambiguous remote peer");
    }
}
