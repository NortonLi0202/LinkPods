package local.airpods.handoffprobe;

import java.util.Map;

/** Exact per-adapter identity, not a brand-wide vulnerability assumption. */
public final class RootTarget {
    public static boolean matches(Map<String,String> observed,Map<String,String> expected) {
        for(String key:new String[]{"model","android_sdk","firmware","kernel","bluetooth_sha256"}) {
            String value=expected.get(key);
            if(value==null || value.isEmpty() || !value.equals(observed.get(key)))return false;
        }
        return true;
    }
}
