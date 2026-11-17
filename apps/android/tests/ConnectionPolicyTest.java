package local.airpods.handoffprobe;

public final class ConnectionPolicyTest {
    private static final String TARGET="AA:BB:CC:DD:EE:FF";
    static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void action(ConnectionPolicy.Action got,ConnectionPolicy.Action expected){expect(got==expected,"expected "+expected+", got "+got);}
    static void unsuccessfulBridgeReconnectStopsAfterOneRetry(){
        ConnectionPolicy p=new ConnectionPolicy();action(p.start(TARGET,true),ConnectionPolicy.Action.CONNECT_BRIDGE);
        long bridge=p.generation();p.ready(bridge,TARGET);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_READY,"explicit native start isn't direct");
        action(p.start(TARGET,false),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_READY,"duplicate start doesn't reclassify bridge");
        action(p.ended(bridge,TARGET),ConnectionPolicy.Action.RECONNECT);
        action(p.retry(bridge,TARGET),ConnectionPolicy.Action.CONNECT_BRIDGE);
        action(p.ended(p.generation(),TARGET),ConnectionPolicy.Action.NONE);
        expect(!p.fallbackUsed(),"bridge failure never requests another preparation");
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.FAILED,"unsuccessful bridge reconnect stops after one retry");
    }
    static void repeatedStartForSameTargetPreservesExistingSession(){
        ConnectionPolicy p=new ConnectionPolicy();
        action(p.start(TARGET,false),ConnectionPolicy.Action.CONNECT_DIRECT);
        long g=p.generation();
        p.ready(g,TARGET);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.DIRECT_READY,"direct connected");
        action(p.start(TARGET,false),ConnectionPolicy.Action.NONE);
        expect(p.generation()==g,"generation preserved across repeated start for same target");
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.DIRECT_READY,"phase stays DIRECT_READY on repeated start");
    }
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
        action(p.ended(p.generation(),TARGET,true),ConnectionPolicy.Action.NONE);
        expect(p.fallbackUsed(),"manual retry retains consumed fallback budget");
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

        unsuccessfulBridgeReconnectStopsAfterOneRetry();
        repeatedStartForSameTargetPreservesExistingSession();
        p=new ConnectionPolicy();p.start(TARGET,false);long g=p.generation();p.ended(g,TARGET,true);
        action(p.bridgePrepared(g,TARGET,true,"","",""),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).description.contains("适配器"),"ready result needs selected adapter");
        for(String reason:new String[]{"matching_bridge_adapter_missing","root_permission_denied","root_not_available","root_status_unknown","bridge_preparation_failed"}) {
            p=new ConnectionPolicy();p.start(TARGET,false);g=p.generation();p.ended(g,TARGET,true);
            p.bridgePrepared(g,TARGET,false,"",reason,"");
            expect(!p.snapshot(TARGET).description.contains(reason),"UI explains capability failure in Chinese");
        }
        p=new ConnectionPolicy();p.start(TARGET,false);long previous=p.generation();p.ready(previous,TARGET);p.stop();
        p.start(TARGET,false);
        action(p.ended(p.generation(),TARGET,true),ConnectionPolicy.Action.PREPARE_BRIDGE);
        expect(p.fallbackUsed(),"new session can use the lifetime fallback after prior direct success");
        p=new ConnectionPolicy();p.start(TARGET,false);long beforeUpgrade=p.generation();p.ready(beforeUpgrade,TARGET);
        action(p.start(TARGET,true),ConnectionPolicy.Action.CONNECT_BRIDGE);
        expect(p.generation()!=beforeUpgrade,"explicit bridge upgrade invalidates direct session callbacks");
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_CONNECTING,"prepared bridge upgrades active direct session");
        action(p.ready(beforeUpgrade,TARGET),ConnectionPolicy.Action.NONE);
        action(p.ended(beforeUpgrade,TARGET,true),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_CONNECTING,"old direct callbacks cannot affect bridge upgrade");
        p.ready(p.generation(),TARGET);
        action(p.start(TARGET,false),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_READY,"normal start never downgrades explicit bridge");
        expect(!p.fallbackUsed(),"explicit upgrade does not request service Root fallback");
        p=new ConnectionPolicy();p.start(TARGET,false);long preparing=p.generation();p.ended(preparing,TARGET,true);
        action(p.start(TARGET,true),ConnectionPolicy.Action.CONNECT_BRIDGE);
        action(p.bridgePrepared(preparing,TARGET,true,"old","",""),ConnectionPolicy.Action.NONE);
        expect(p.snapshot(TARGET).phase==ConnectionPolicy.Phase.BRIDGE_CONNECTING,"explicit upgrade invalidates pending automatic preparation");
        System.out.println("PASS: direct-first, bounded bridge fallback, reconnect, failures and stale completion");
    }
}
