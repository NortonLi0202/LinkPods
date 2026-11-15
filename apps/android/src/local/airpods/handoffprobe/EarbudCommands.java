package local.airpods.handoffprobe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Restricted scalar AACP codec; protocol reference: pinned PodTide/LibrePods in THIRD_PARTY.md. */
public final class EarbudCommands {
    public static final int LISTENING_MODE=0x0d, MODE_OFF=1, MODE_ANC=2,
        MODE_TRANSPARENCY=3, MODE_ADAPTIVE=4;
    public static final int EAR_DETECTION=0x0a, ONE_BUD_ANC=0x1b, VOLUME_SWIPE=0x25,
        PERSONALIZED_VOLUME=0x26, CONVERSATION_AWARENESS=0x28;
    public static final int MICROPHONE=0x01, CHIME_VOLUME=0x1f, SWIPE_LENGTH=0x23;
    private EarbudCommands() {}

    // Both upstream implementations use Apple true=1 / false=2.
    public static int booleanValue(boolean value) { return value ? 1 : 2; }
    static boolean isBoolean(int id) {
        return id==EAR_DETECTION || id==ONE_BUD_ANC || id==VOLUME_SWIPE
            || id==PERSONALIZED_VOLUME || id==CONVERSATION_AWARENESS;
    }
    private static boolean valid(int id,int value) {
        if(isBoolean(id)) return value==1 || value==2;
        if(id==LISTENING_MODE) return value>=1 && value<=4;
        if(id==MICROPHONE || id==SWIPE_LENGTH) return value>=0 && value<=2;
        return id==CHIME_VOLUME && value>=15 && value<=100;
    }
    // Package private: the UI can submit settings, never arbitrary packet bytes.
    static byte[] encode(int id, int value) {
        if(!valid(id,value)) return null;
        return new byte[]{4,0,4,0,9,0,(byte)id,(byte)value,0,0,0};
    }

    /** Session-local observed state, independently testable without Android or a socket. */
    static final class State {
        private static final long CONFIRM_TIMEOUT_MS=5000;
        private String target="";
        private boolean ready;
        private Integer left,right,box;
        private String status="控制服务未运行";
        private final Map<Integer,Integer> observed=new HashMap<>();
        private final Map<Integer,Request> pending=new HashMap<>();
        private final Set<Integer> observedModes=new HashSet<>();
        private static final class Request {
            final int value; final long deadline;
            Request(int value,long deadline) { this.value=value;this.deadline=deadline; }
        }
        synchronized void begin(String address) {
            target=address==null ? "" : address;
            ready=false;left=null;right=null;box=null;
            observed.clear();pending.clear();observedModes.clear();
            status="正在连接耳机控制通道";
        }
        synchronized void connected() {
            // Disconnected snapshots may retain old readings, but a new live session must
            // wait for fresh component reports before presenting battery values as current.
            left=null;right=null;box=null;
            ready=true;status="控制通道已连接，等待耳机状态";
        }
        synchronized void disconnected() {
            ready=false;observed.clear();pending.clear();observedModes.clear();
            status="控制通道已断开，电量为上次读数";
        }
        synchronized boolean receive(byte[] packet) {
            if(!ready || packet==null || packet.length<6 || packet[0]!=4 || packet[1]!=0
                || packet[2]!=4 || packet[3]!=0 || packet[5]!=0) return false;
            if(packet[4]==9) {
                // Scalar control messages carry exactly 5 payload bytes; multi-byte settings
                // are deliberately outside this whitelist, so unknown trailing data is rejected.
                if(packet.length!=11 || packet[8]!=0 || packet[9]!=0 || packet[10]!=0) return false;
                int id=packet[6]&255,value=packet[7]&255;
                if(!valid(id,value)) return false;
                observed.put(id,value);
                if(id==LISTENING_MODE) observedModes.add(value);
                Request request=pending.get(id);
                if(request!=null && request.value==value) {
                    pending.remove(id);status="耳机已回报设置";
                }
                return true;
            }
            if(packet[4]!=4 || packet.length<7) return false;
            int count=packet[6]&255;
            if(count==0 || packet.length!=7+count*5) return false;
            Set<Integer> seen=new HashSet<>();
            for(int offset=7;offset<packet.length;offset+=5) {
                int component=packet[offset]&255;
                if((component!=4 && component!=2 && component!=8) || !seen.add(component)) return false;
            }
            for(int offset=7;offset<packet.length;offset+=5) {
                int component=packet[offset]&255,percent=packet[offset+2]&255,charging=packet[offset+3]&255;
                Integer value=percent<=100 && charging!=4 ? Integer.valueOf(percent) : null;
                if(component==4) left=value;
                else if(component==2) right=value;
                else box=value;
            }
            return true;
        }
        synchronized byte[] queue(String address,int id,int value,long now) {
            expire(now);
            if(!matches(address) || !ready || !observed.containsKey(id) || pending.containsKey(id)) return null;
            if(id==LISTENING_MODE && !supportedMode(value)) return null;
            byte[] bytes=encode(id,value);
            if(bytes==null) return null;
            pending.put(id,new Request(value,now+CONFIRM_TIMEOUT_MS));
            status="等待耳机确认设置";
            return bytes;
        }
        synchronized void failed(int id) {
            if(pending.remove(id)!=null) status="设置未发送，未确认";
        }
        private boolean matches(String address) {
            return address!=null && !target.isEmpty() && target.equalsIgnoreCase(address);
        }
        private boolean supportedMode(int mode) {
            return observed.containsKey(LISTENING_MODE) &&
                (mode==MODE_ANC || mode==MODE_TRANSPARENCY || observedModes.contains(mode));
        }
        private void expire(long now) {
            Iterator<Request> iterator=pending.values().iterator();
            while(iterator.hasNext()) if(now>=iterator.next().deadline) {
                iterator.remove();status="设置已发送，耳机未确认";
            }
        }
        synchronized EarbudSnapshot snapshot(String address,long now) {
            if(!matches(address)) return EarbudSnapshot.empty(address,"未连接此设备的控制通道");
            expire(now);
            Set<Integer> modes=new HashSet<>(observedModes);
            if(observed.containsKey(LISTENING_MODE)) { modes.add(MODE_ANC);modes.add(MODE_TRANSPARENCY); }
            return new EarbudSnapshot(target,ready,left,right,box,status,observed,pending.keySet(),modes);
        }
    }
}
