package local.airpods.handoffprobe;
import java.util.Map;

/** Route setup chatter is not playback; a real media or call rising edge can reclaim. */
final class PeerRoutingPolicy {
    private Boolean playing;
    private boolean callActive;
    void reset() { playing = null; callActive = false; }
    boolean receive(Map<String,Object> fields, boolean localActive) {
        Object state=fields.get("hostStreamingState"), score=fields.get("otherDeviceAudioCategory");
        if (state instanceof String && score instanceof Number) {
            long category=((Number)score).longValue();
            // Route-only reports (YES or NO) must neither arm nor clear the peer playback edge.
            if (category<=100) return false;
            if ("NO".equals(state)) { playing=false; callActive=false; return false; }
            if (!"YES".equals(state)) return false;
            boolean isCall = category >= 501;
            boolean rising = Boolean.FALSE.equals(playing);
            boolean callRising = Boolean.TRUE.equals(playing) && isCall && !callActive;
            playing = true;
            callActive = isCall;
            return localActive && (rising || callRising);
        }
        if (Boolean.TRUE.equals(fields.get("audioRoutingSetOwnershipToFalse"))) {
            Object reason=fields.get("reason");
            return localActive && ("ReverseBannerTapped".equals(reason) || "ManualRoute".equals(reason));
        }
        return false;
    }
}
