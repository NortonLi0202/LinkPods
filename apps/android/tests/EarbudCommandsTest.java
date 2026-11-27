package local.airpods.handoffprobe;

import java.util.Arrays;

/** Hand-checked AACP fixtures from the pinned upstream protocol implementations. */
public final class EarbudCommandsTest {
    private static final String A = "11:22:33:44:55:66", B = "AA:BB:CC:DD:EE:FF";
    private static void expect(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    private static byte[] control(int id, int value) {
        return new byte[]{4,0,4,0,9,0,(byte) id,(byte) value,0,0,0};
    }
    private static void tokenAndDeadlineBoundaries() {
        EarbudCommands.State state=new EarbudCommands.State();
        state.begin(A);state.connected();
        state.receive(control(0x28,2));state.receive(control(0x0a,2));
        EarbudCommands.PendingWrite first=state.queueRequest(A,0x28,1,100);
        EarbudCommands.PendingWrite other=state.queueRequest(A,0x0a,1,100);
        expect(first!=null && other!=null && other.token>first.token,"different setting requests get increasing tokens");
        expect(Arrays.equals(first.packet,control(0x28,1)),"tokenized request retains exact wire encoding");
        state.failed(0x0a,first.token);
        expect(state.snapshot(A,5099).pending.size()==2,"token for another setting cannot fail request; deadline is exclusive before 5000ms");
        state.sent(0x28,other.token);
        state.failed(0x0a,other.token);
        expect(state.snapshot(A,5099).pending.contains(0x28),"failure clears only matching setting and token");
        EarbudSnapshot expired=state.snapshot(A,5100);
        expect(expired.pending.isEmpty() && "设置请求超时，耳机未确认".equals(expired.status),
            "exact deadline expires request; wrong sent token cannot claim socket delivery");
        EarbudCommands.PendingWrite retry=state.queueRequest(A,0x28,1,5100);
        expect(retry!=null && retry.token>other.token,"retry allowed at exact timeout with increasing token");
        state.sent(0x28,first.token);
        expired=state.snapshot(A,10100);
        expect("设置请求超时，耳机未确认".equals(expired.status),"late success cannot mark retry as sent");
        state.begin(A);state.connected();state.receive(control(0x28,2));
        EarbudCommands.PendingWrite fresh=state.queueRequest(A,0x28,1,10200);
        expect(fresh!=null && fresh.token>retry.token,"begin never recycles tokens across sessions");
        state.failed(0x28,retry.token);
        state.sent(0x28,fresh.token);
        expect(state.snapshot(A,15199).pending.contains(0x28),"old session failure cannot clear fresh request");
        expect("设置已发送，耳机未确认".equals(state.snapshot(A,15200).status),"matching success changes exact-deadline timeout status");
    }
    public static void main(String[] args) {
        tokenAndDeadlineBoundaries();
        expect(Arrays.equals(EarbudCommands.encode(0x0d,2), new byte[]{4,0,4,0,9,0,13,2,0,0,0}), "ANC uses setting 13 and mode 2");
        expect(Arrays.equals(EarbudCommands.encode(0x28,EarbudCommands.booleanValue(true)),new byte[]{4,0,4,0,9,0,40,1,0,0,0}), "conversation true is Apple bool 1");
        expect(EarbudCommands.booleanValue(false)==2, "Apple bool false is 2");
        int[] booleanIds={0x0a,0x1b,0x25,0x26,0x28};
        for(int id:booleanIds) {
            expect(EarbudCommands.encode(id,1)!=null && EarbudCommands.encode(id,2)!=null,"known bool accepts only encoded values");
            expect(EarbudCommands.encode(id,0)==null && EarbudCommands.encode(id,3)==null,"bool rejects invalid values");
        }
        expect(EarbudCommands.encode(6,1)==null && EarbudCommands.encode(0xff,1)==null,"ownership and arbitrary writes forbidden");
        expect(EarbudCommands.encode(0x0d,0)==null && EarbudCommands.encode(0x0d,5)==null,"unknown listening modes rejected");
        expect(EarbudCommands.encode(1,0)!=null && EarbudCommands.encode(1,2)!=null && EarbudCommands.encode(1,3)==null,"microphone enum bounded");
        expect(EarbudCommands.encode(0x1f,15)!=null && EarbudCommands.encode(0x1f,100)!=null && EarbudCommands.encode(0x1f,14)==null && EarbudCommands.encode(0x1f,101)==null,"chime bounded 15..100");
        expect(EarbudCommands.encode(0x23,0)!=null && EarbudCommands.encode(0x23,2)!=null && EarbudCommands.encode(0x23,3)==null,"swipe timing bounded");

        EarbudCommands.State state=new EarbudCommands.State();
        state.begin(A);
        expect(!state.snapshot(A,0).ready && state.snapshot(A,0).leftBattery==null,"initial state unknown");
        state.connected();
        byte[] repeatedHeader={4,0,4,0,4,0,4,0,9,0,13,2,0,0,0};
        expect(state.receive(repeatedHeader),"LibrePods repeated data headers decode a scalar report");
        expect(state.snapshot(A,0).value(13)==2,"nested report reaches observed state");
        expect(!state.receive(new byte[]{4,0,4,0,9,0,13,2,1,0,0}),"multi-byte scalar report remains rejected");
        expect(state.queue(A,0x28,1,0)==null,"unobserved capability rejected");
        expect(!state.receive(control(6,1)),"ownership not interpreted as setting");
        expect(!state.receive(new byte[]{4,0,4,0,9,0,40}),"truncated setting rejected");
        expect(!state.receive(new byte[]{4,0,4,0,9,0,40,1}),"short setting rejected");
        byte[] trailing=Arrays.copyOf(control(0x28,1),12);
        expect(!state.receive(trailing),"extra bytes rejected rather than misframed");
        byte[] reserved=control(0x28,1); reserved[10]=1;
        expect(!state.receive(reserved),"unknown multi-byte value rejected");
        byte[] badService=control(0x28,1);badService[2]=5;
        expect(!state.receive(badService),"wrong service header rejected");
        expect(!state.receive(control(0x28,0)),"invalid bool ignored");
        expect(state.receive(control(0x28,2)),"real observed bool decoded");
        expect(Boolean.FALSE.equals(state.snapshot(A,0).enabled(0x28)),"observed false decoded");
        expect(state.snapshot(A,0).enabled(0x0d)==null,"nonboolean and unknown stay null");
        expect(state.queue(B,0x28,1,0)==null,"cross-target write rejected");
        expect(state.queue(A,0x28,1,10)!=null,"observed setting queues write");
        EarbudSnapshot pending=state.snapshot(A,11);
        expect(pending.pending.contains(0x28) && Boolean.FALSE.equals(pending.enabled(0x28)),"queued request cannot optimistically change observed state");
        expect(state.queue(A,0x28,1,12)==null,"duplicate pending write rejected");
        state.receive(control(0x28,2));
        expect(state.snapshot(A,13).pending.contains(0x28),"old or conflicting reply cannot confirm new request");
        state.receive(control(0x28,1));
        EarbudSnapshot confirmed=state.snapshot(A,14);
        expect(confirmed.pending.isEmpty() && "耳机已回报设置".equals(confirmed.status) && Boolean.TRUE.equals(confirmed.enabled(0x28)),"matching real reply confirms");
        state.queue(A,0x28,2,20);
        EarbudSnapshot expiredUnsent=state.snapshot(A,10000);
        expect(expiredUnsent.pending.isEmpty() && Boolean.TRUE.equals(expiredUnsent.enabled(0x28))
            && "设置请求超时，耳机未确认".equals(expiredUnsent.status),
            "timeout before write completion reports request timeout without claiming sent");
        EarbudCommands.PendingWrite sentBeforeTimeout=state.queueRequest(A,0x28,2,10000);
        expect(sentBeforeTimeout!=null,"request queued before send confirmation");
        state.sent(0x28,sentBeforeTimeout.token);
        EarbudSnapshot expiredSent=state.snapshot(A,15000);
        expect(expiredSent.pending.isEmpty() && "设置已发送，耳机未确认".equals(expiredSent.status),
            "timeout after confirmed socket write reports sent without reply");
        state.queue(A,0x28,2,15001); state.failed(0x28);
        expect(state.snapshot(A,15002).pending.isEmpty() && Boolean.TRUE.equals(state.snapshot(A,15002).enabled(0x28)),"send failure preserves observation");
        EarbudCommands.PendingWrite firstAttempt=state.queueRequest(A,0x28,2,10010);
        expect(firstAttempt!=null,"tokenized request queued");
        expect(state.snapshot(A,16000).pending.isEmpty(),"first request expires after timeout");
        EarbudCommands.PendingWrite retryAttempt=state.queueRequest(A,0x28,1,16001);
        expect(retryAttempt!=null && retryAttempt.token!=firstAttempt.token,"retry gets distinct request token");
        state.failed(0x28,firstAttempt.token);
        EarbudSnapshot afterStaleFailure=state.snapshot(A,16002);
        expect(afterStaleFailure.pending.contains(0x28) && "等待耳机确认设置".equals(afterStaleFailure.status),
            "late failure from timed-out attempt must not clear active retry");
        state.failed(0x28,retryAttempt.token);
        expect(state.snapshot(A,16003).pending.isEmpty(),"matching request token failure clears active retry");
        expect(!state.snapshot(B,0).ready && state.snapshot(B,0).settings.isEmpty(),"wrong target reveals no state");

        state.receive(control(0x0d,2));
        expect(state.snapshot(A,0).supportsListeningMode(2) && state.snapshot(A,0).supportsListeningMode(3),"ANC observation enables core modes");
        expect(!state.snapshot(A,0).supportsListeningMode(1) && !state.snapshot(A,0).supportsListeningMode(4),"off and adaptive require evidence");
        expect(state.queue(A,0x0d,4,0)==null,"unobserved adaptive cannot send");
        state.receive(control(0x0d,4)); state.receive(control(0x0d,2));
        expect(state.snapshot(A,0).supportsListeningMode(4),"observed adaptive capability retained in same session");
        state.receive(control(0x0d,1));
        expect(state.snapshot(A,0).supportsListeningMode(1),"off requires actual observation");

        // Battery payload count=3, each entry [component,reserved,percent,charging,reserved].
        byte[] batteries={4,0,4,0,4,0,3,4,1,73,2,0,2,1,0,1,0,8,1,100,2,0};
        byte[] nestedBattery=new byte[batteries.length+4];
        System.arraycopy(new byte[]{4,0,4,0},0,nestedBattery,0,4);
        System.arraycopy(batteries,0,nestedBattery,4,batteries.length);
        expect(state.receive(nestedBattery) && state.snapshot(A,0).leftBattery==73,"nested battery notification reaches state");
        expect(state.receive(batteries),"complete battery notification accepted");
        EarbudSnapshot battery=state.snapshot(A,0);
        expect(battery.leftBattery==73 && battery.rightBattery==0 && battery.caseBattery==100,"physical IDs and percent boundaries decoded");
        expect(!state.receive(Arrays.copyOf(batteries,batteries.length-1)),"truncated battery notification rejected atomically");
        expect(!state.receive(Arrays.copyOf(batteries,batteries.length+1)),"extra battery bytes rejected");
        state.receive(new byte[]{4,0,4,0,4,0,1,4,1,127,2,0});
        expect(state.snapshot(A,0).leftBattery==null,"127 invalidates stale battery instead of showing fake percent");
        state.receive(new byte[]{4,0,4,0,4,0,1,2,1,(byte)255,4,0});
        expect(state.snapshot(A,0).rightBattery==null,"255 disconnected battery is unknown");
        state.receive(new byte[]{4,0,4,0,4,0,1,8,1,50,4,0});
        expect(state.snapshot(A,0).caseBattery==null,"disconnected component cannot display a numeric battery");
        expect(!state.receive(new byte[]{4,0,4,0,4,0,1,3,1,90,1,0}),"unknown battery component ignored");
        try { battery.settings.put(0x28,2); throw new AssertionError("mutable settings"); } catch(UnsupportedOperationException expected) {}
        try { pending.pending.clear(); throw new AssertionError("mutable pending"); } catch(UnsupportedOperationException expected) {}
        expect(Boolean.FALSE.equals(pending.enabled(0x28)),"old snapshot is independent of live state");
        expect(state.receive(new byte[]{4,0,4,0,4,0,1,4,1,42,1,0}),"fresh battery fixture accepted");
        expect(state.snapshot(A,0).leftBattery==42,"fresh battery fixture must be numeric before disconnect");
        state.disconnected();
        expect(state.snapshot(A,0).leftBattery==42,"disconnected snapshot retains last battery reading");
        expect(!state.snapshot(A,0).ready && state.snapshot(A,0).settings.isEmpty() && state.snapshot(A,0).pending.isEmpty(),"disconnect invalidates settings and requests");
        expect(!state.receive(control(0x28,1)) && state.queue(A,0x28,2,0)==null,"disconnected state ignores late packets/writes");
        state.connected();
        expect(state.snapshot(A,0).leftBattery==null && state.snapshot(A,0).rightBattery==null
            && state.snapshot(A,0).caseBattery==null,"reconnected session must not present old battery readings as current");
        state.receive(new byte[]{4,0,4,0,4,0,1,4,1,42,1,0});
        expect(state.snapshot(A,0).leftBattery==42 && state.snapshot(A,0).rightBattery==null,
            "only newly reported component becomes current after reconnect");
        state.begin(B);state.connected();
        expect(state.snapshot(B,0).leftBattery==null && !state.snapshot(B,0).supportsListeningMode(4),"new target cannot inherit battery/capabilities");
        expect(!state.snapshot(A,0).ready && state.snapshot(A,0).settings.isEmpty(),"old target invalidated");
        System.out.println("PASS: safe AACP wire/ranges, strict parsing/battery, scoped immutable snapshots, observed confirmations, timeout/disconnect and mode evidence");
    }
}
