package local.airpods.handoffprobe;
import java.util.Map;

/** Route setup chatter is not playback; a real media rising edge can reclaim. */
final class PeerRoutingPolicy {
    private Boolean playing;
    void reset() { playing = null; }
    boolean receive(Map<String,Object> fields, boolean localActive) {
        Object state=fields.get("hostStreamingState"), score=fields.get("otherDeviceAudioCategory");
        if (state instanceof String && score instanceof Number) {
            boolean active="YES".equals(state);
            long category=((Number)score).longValue();
            if (!active) { playing=false; return false; }
            if (category<=100) return false;
            boolean rising=Boolean.FALSE.equals(playing);
            playing=true;
            return localActive && (category>=501 || rising);
        }
        if (Boolean.TRUE.equals(fields.get("audioRoutingSetOwnershipToFalse"))) {
            Object reason=fields.get("reason");
            return localActive && ("ReverseBannerTapped".equals(reason) || "ManualRoute".equals(reason));
        }
        return false;
    }
}
