package local.airpods.handoffprobe;

/** UI-thread request generations: Stop invalidates pending environment work. */
public final class RequestGate {
    private long generation;
    private boolean pending;
    public long begin() { pending=true; return ++generation; }
    public void cancel() { generation++; pending=false; }
    public boolean pending() { return pending; }
    public boolean complete(long request) {
        if (!pending || request!=generation) return false;
        pending=false; return true;
    }
}
