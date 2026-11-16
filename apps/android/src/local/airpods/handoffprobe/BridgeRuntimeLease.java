package local.airpods.handoffprobe;
/** Identity of a bridge actually started by this preparation, not an adopted resident. */
final class BridgeRuntimeLease {
    private final String target,profile,instance;
    private final int pid;
    BridgeRuntimeLease(String target,String profile,int pid,String instance) {
        if(instance==null || instance.isEmpty() || pid<=0)throw new IllegalArgumentException("Missing runtime instance");
        this.target=target;this.profile=profile;this.pid=pid;this.instance=instance;
    }
    boolean matches(String target,String profile,int pid,String instance) {
        return this.target.equalsIgnoreCase(target) && this.profile.equals(profile) && this.pid==pid && this.instance.equals(instance);
    }
    boolean matchesPreparation(String target,String profile,String instance) {
        return this.target.equalsIgnoreCase(target) && this.profile.equals(profile) && this.instance.equals(instance);
    }
    boolean sameInstance(BridgeRuntimeLease other) {
        return other!=null && matches(other.target,other.profile,other.pid,other.instance);
    }
}
