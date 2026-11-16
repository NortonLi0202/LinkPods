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
    }
    static Prepared prepare(Context context,String target,boolean enable,boolean allowTemporary) {
        Prepared prepared=new Prepared();
        boolean preparingBridge=false;
        SuRootProvider su=new SuRootProvider();
        if(!context.getPackageName().equals(RootSessionConfig.PACKAGE)) {
            prepared.access=RootAccess.prepare(su,RootAccess.unavailableTemporaryProvider());
            prepared.local=prepared.access.ready()?su.localBluetoothAddress():"";
            prepared.diagnostic="此常规开发包不包含独立 Root 载荷";return prepared;
        }
        try {
            RootAssets assets=new RootAssets(context);
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
            prepared.access=RootAccess.prepare(existing,allowTemporary?allowedTemporary:RootAccess.unavailableTemporaryProvider());
            if(!prepared.access.ready()) {
                prepared.diagnostic=allowTemporary?temporary.diagnostic():"仅检测已有 Root，没有运行临时 Root";
                return prepared;
            }
            prepared.local=DeviceConfig.address(host.call(RootProtocol.Command.LOCAL_ADDRESS),true);
            if(enable && !Thread.currentThread().isInterrupted()) {
                assets.stageBridge(target);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                preparingBridge=true;
                host.call(RootProtocol.Command.BRIDGE_PREPARE);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                JSONObject status=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
                JSONObject configuration=status.getJSONObject("configuration");
                JSONObject identity=status.getJSONObject("identity");
                long age=System.currentTimeMillis()-status.getLong("updated_at_ms");
                prepared.nativeReady=status.getInt("pid")>0 && age>=0 && age<=15000
                    && status.getJSONObject("state").getString("state").equals("enabled")
                    && configuration.getString("target_address").equals(DeviceConfig.address(target,true))
                    && identity.getInt("vendor")==0x4c && identity.getInt("source")==1;
                if(!prepared.nativeReady)throw new IOException("Bridge runtime status did not validate");
            }
        }catch(InterruptedException error){
            if(preparingBridge)stopBridge();
            Thread.currentThread().interrupt();prepared.diagnostic="已取消，未启动交接服务";
        }
        catch(Exception error){prepared.diagnostic=error.getClass().getSimpleName()+": "+error.getMessage();}
        if(prepared.access==null)prepared.access=new RootAccess.Result(RootAccess.Status.PREPARATION_FAILED);
        return prepared;
    }
    static boolean stopBridge() {
        RootHost host=RootHost.current();
        if(host==null)return true; // Do not acquire root or start a provider on Stop.
        try {
            if(!host.await(5000))return false;
            host.call(RootProtocol.Command.BRIDGE_STOP);
            for(int attempt=0;attempt<30;attempt++) {
                JSONObject status=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
                JSONObject state=status.getJSONObject("state");
                if(state.getString("state").equals("disabled")&&state.getInt("active_sdp")==0)return true;
                Thread.sleep(500);
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(Exception ignored){}
        return false;
    }
}
