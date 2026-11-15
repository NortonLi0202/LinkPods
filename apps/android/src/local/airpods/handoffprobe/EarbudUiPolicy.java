package local.airpods.handoffprobe;

/** Pure interaction policy shared by the native controls. */
final class EarbudUiPolicy {
    static boolean canWrite(boolean ready, Integer observed, boolean pending) {
        return ready && observed != null && !pending;
    }
    static int nearestSegment(float x, float width, int count) {
        if (width <= 0 || count <= 1) return 0;
        return Math.max(0, Math.min(count - 1, (int) (x / width * count)));
    }
}
