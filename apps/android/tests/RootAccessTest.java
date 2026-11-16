package local.airpods.handoffprobe;

public final class RootAccessTest {
    static void expect(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static class Fake implements RootAccess.Provider {
        RootAccess.Probe state; boolean supported, success; int acquisitions, probes;
        Fake(RootAccess.Probe state, boolean supported, boolean success) { this.state=state; this.supported=supported; this.success=success; }
        public RootAccess.Probe probe() { probes++; return state; }
        public boolean supported() { return supported; }
        public boolean acquire() { acquisitions++; if (success) state=RootAccess.Probe.GRANTED; return success; }
    }
    public static void main(String[] args) {
        expect(!new RootAccess.Result(RootAccess.Status.PREPARATION_FAILED).ready(),"asset/preparation failure cannot claim a Root result");
        Fake existing = new Fake(RootAccess.Probe.GRANTED,true,false), temporary = new Fake(RootAccess.Probe.ABSENT,true,true);
        Thread.currentThread().interrupt();
        try {
            expect(RootAccess.prepare(existing,temporary).status==RootAccess.Status.UNKNOWN,"cancelled root request does not start providers");
            expect(existing.probes==0 && temporary.acquisitions==0,"no provider activity after cancellation");
        } finally {Thread.interrupted();}
        expect(RootAccess.prepare(existing,temporary).status == RootAccess.Status.EXISTING_ROOT, "reuse existing root");
        expect(temporary.probes==0 && temporary.acquisitions==0, "never run exploit when root is available");
        existing.state=RootAccess.Probe.ABSENT;
        expect(RootAccess.prepare(existing,temporary).status == RootAccess.Status.TEMPORARY_ROOT, "supported temporary fallback");
        expect(temporary.probes==1 && temporary.acquisitions==1, "recheck real UID after acquisition");
        existing.state=RootAccess.Probe.DENIED;
        expect(RootAccess.prepare(existing,temporary).status==RootAccess.Status.DENIED, "do not bypass denied root permission");
        expect(temporary.acquisitions==1, "denial does not start exploit");
        existing.state=RootAccess.Probe.UNKNOWN;
        expect(RootAccess.prepare(existing,temporary).status==RootAccess.Status.UNKNOWN, "timeout is not root absence");
        existing.state=RootAccess.Probe.ABSENT;
        temporary.supported=false;
        expect(RootAccess.prepare(existing,temporary).status==RootAccess.Status.UNSUPPORTED, "unsupported system stays unsupported");
        temporary = new Fake(RootAccess.Probe.ABSENT,true,true) {
            public RootAccess.Probe probe() { return RootAccess.Probe.ABSENT; }
        };
        expect(RootAccess.prepare(existing,temporary).status==RootAccess.Status.FAILED, "acquisition return code is not UID zero");
        expect(RootAccess.fromCommand(0,"0\n")==RootAccess.Probe.GRANTED, "actual id -u output");
        for (String output : new String[]{"uid=0", "10000", "root", "0\n10000"})
            expect(RootAccess.fromCommand(0,output)!=RootAccess.Probe.GRANTED, "reject non-root or malformed output");
        expect(RootAccess.fromCommand(1,"0")!=RootAccess.Probe.GRANTED, "failed command cannot grant root");
        expect(RootAccess.fromExecFailure("error=2, No such file or directory")==RootAccess.Probe.ABSENT, "missing su route");
        expect(RootAccess.fromExecFailure("error=13, Permission denied")==RootAccess.Probe.DENIED, "blocked su is not absent root");
        expect(RootAccess.fromExecFailure("pipe read failed")==RootAccess.Probe.UNKNOWN, "transport failure cannot trigger exploit fallback");
        System.out.println("PASS: root reuse, temporary fallback, UID verification, denial and timeout");
    }
}
