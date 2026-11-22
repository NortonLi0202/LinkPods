package local.airpods.handoffprobe;

/** A2DP follows requested ownership, including an immediate replay during disconnect. */
final class AudioLinkPolicy {
    enum Action { NONE, CONNECT, DISCONNECT }
    private Boolean wanted;
    private boolean disconnectedObserved;
    boolean isWanted() { return Boolean.TRUE.equals(wanted); }
    Action want(boolean active, int state) {
        wanted = active;
        disconnectedObserved = false;
        return update(state);
    }
    void reset() {
        wanted = null;
        disconnectedObserved = false;
    }
    void clear() { reset(); }
    Action update(int state) {
        if (wanted == null) return Action.NONE;
        if (wanted) {
            if (state == 0) return Action.CONNECT;
            return Action.NONE;
        }
        if (state == 0) {
            disconnectedObserved = true;
            return Action.NONE;
        }
        if (disconnectedObserved && state == 1) {
            // A fresh user-initiated CONNECTING after disconnect completes clears stale yield suppression.
            reset();
            return Action.NONE;
        }
        if (state == 1 || state == 2) return Action.DISCONNECT;
        return Action.NONE;
    }
}
