package local.airpods.handoffprobe;

public final class ConnectionPolicyTest {
    private static final String TARGET="AA:BB:CC:DD:EE:FF";
    static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void action(ConnectionPolicy.Action got,ConnectionPolicy.Action expected){expect(got==expected,"expected "+expected+", got "+got);}
    public static void main(String[] args){
        ConnectionPolicy p=new ConnectionPolicy();
        action(p.start(TARGET,false),ConnectionPolicy.Action.CONNECT_DIRECT);
        long direct=p.generation();
        action(p.ready(direct,TARGET),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.DIRECT_READY,"actual handshake confirms direct");
        action(p.ended(direct,TARGET),ConnectionPolicy.Action.RECONNECT);
        action(p.retry(direct,TARGET),ConnectionPolicy.Action.CONNECT_DIRECT);
        action(p.ended(p.generation(),TARGET),ConnectionPolicy.Action.RECONNECT);
        expect(!p.fallbackUsed(),"working direct never requests root even after reconnect failure");

        p=new ConnectionPolicy();p.start(TARGET,false);
        action(p.ended(p.generation(),TARGET),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.FAILED,"ordinary missing handshake fails without Root");
        expect(!p.fallbackUsed(),"timeout isn't evidence of a compatibility blocker");
        action(p.start(TARGET,false),ConnectionPolicy.Action.CONNECT_DIRECT);
        long failed=p.generation();
        action(p.ended(failed,TARGET,true),ConnectionPolicy.Action.PREPARE_BRIDGE);
        action(p.ended(failed,TARGET),ConnectionPolicy.Action.NONE);
        expect(p.fallbackUsed(),"fallback consumed once");
        action(p.bridgePrepared(failed,TARGET,true,"adapter-one","",""),ConnectionPolicy.Action.CONNECT_BRIDGE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_CONNECTING,"validated bridge chosen");
        action(p.ended(p.generation(),TARGET),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.FAILED,"failed bridge handshake stops");
        action(p.start(TARGET,false),ConnectionPolicy.Action.CONNECT_DIRECT);
        action(p.ended(p.generation(),TARGET),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.FAILED,"manual retry does not renew lifetime root budget");

        for(String missing:new String[]{"missing adapter","Root denied","no existing Root"}){
            p=new ConnectionPolicy();p.start(TARGET,false);long g=p.generation();p.ended(g,TARGET,true);
            action(p.bridgePrepared(g,TARGET,false,"",missing,"detail"),ConnectionPolicy.Action.NONE);
            expect(p.snapshot(TARGET).description.contains(missing),"concrete missing capability visible");
            action(p.bridgePrepared(g,TARGET,true,"late","",""),ConnectionPolicy.Action.NONE);
        }
        p=new ConnectionPolicy();p.start(TARGET,false);long old=p.generation();p.ended(old,TARGET,true);p.stop();
        action(p.bridgePrepared(old,TARGET,true,"adapter","",""),ConnectionPolicy.Action.NONE);
        action(p.ready(old,TARGET),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.STOPPED,"stop blocks stale completion");
        p.start(TARGET,false);long current=p.generation();
        action(p.ready(old,TARGET),ConnectionPolicy.Action.NONE);
        action(p.retry(old,TARGET),ConnectionPolicy.Action.NONE);
        action(p.ended(current,"11:22:33:44:55:66"),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.DIRECT_CONNECTING,"generation and target isolated");
        expect(p.snapshot("11:22:33:44:55:66").phase==ConnectionPolicy.Phase.IDLE,"foreign target gets no capability");

        p=new ConnectionPolicy();action(p.start(TARGET,true),ConnectionPolicy.Action.CONNECT_BRIDGE);
        long bridge=p.generation();p.ready(bridge,TARGET);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_READY,"explicit native start isn't direct");
        action(p.start(TARGET,false),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_READY,"duplicate start doesn't reclassify bridge");
        action(p.ended(bridge,TARGET),ConnectionPolicy.Action.RECONNECT);
        action(p.retry(bridge,TARGET),ConnectionPolicy.Action.CONNECT_BRIDGE);
        action(p.ended(p.generation(),TARGET),ConnectionPolicy.Action.NONE);
        expect(!p.fallbackUsed(),"bridge failure never requests another preparation");
        p=new ConnectionPolicy();p.start(TARGET,false);long g=p.generation();p.ended(g,TARGET,true);
        action(p.bridgePrepared(g,TARGET,true,"","",""),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).description.contains("适配器"),"ready result needs selected adapter");
        for(String reason:new String[]{"matching_bridge_adapter_missing","root_permission_denied","root_not_available","root_status_unknown","bridge_preparation_failed"}) {
            p=new ConnectionPolicy();p.start(TARGET,false);g=p.generation();p.ended(g,TARGET,true);
            p.bridgePrepared(g,TARGET,false,"",reason,"");
            expect(!p.snapshot(TARGET).description.contains(reason),"UI explains capability failure in Chinese");
        }
        System.out.println("PASS: direct-first, bounded bridge fallback, reconnect, failures and stale completion");
    }
}
