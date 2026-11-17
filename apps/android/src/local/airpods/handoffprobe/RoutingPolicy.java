package local.airpods.handoffprobe;

import java.util.HashMap;
import java.util.Map;

/** Playback-state edges distinguish a replay from route/configuration churn. */
final class RoutingPolicy {
    enum Action { NONE, TAKE, RELEASE }
    private final Map<String, Boolean> playing = new HashMap<>();
    private boolean pending;
    void seed(String player, int state) { playing.put(player, state == 3); }
    Action onPlayback(String player, int state) {
        if (state == 0 || state == 6 || state == 8 || state == 4 || state == 5) return Action.NONE;
        boolean active = state == 3;
        boolean previous = Boolean.TRUE.equals(playing.put(player, active));
        if (active && !previous) pending = true;
        if (!hasPlayback()) pending = false;
        return active && !previous ? Action.TAKE : Action.NONE;
    }
    boolean hasPlayback() { return playing.containsValue(Boolean.TRUE); }
    void reset() { playing.clear(); pending = false; }
    boolean needsClaim(boolean owned, boolean localMedia) { return !owned || !localMedia; }
    void remove(String player) {
        playing.remove(player);
        if (!hasPlayback()) pending = false;
    }
    boolean consumeTake(boolean ready, boolean hasPeer) {
        if (!pending || !ready || !hasPeer || !hasPlayback()) return false;
        pending = false;
        return true;
    }
    Action onRemoteRequest() { pending = false; return Action.RELEASE; }
}
