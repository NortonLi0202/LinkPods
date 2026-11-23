package local.airpods.handoffprobe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Playback-state edges distinguish a replay from route/configuration churn. */
final class RoutingPolicy {
    enum Action { NONE, TAKE, RELEASE, PAUSE }
    enum TakeDecision { WAIT, TAKE, SKIP }
    private final Map<String, Boolean> playing = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    void seed(String player, int state) { playing.put(player, state == 3); }
    void reseed(Map<String, Integer> states) {
        playing.clear();
        states.forEach(this::seed);
        pending.removeIf(player -> !Boolean.TRUE.equals(playing.get(player)));
    }
    Action onPlayback(String player, int state) {
        return onPlayback(player, state, true);
    }
    Action onPlayback(String player, int state, boolean allowTake) {
        if (state == 0 || state == 6 || state == 8 || state == 4 || state == 5) return Action.NONE;
        boolean active = state == 3;
        boolean previous = Boolean.TRUE.equals(playing.put(player, active));
        if (!active) pending.remove(player);
        if (active && !previous && allowTake) pending.add(player);
        if (!active || previous) return Action.NONE;
        return allowTake ? Action.TAKE : Action.PAUSE;
    }
    boolean hasPlayback() { return playing.containsValue(Boolean.TRUE); }
    boolean isPending() { return !pending.isEmpty(); }
    void reset() { playing.clear(); pending.clear(); }
    boolean needsClaim(boolean owned, boolean localMedia) { return !owned || !localMedia; }
    void remove(String player) {
        playing.remove(player);
        pending.remove(player);
    }
    boolean consumeTake(boolean ready, boolean hasPeer) {
        return evaluateTake(ready, hasPeer, true) == TakeDecision.TAKE;
    }

    /**
     * Evaluates a pending playback takeover once control session and peer are ready.
     * Consumes the pending edge even when target output is not confirmed so speaker
     * playback never triggers a delayed takeover later.
     */
    TakeDecision evaluateTake(boolean ready, boolean hasPeer, boolean targetOutputConfirmed) {
        if (!isPending() || !ready || !hasPeer) return TakeDecision.WAIT;
        pending.clear();
        return targetOutputConfirmed ? TakeDecision.TAKE : TakeDecision.SKIP;
    }
    void clearPendingTake() { pending.clear(); }
    Action onRemoteRequest() { clearPendingTake(); return Action.RELEASE; }
}
