package local.airpods.handoffprobe;

import android.content.Context;
import java.io.*;
import org.json.JSONObject;

/** App entry for existing root, a matching temporary adapter and bridge staging. */
final class RootEnvironment {
    static final RootWorkQueue WORK=new RootWorkQueue();
    // Keep failed cleanups reachable even after the Activity/Service releases its reference.
    private static final java.util.Set<Prepared> pendingCleanup=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Prepared> readyBridges=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Object bridgeLock=new Object();
    private static java.util.concurrent.Future<?> cleanupRetry;
    private static int cleanupRetryAttempt;
    private static long cleanupRetryGeneration;
    private static final long[] cleanupRetryDelays={1000,5000,15000};
    static final class Prepared {
        RootAccess.Result access;
        String local="",diagnostic="";
        volatile boolean nativeReady;
        volatile boolean cleanupRequested;
        boolean temporarySupported;
        boolean temporaryAttempted;
        boolean rootChecked;
        String adapterId="",missing="";
        volatile boolean bridgeOwned;
        String targetAddress="",bridgeProfileHash="";
        String bridgeInstanceId="";
        BridgeRuntimeLease lease;
    }
    static boolean bridgeUsable(Prepared prepared) {
        synchronized(bridgeLock) {
            return prepared!=null && prepared.nativeReady && !prepared.cleanupRequested
                && prepared.access!=null && prepared.access.ready();
        }
    }
    private static boolean sameBridge(Prepared first,Prepared second) {
        if(first.lease!=null && second.lease!=null)return first.lease.sameInstance(second.lease);
        if(first.lease!=null)return first.lease.matchesPreparation(second.targetAddress,second.bridgeProfileHash,second.bridgeInstanceId);
        if(second.lease!=null)return second.lease.matchesPreparation(first.targetAddress,first.bridgeProfileHash,first.bridgeInstanceId);
        return !first.bridgeInstanceId.isEmpty() && first.bridgeInstanceId.equals(second.bridgeInstanceId)
            && first.targetAddress.equalsIgnoreCase(second.targetAddress) && first.bridgeProfileHash.equals(second.bridgeProfileHash);
    }
    static void markBridgeStopping(Prepared prepared) {
        if(prepared==null)return;
        synchronized(bridgeLock) {
            prepared.nativeReady=false;
            boolean firstRequest=!prepared.cleanupRequested;
            prepared.cleanupRequested=true;
            readyBridges.remove(prepared);
            if(!prepared.bridgeOwned)return;
            pendingCleanup.add(prepared);
            if(firstRequest)cleanupRetryAttempt=0;
            for(Prepared alias:readyBridges)if(sameBridge(prepared,alias)) {
                alias.nativeReady=false;alias.cleanupRequested=true;readyBridges.remove(alias);
            }
        }
    }
    static void queueBridgeStop(Prepared prepared) {
        if(prepared==null)return;
        markBridgeStopping(prepared);
        WORK.submit(() -> stopOwnedBridge(prepared));
    }
    private static boolean activateBridge(Prepared prepared) {
        synchronized(bridgeLock) {
            for(Prepared pending:pendingCleanup)if(pending!=prepared && pending.cleanupRequested && sameBridge(pending,prepared))
                return false;
            prepared.nativeReady=true;readyBridges.add(prepared);pendingCleanup.remove(prepared);
            return true;
        }
    }
    /** Transfer a reused instance only when its receiver commits to taking it. */
    static boolean acceptBridge(Prepared prepared) {
        synchronized(bridgeLock) {
            if(!bridgeUsable(prepared))return false;
            for(Prepared owner:readyBridges)if(owner!=prepared && owner.bridgeOwned && !owner.cleanupRequested && sameBridge(owner,prepared)) {
                prepared.bridgeOwned=true;prepared.bridgeInstanceId=owner.bridgeInstanceId;
                owner.bridgeOwned=false;owner.nativeReady=false;readyBridges.remove(owner);
            }
            return true;
        }
    }
    static void rootChannelConnected() {
        synchronized(bridgeLock) {
            cleanupRetryAttempt=0;
            ++cleanupRetryGeneration;
            if(cleanupRetry!=null){cleanupRetry.cancel(false);cleanupRetry=null;}
            scheduleCleanupRetry(0);
        }
    }
    private static void scheduleCleanupRetry(long delayMs) {
        synchronized(bridgeLock) {
            if(pendingCleanup.isEmpty() || cleanupRetry!=null)return;
            final long generation=cleanupRetryGeneration;
            cleanupRetry=WORK.schedule(() -> {
                try {
                    for(Prepared pending:pendingCleanup)if(pending.cleanupRequested)stopOwnedBridge(pending);
                } finally {
                    synchronized(bridgeLock) {
                        if(generation==cleanupRetryGeneration){cleanupRetry=null;scheduleCleanupRetry();}
                    }
                }
            },delayMs);
        }
    }
    private static void scheduleCleanupRetry() {
        synchronized(bridgeLock) {
            if(cleanupRetry==null && !pendingCleanup.isEmpty() && cleanupRetryAttempt<cleanupRetryDelays.length)
                scheduleCleanupRetry(cleanupRetryDelays[cleanupRetryAttempt++]);
        }
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
            if(enable) {
                for(Prepared pending:pendingCleanup)stopOwnedBridge(pending);
                // A lost response may also mean injection never started or a resident
                // was reused. Keep unmatched records; let the launcher validate reuse.
            }
            prepared.local=DeviceConfig.address(host.call(RootProtocol.Command.LOCAL_ADDRESS),true);
            if(enable && !Thread.currentThread().isInterrupted()) {
                prepared.bridgeInstanceId=java.util.UUID.randomUUID().toString().replace("-","");
                assets.stageBridge(target,adapter,prepared.bridgeInstanceId);
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                preparingBridge=true;
                // This is a possible ownership claim until status proves the exact instance.
                // Retain it before the call: a failed response can still have started a runtime.
                prepared.bridgeOwned=true;
                pendingCleanup.add(prepared);
                String outcome=host.call(RootProtocol.Command.BRIDGE_PREPARE);
                boolean started=false,reused=false;
                for(String line:outcome.split("\\r?\\n")) {
                    if(line.equals("LINKPODS_BRIDGE_STARTED"))started=true;
                    if(line.equals("LINKPODS_BRIDGE_REUSED"))reused=true;
                }
                if(started==reused)throw new IOException("Bridge preparation outcome is unknown");
                if(reused){prepared.bridgeOwned=false;pendingCleanup.remove(prepared);}
                JSONObject status=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
                JSONObject configuration=status.getJSONObject("configuration");
                JSONObject identity=status.getJSONObject("identity");
                if(!status.optString("instance_id","").isEmpty())prepared.lease=new BridgeRuntimeLease(prepared.targetAddress,adapter.profileHash,
                    status.getInt("pid"),status.getString("instance_id"));
                if(started && !prepared.bridgeInstanceId.equals(status.optString("instance_id",""))) {
                    prepared.lease=null;
                    throw new IOException("Started bridge instance did not match this preparation");
                }
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                long age=System.currentTimeMillis()-status.getLong("updated_at_ms");
                // Prologue-resolved bridges intentionally do not write SDP DI identity;
                // require the reference identity only when the agent kept DI enabled.
                boolean diIdentity=status.optBoolean("di_identity",true);
                boolean identityReady=identity.getInt("vendor")==0x4c && identity.getInt("source")==1;
                boolean nativeReady=status.getInt("pid")>0 && age>=0 && age<=15000
                    && status.getJSONObject("state").getString("state").equals("enabled")
                    && configuration.getString("target_address").equals(DeviceConfig.address(target,true))
                    && configuration.getString("profile_sha256").equals(adapter.profileHash)
                    && (identityReady || !diIdentity);
                if(!nativeReady || !activateBridge(prepared))throw new IOException("Bridge runtime is invalid or already stopping");
            }
        }catch(InterruptedException error){
            // Clear interruption for bounded ownership cleanup, then preserve cancellation.
            Thread.interrupted();
            if(preparingBridge && prepared.bridgeOwned)stopOwnedBridge(prepared);
            Thread.currentThread().interrupt();prepared.diagnostic="已取消，未启动交接服务";
            prepared.missing="preparation_cancelled";
        }
        catch(Exception error){
            prepared.nativeReady=false;
            if(preparingBridge && prepared.bridgeOwned)stopOwnedBridge(prepared);
            prepared.missing=enable?"bridge_preparation_failed":"root_preparation_failed";
            prepared.diagnostic=error.getClass().getSimpleName()+": "+error.getMessage();
        }
        if(prepared.access==null)prepared.access=new RootAccess.Result(RootAccess.Status.PREPARATION_FAILED);
        return prepared;
    }
    static boolean stopOwnedBridge(Prepared prepared) {
        markBridgeStopping(prepared);
        if(prepared==null || !prepared.bridgeOwned)return true;
        pendingCleanup.add(prepared);
        RootHost host=RootHost.current();
        try {
            if(host==null)return false;
            if(!host.await(5000))return false;
            JSONObject status=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
            JSONObject configuration=status.getJSONObject("configuration");
            if(prepared.lease==null) {
                // Recover only our preassigned token, never infer ownership from target alone.
                if(prepared.bridgeInstanceId.isEmpty() || !prepared.bridgeInstanceId.equals(status.optString("instance_id",""))
                        || !prepared.targetAddress.equalsIgnoreCase(configuration.getString("target_address"))
                        || !prepared.bridgeProfileHash.equals(configuration.getString("profile_sha256")))return false;
                prepared.lease=new BridgeRuntimeLease(prepared.targetAddress,prepared.bridgeProfileHash,
                    status.getInt("pid"),status.getString("instance_id"));
            }
            if(!prepared.lease.matches(configuration.getString("target_address"),configuration.getString("profile_sha256"),
                    status.getInt("pid"),status.optString("instance_id","")))return false;
            host.call(RootProtocol.Command.BRIDGE_STOP);
            for(int attempt=0;attempt<30;attempt++) {
                JSONObject latest=new JSONObject(host.call(RootProtocol.Command.BRIDGE_STATUS));
                JSONObject latestConfig=latest.getJSONObject("configuration");
                if(!prepared.lease.matches(latestConfig.getString("target_address"),latestConfig.getString("profile_sha256"),
                        latest.getInt("pid"),latest.optString("instance_id","")))return false;
                JSONObject state=latest.getJSONObject("state");
                if(state.getString("state").equals("disabled")&&state.getInt("active_sdp")==0){
                    prepared.bridgeOwned=false;prepared.nativeReady=false;pendingCleanup.remove(prepared);return true;
                }
                Thread.sleep(500);
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(Exception ignored){}
        finally {if(prepared.bridgeOwned)scheduleCleanupRetry();}
        return false;
    }
}
