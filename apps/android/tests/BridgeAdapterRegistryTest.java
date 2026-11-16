package local.airpods.handoffprobe;
import java.util.*;
public final class BridgeAdapterRegistryTest {
    private static final String HASH="a".repeat(64), BUILD="ab".repeat(16);
    private static BridgeAdapterRegistry.Adapter adapter(String id,String path) {
        return new BridgeAdapterRegistry.Adapter(id,path,HASH,BUILD,32,"b".repeat(64));
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
        var nextFirmware=new BridgeAdapterRegistry.Adapter("synthetic-next",a.modulePath,"c".repeat(64),"cd".repeat(16),48,"d".repeat(64));
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
        for(String path:new String[]{"relative.so","/apex/../x.so","/apex//x.so","/apex/./x.so","/apex/x.so;id"})
            rejected(()->adapter("test",path));
        rejected(()->adapter("../escape",a.modulePath));
        System.out.println("PASS: exact unique bridge registry selection, synthetic extension and path validation");
    }
}
