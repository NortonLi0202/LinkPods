package local.airpods.handoffprobe;

/** A2DP follows requested ownership, including an immediate replay during disconnect. */
final class AudioLinkPolicy {
    enum Action { NONE, CONNECT, DISCONNECT }
    private Boolean wanted;
    boolean isWanted() { return Boolean.TRUE.equals(wanted); }
    Action want(boolean active, int state) { wanted = active; return update(state); }
    Action update(int state) {
        if (wanted == null) return Action.NONE;
        if (wanted && state == 0) return Action.CONNECT;
        if (!wanted && (state == 1 || state == 2)) return Action.DISCONNECT;
        return Action.NONE;
    }
}
