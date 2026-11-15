package local.airpods.handoffprobe;

/** Root acquisition policy. Providers must prove UID 0, not just locate su. */
public final class RootAccess {
    public enum Probe { GRANTED, ABSENT, DENIED, UNKNOWN }
    public enum Status { EXISTING_ROOT, TEMPORARY_ROOT, DENIED, UNKNOWN, UNSUPPORTED, FAILED }
    public interface Provider {
        Probe probe();
        boolean supported();
        boolean acquire();
    }
    public static final class Result {
        public final Status status;
        Result(Status status) { this.status=status; }
        public boolean ready() { return status==Status.EXISTING_ROOT || status==Status.TEMPORARY_ROOT; }
    }
    public static Probe fromCommand(int exit, String output) {
        return exit==0 && output!=null && output.trim().equals("0") ? Probe.GRANTED : Probe.DENIED;
    }
    public static Probe fromExecFailure(String message) {
        if (message!=null && message.matches("(?s).*\\berror=2(?:[,\\s].*|$)")) return Probe.ABSENT;
        if (message!=null && message.matches("(?s).*\\berror=13(?:[,\\s].*|$)")) return Probe.DENIED;
        return Probe.UNKNOWN;
    }
    public static Result prepare(Provider existing, Provider temporary) {
        if(Thread.currentThread().isInterrupted())return new Result(Status.UNKNOWN);
        Probe state = existing.probe();
        if (state==Probe.GRANTED) return new Result(Status.EXISTING_ROOT);
        if (state==Probe.DENIED) return new Result(Status.DENIED);
        if (state!=Probe.ABSENT) return new Result(Status.UNKNOWN);
        if (!temporary.supported()) return new Result(Status.UNSUPPORTED);
        if(Thread.currentThread().isInterrupted())return new Result(Status.UNKNOWN);
        if (!temporary.acquire()) return new Result(Status.FAILED);
        return new Result(temporary.probe()==Probe.GRANTED ? Status.TEMPORARY_ROOT : Status.FAILED);
    }
    /** No exploit backend is bundled until an app-callable route is integrated. */
    public static Provider unavailableTemporaryProvider() {
        return new Provider() {
            public Probe probe() { return Probe.ABSENT; }
            public boolean supported() { return false; }
            public boolean acquire() { return false; }
        };
    }
}
