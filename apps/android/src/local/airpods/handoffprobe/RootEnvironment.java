package local.airpods.handoffprobe;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

/** App entry for existing root, a matching temporary adapter and bridge staging. */
final class RootEnvironment {
    static final RootWorkQueue WORK=new RootWorkQueue();
    static final class Prepared {
        RootAccess.Result access;
        String local="",diagnostic="";
        boolean nativeReady;
        boolean temporarySupported;
        boolean temporaryAttempted;
        boolean rootChecked;
        String adapterId="",missing="";
        volatile boolean bridgeOwned;
        String targetAddress="",bridgeProfileHash="";
        BridgeRuntimeLease lease;
    }
    static Prepared prepare(Context context,String target,boolean enable,boolean allowTemporary) {
        Prepared prepared=new Prepared();
        prepared.targetAddress=target==null?"":target;
        boolean preparingBridge=false;
        SuRootProvider su=new SuRootProvider();
        if(!context.getPackageName().equals(RootSessionConfig.PACKAGE)) {
            prepared.access=RootAccess.prepare(su,RootAccess.unavailableTemporaryProvider());
            prepared.local=prepared.access.ready()?su.localBluetoothAddress():"";
            prepared.diagnostic="此常规开发包不包含独立 Root 载荷";return prepared;
        }
        try {
            RootAssets assets=new RootAssets(context);
            // A bridge request must resolve system adaptation before requesting Root.
            // Normal direct connection never calls this preparation method.
            BridgeAdapterRegistry.Adapter adapter=enable?assets.matchBridge():null;
            if(enable && adapter==null) {
                prepared.missing="matching_bridge_adapter_missing";
                prepared.diagnostic="没有与当前蓝牙模块精确匹配的桥接适配；未请求 Root";
                prepared.access=new RootAccess.Result(RootAccess.Status.UNSUPPORTED);
                return prepared;
            }
            if(adapter!=null){prepared.adapterId=adapter.id;prepared.bridgeProfileHash=adapter.profileHash;}
            RootHost host=RootHost.get(context);
            StandaloneRootProvider temporary=new StandaloneRootProvider(context,host,assets);
            prepared.temporarySupported=temporary.supported();
            RootAccess.Provider existing=new RootAccess.Provider() {
                public RootAccess.Probe probe() {
                    RootAccess.Probe channel=temporary.probe();
                    if(channel==RootAccess.Probe.GRANTED)return channel;
                    RootAccess.Probe available=su.probe();
                    if(available!=RootAccess.Probe.GRANTED)return available==RootAccess.Probe.ABSENT&&channel==RootAccess.Probe.UNKNOWN?channel:available;
                    if(!su.startBroker(new File(context.getApplicationInfo().nativeLibraryDir,"liblpbootstrap.so")))return RootAccess.Probe.UNKNOWN;
                    try{return host.await(15000)&&host.call(RootProtocol.Command.ID).trim().equals("0")?RootAccess.Probe.GRANTED:RootAccess.Probe.UNKNOWN;}
                    catch(InterruptedException error){Thread.currentThread().interrupt();return RootAccess.Probe.UNKNOWN;}
                    catch(IOException error){return RootAccess.Probe.UNKNOWN;}
                }
                public boolean supported(){return true;}
                public boolean acquire(){return false;}
            };
            RootAccess.Provider allowedTemporary=new RootAccess.Provider() {
                public RootAccess.Probe probe(){return temporary.probe();}
                public boolean supported(){return temporary.supported();}
                public boolean acquire(){prepared.temporaryAttempted=true;return temporary.acquire();}
            };
            prepared.rootChecked=true;
            prepared.access=RootAccess.prepare(existing,allowTemporary?allowedTemporary:RootAccess.unavailableTemporaryProvider());
            if(!prepared.access.ready()) {
                prepared.missing=prepared.access.status==RootAccess.Status.DENIED?"root_permission_denied"
                    :prepared.access.status==RootAccess.Status.UNKNOWN?"root_status_unknown":"root_not_available";
                prepared.diagnostic=allowTemporary?temporary.diagnostic():"仅检测已有 Root，没有运行临时 Root";
                return prepared;
            }
            prepared.local=DeviceConfig.address(host.call(RootProtocol.Command.LOCAL_ADDRESS),true);
            if(enable && !Thread.currentThread().isInterrupted()) {
                assets.stageBridge(target,adapter);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                preparingBridge=true;
                String outcome=host.call(RootProtocol.Command.BRIDGE_PREPARE);
                for(String line:outcome.split("\\r?\\n"))
                    if(line.equals("LINKPODS_BRIDGE_STARTED"))prepared.bridgeOwned=true;
                JSONObject status=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
                JSONObject configuration=status.getJSONObject("configuration");
                JSONObject identity=status.getJSONObject("identity");
                if(!status.optString("instance_id","").isEmpty())prepared.lease=new BridgeRuntimeLease(prepared.targetAddress,adapter.profileHash,
                    status.getInt("pid"),status.getString("instance_id"));
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                long age=System.currentTimeMillis()-status.getLong("updated_at_ms");
                prepared.nativeReady=status.getInt("pid")>0 && age>=0 && age<=15000
                    && status.getJSONObject("state").getString("state").equals("enabled")
                    && configuration.getString("target_address").equals(DeviceConfig.address(target,true))
                    && configuration.getString("profile_sha256").equals(adapter.profileHash)
                    && identity.getInt("vendor")==0x4c && identity.getInt("source")==1;
                if(!prepared.nativeReady)throw new IOException("Bridge runtime status did not validate");
            }
        }catch(InterruptedException error){
            // Clear interruption for bounded ownership cleanup, then preserve cancellation.
            Thread.interrupted();
            if(preparingBridge && prepared.bridgeOwned)stopOwnedBridge(prepared);
            Thread.currentThread().interrupt();prepared.diagnostic="已取消，未启动交接服务";
            prepared.missing="preparation_cancelled";
        }
        catch(Exception error){
            prepared.missing=enable?"bridge_preparation_failed":"root_preparation_failed";
            prepared.diagnostic=error.getClass().getSimpleName()+": "+error.getMessage();
        }
        if(prepared.access==null)prepared.access=new RootAccess.Result(RootAccess.Status.PREPARATION_FAILED);
        return prepared;
    }
    static boolean stopOwnedBridge(Prepared prepared) {
        if(prepared==null || !prepared.bridgeOwned)return true;
        if(prepared.lease==null)return false;
        RootHost host=RootHost.current();
        if(host==null)return false;
        try {
            if(!host.await(5000))return false;
            JSONObject status=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
            JSONObject configuration=status.getJSONObject("configuration");
            if(!prepared.lease.matches(configuration.getString("target_address"),configuration.getString("profile_sha256"),
                    status.getInt("pid"),status.optString("instance_id","")))return false;
            host.call(RootProtocol.Command.BRIDGE_STOP);
            for(int attempt=0;attempt<30;attempt++) {
                JSONObject latest=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
                JSONObject latestConfig=latest.getJSONObject("configuration");
                if(!prepared.lease.matches(latestConfig.getString("target_address"),latestConfig.getString("profile_sha256"),
                        latest.getInt("pid"),latest.optString("instance_id","")))return false;
                JSONObject state=latest.getJSONObject("state");
                if(state.getString("state").equals("disabled")&&state.getInt("active_sdp")==0){prepared.bridgeOwned=false;return true;}
                Thread.sleep(500);
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(Exception ignored){}
        return false;
    }
}
