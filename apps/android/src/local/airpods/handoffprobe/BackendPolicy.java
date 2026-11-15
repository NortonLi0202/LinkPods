package local.airpods.handoffprobe;

/** Capability choice, independent of brand and Android version. No hook loading. */
public final class BackendPolicy {
    public enum Path { CONTROL_CHANNEL, NATIVE_BRIDGE, UNAVAILABLE }
    public static Path select(boolean controlObserved, boolean rootGranted,
                              boolean exactModuleMatch, boolean runtimeValidated) {
        if (rootGranted && exactModuleMatch && runtimeValidated) return Path.NATIVE_BRIDGE;
        if (controlObserved) return Path.CONTROL_CHANNEL;
        return Path.UNAVAILABLE;
    }
    public static boolean handoffVerified() { return false; }
}
