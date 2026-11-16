package local.airpods.handoffprobe;

/** Pure connection decisions. Returned actions are the only authority for service side effects. */
public final class ConnectionPolicy {
    public enum Phase { IDLE, DIRECT_CONNECTING, DIRECT_READY, BRIDGE_PREPARING, BRIDGE_CONNECTING, BRIDGE_READY, RECONNECTING, FAILED, STOPPED }
    public enum Action { NONE, CONNECT_DIRECT, PREPARE_BRIDGE, CONNECT_BRIDGE, RECONNECT }
    public static final class Snapshot {
        public final String targetAddress,description;
        public final Phase phase;
        Snapshot(String target,Phase phase,String description){this.targetAddress=target;this.phase=phase;this.description=description;}
    }
    private String target="",description="控制服务未运行";
    private Phase phase=Phase.IDLE;
    private long generation;
    private boolean fallbackUsed,directWorked,bridge,sessionReady;
    public synchronized long generation(){return generation;}
    public synchronized boolean fallbackUsed(){return fallbackUsed;}
    public synchronized Snapshot snapshot(String address){
        return address!=null && address.equalsIgnoreCase(target)
            ? new Snapshot(target,phase,description) : new Snapshot(address,Phase.IDLE,"此设备没有活动控制会话");
    }
    public synchronized Action start(String address,boolean preparedBridge){
        boolean upgrade=preparedBridge && !bridge && address!=null && address.equalsIgnoreCase(target)
            && (phase==Phase.DIRECT_CONNECTING || phase==Phase.DIRECT_READY
                || phase==Phase.RECONNECTING || phase==Phase.BRIDGE_PREPARING);
        if(!upgrade && phase!=Phase.IDLE && phase!=Phase.FAILED && phase!=Phase.STOPPED)return Action.NONE;
        if(!upgrade)directWorked=false;
        target=address;++generation;bridge=preparedBridge;sessionReady=false;
        phase=bridge?Phase.BRIDGE_CONNECTING:Phase.DIRECT_CONNECTING;
        description=bridge?"已准备桥接，等待控制通道握手；交接未验证":"直接连接控制通道，无需 Root；交接未验证";
        return bridge?Action.CONNECT_BRIDGE:Action.CONNECT_DIRECT;
    }
    private boolean accepts(long expected,String address){return generation==expected && address!=null && address.equalsIgnoreCase(target);}
    public synchronized Action ready(long expected,String address){
        if(!accepts(expected,address) || (phase!=Phase.DIRECT_CONNECTING && phase!=Phase.BRIDGE_CONNECTING))return Action.NONE;
        sessionReady=true;if(!bridge)directWorked=true;
        phase=bridge?Phase.BRIDGE_READY:Phase.DIRECT_READY;
        description=bridge?"桥接控制通道已连接；音频交接未验证":"直连控制通道已连接，未请求 Root；音频交接未验证";
        return Action.NONE;
    }
    public synchronized Action ended(long expected,String address){return ended(expected,address,false);}
    public synchronized Action ended(long expected,String address,boolean compatibilityRequired){
        if(!accepts(expected,address) || (phase!=Phase.DIRECT_CONNECTING && phase!=Phase.BRIDGE_CONNECTING && phase!=Phase.DIRECT_READY && phase!=Phase.BRIDGE_READY))return Action.NONE;
        if(sessionReady || (!bridge && directWorked)){
            phase=Phase.RECONNECTING;description=bridge?"桥接控制通道断开，等待重连":"直连控制通道断开，等待直接重连（不请求 Root）";
            return Action.RECONNECT;
        }
        if(!bridge && compatibilityRequired && !fallbackUsed){
            fallbackUsed=true;phase=Phase.BRIDGE_PREPARING;
            description="直连握手失败，尝试匹配适配器与已有 Root；不运行临时 Root";
            return Action.PREPARE_BRIDGE;
        }
        fail(bridge?"桥接后的控制通道握手失败":fallbackUsed?"直连握手失败；本次服务的已有 Root 回退已用尽":"直连握手未完成；可重试或手动准备桥接，未请求 Root");return Action.NONE;
    }
    public synchronized Action retry(long expected,String address){
        if(!accepts(expected,address) || phase!=Phase.RECONNECTING)return Action.NONE;
        ++generation;sessionReady=false;phase=bridge?Phase.BRIDGE_CONNECTING:Phase.DIRECT_CONNECTING;
        description=bridge?"正在重连桥接控制通道":"正在直接重连控制通道，不请求 Root";
        return bridge?Action.CONNECT_BRIDGE:Action.CONNECT_DIRECT;
    }
    public synchronized Action bridgePrepared(long expected,String address,boolean valid,String adapterId,String missing,String diagnostic){
        if(!accepts(expected,address) || phase!=Phase.BRIDGE_PREPARING)return Action.NONE;
        if(!valid || adapterId==null || adapterId.isEmpty()){
            String reason=missing==null?"":missing;
            if(reason.isEmpty())reason=adapterId==null || adapterId.isEmpty()?"matching_bridge_adapter_missing":"bridge_runtime_not_ready";
            fail(explain(reason)+(diagnostic==null || diagnostic.isEmpty()?"":"："+diagnostic));return Action.NONE;
        }
        bridge=true;sessionReady=false;++generation;phase=Phase.BRIDGE_CONNECTING;
        description="桥接适配器 "+adapterId+" 已准备，等待控制通道握手；交接未验证";
        return Action.CONNECT_BRIDGE;
    }
    public synchronized void unavailable(long expected,String address,String reason){
        if(accepts(expected,address) && (phase==Phase.DIRECT_CONNECTING || phase==Phase.BRIDGE_CONNECTING))fail(reason);
    }
    private static String explain(String reason){
        switch(reason){
            case "matching_bridge_adapter_missing":return "缺少与当前蓝牙模块匹配的桥接适配器";
            case "root_permission_denied":return "已有 Root 授权被拒绝";
            case "root_not_available":return "没有可用的已有 Root";
            case "root_status_unknown":return "无法确认已有 Root 状态";
            case "bridge_runtime_not_ready":return "桥接运行状态未验证通过";
            case "bridge_preparation_failed":return "桥接准备失败";
            case "root_preparation_failed":return "已有 Root 准备失败";
            case "preparation_cancelled":return "桥接准备已取消";
            default:return reason;
        }
    }
    private void fail(String reason){phase=Phase.FAILED;description=reason+"；交接未验证";}
    public synchronized void stop(){++generation;phase=Phase.STOPPED;description="控制服务已停止";}
}
