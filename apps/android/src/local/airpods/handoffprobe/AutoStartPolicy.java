package local.airpods.handoffprobe;

/** Pure state machine for target-earbud auto-start and debounced auto-stop decisions. */
public final class AutoStartPolicy {
    public enum ConnectDecision {
        IGNORE,
        START_AUTO_HANDOFF
    }

    private boolean userSuppressed;
    private boolean autoStarted;

    public synchronized ConnectDecision onTargetConnected(
            String connectedAddress,
            String rememberedTarget,
            boolean autoStartOptIn,
            boolean hasBluetoothPermission,
            boolean serviceRunning) {
        if (!autoStartOptIn || !hasBluetoothPermission || userSuppressed || serviceRunning) {
            return ConnectDecision.IGNORE;
        }
        if (connectedAddress == null || rememberedTarget == null || rememberedTarget.isEmpty()) {
            return ConnectDecision.IGNORE;
        }
        if (!connectedAddress.equalsIgnoreCase(rememberedTarget)) {
            return ConnectDecision.IGNORE;
        }
        autoStarted = true;
        return ConnectDecision.START_AUTO_HANDOFF;
    }

    public synchronized void onUserStoppedOrDowngraded() {
        userSuppressed = true;
        autoStarted = false;
    }

    public synchronized void onManualAutoHandoffEnabled() {
        userSuppressed = false;
    }

    public synchronized void onAutoStartRejected() {
        autoStarted = false;
    }

    /** Returns true if an auto-started service should be stopped after disconnect debounce. */
    public synchronized boolean shouldAutoStopOnDisconnect(boolean anyProfileConnected) {
        if (anyProfileConnected) {
            return false;
        }
        userSuppressed = false;
        if (!autoStarted) {
            return false;
        }
        autoStarted = false;
        return true;
    }

    public synchronized boolean isAutoStarted() {
        return autoStarted;
    }

    public synchronized boolean isUserSuppressed() {
        return userSuppressed;
    }
}
