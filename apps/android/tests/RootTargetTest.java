package local.airpods.handoffprobe;
import java.util.*;
public final class RootTargetTest {
    public static void main(String[] args) {
        Map<String,String> expected=new HashMap<>();
        expected.put("model","test-model");expected.put("android_sdk","36");expected.put("firmware","test-build");
        expected.put("kernel","test-kernel");expected.put("bluetooth_sha256","a".repeat(64));
        if(!RootTarget.matches(expected,expected))throw new AssertionError("exact target rejected");
        for(String key:expected.keySet()) {
            Map<String,String> different=new HashMap<>(expected); different.put(key,"other");
            if(RootTarget.matches(different,expected))throw new AssertionError("mismatched "+key);
            different.remove(key);
            if(RootTarget.matches(different,expected))throw new AssertionError("unknown "+key);
        }
        if(RootTarget.matches(Collections.emptyMap(),Collections.emptyMap()))throw new AssertionError("empty target matched");
        System.out.println("PASS: temporary Root adapter requires full exact system identity");
    }
}
