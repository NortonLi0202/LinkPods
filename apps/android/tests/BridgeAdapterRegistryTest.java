package local.airpods.handoffprobe;
import java.util.*;
public final class BridgeAdapterRegistryTest {
    private static final String HASH="a".repeat(64), BUILD="ab".repeat(16);
    private static BridgeAdapterRegistry.Adapter adapter(String id,String path) {
        return new BridgeAdapterRegistry.Adapter(id,path,"exact",HASH,BUILD,32,"b".repeat(64));
    }
    private static void rejected(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) {return;}
        throw new AssertionError("unsafe registry accepted");
    }
    public static void main(String[] args) throws Exception {
        var a=adapter("synthetic-a","/apex/test/lib64/a.so");
        var b=adapter("synthetic-b","/apex/test/lib64/b.so");
        var registry=new BridgeAdapterRegistry(Arrays.asList(a,b));
        if(registry.match(b.modulePath,HASH,BUILD)!=b)throw new AssertionError("second adapter not selected");
        if(registry.select(a1->a1==b?new String[]{HASH,BUILD}:null)!=b)throw new AssertionError("installed second adapter not selected");
        if(registry.select(a1->null)!=null)throw new AssertionError("unknown installed identity selected");
        var nextFirmware=new BridgeAdapterRegistry.Adapter("synthetic-next",a.modulePath,"exact","c".repeat(64),"cd".repeat(16),48,"d".repeat(64));
        var firmwareRegistry=new BridgeAdapterRegistry(Arrays.asList(a,nextFirmware));
        if(firmwareRegistry.select(a1->new String[]{nextFirmware.sha256,nextFirmware.buildId})!=nextFirmware)
            throw new AssertionError("same module path firmware identity must select once");
        if(firmwareRegistry.select(a1->new String[]{a.sha256,a.buildId})!=a)
            throw new AssertionError("original firmware identity must still select once");
        try {registry.select(a1->new String[]{HASH,BUILD});throw new AssertionError("ambiguous installed adapters accepted");}
        catch(IllegalArgumentException expected) {}
        if(registry.match("/unknown.so",HASH,BUILD)!=null || registry.match(a.modulePath,"c".repeat(64),BUILD)!=null || registry.match(a.modulePath,HASH,"cd".repeat(16))!=null)
            throw new AssertionError("unknown identity selected");
        rejected(()->new BridgeAdapterRegistry(Arrays.asList(a,adapter("other",a.modulePath))));
        rejected(()->new BridgeAdapterRegistry(Arrays.asList(a,adapter(a.id,b.modulePath))));

        var prologue=new BridgeAdapterRegistry.Adapter("synthetic-prologue",a.modulePath,"prologue","","",0,"e".repeat(64));
        var resolved=new BridgeAdapterRegistry(Arrays.asList(a,prologue));
        if(resolved.select(a1->new String[]{HASH,BUILD})!=a)throw new AssertionError("exact adapter must win over prologue");
        if(resolved.select(a1->null)!=null)throw new AssertionError("prologue adapter must not be selected by exact identity");
        if(resolved.match(a.modulePath,HASH,BUILD)!=a)throw new AssertionError("exact match must ignore prologue");
        rejected(()->new BridgeAdapterRegistry(Arrays.asList(prologue,new BridgeAdapterRegistry.Adapter("synthetic-prologue-2",a.modulePath,"prologue","","",0,"f".repeat(64)))));
        rejected(()->new BridgeAdapterRegistry.Adapter("bad",a.modulePath,"fuzzy","","",0,"e".repeat(64)));
        rejected(()->new BridgeAdapterRegistry.Adapter("bad",a.modulePath,"exact","","",0,"e".repeat(64)));

        for(String path:new String[]{"relative.so","/apex/../x.so","/apex//x.so","/apex/./x.so","/apex/x.so;id"})
            rejected(()->adapter("test",path));
        rejected(()->adapter("../escape",a.modulePath));
        System.out.println("PASS: exact unique bridge registry selection, prologue fallback, synthetic extension and path validation");
    }
}
