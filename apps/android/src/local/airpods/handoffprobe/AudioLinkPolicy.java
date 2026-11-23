package local.airpods.handoffprobe;

/**
 * A2DP follows requested ownership. Once yielded, every passive CONNECTING/CONNECTED is torn
 * down until an explicit switch back (manual handoff, confirmed new playback or mode change),
 * because Android cannot tell an earbud-initiated reconnect from a user click.
 */
final class AudioLinkPolicy {
    enum Action { NONE, CONNECT, DISCONNECT }
    private enum State { OBSERVING, LOCAL, YIELDED }
    private State intent = State.OBSERVING;
    boolean isWanted() { return intent == State.LOCAL; }
    boolean isYielded() { return intent == State.YIELDED; }
    Action want(boolean active, int state) {
        intent = active ? State.LOCAL : State.YIELDED;
        return update(state);
    }
    void reset() { intent = State.OBSERVING; }
    void clear() { reset(); }
    Action update(int state) {
        if (isWanted() && state == 0) return Action.CONNECT;
        if (isYielded() && (state == 1 || state == 2)) return Action.DISCONNECT;
        return Action.NONE;
    }
}
