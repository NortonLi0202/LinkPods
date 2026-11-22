package local.airpods.handoffprobe;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.SystemClock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owns the target-bound AACP probe, session generation, and setting confirmation lifecycle. */
final class ControlSession {
    interface ProbeFactory {
        AapProbe create(BluetoothDevice device, AapProbe.Logger logger, AapProbe.Listener listener);
    }

    interface Listener {
        void onReady(long connectionGeneration, String address);
        void onPacket(byte[] data);
        void onEnded(long connectionGeneration, String address, boolean compatibilityRequired);
        default void onStateChanged() {}
    }

    private final Handler handler;
    private final LongSupplier clock;
    private final ProbeFactory probeFactory;
    private final AapProbe.Logger logger;
    private final Listener listener;
    private final EarbudCommands.State earbudState = new EarbudCommands.State();

    private String targetAddress = "";
    private volatile long sessionGeneration;
    private volatile AapProbe probe;
    private volatile boolean ready;
    private volatile boolean stopped;

    ControlSession(Handler handler, AapProbe.Logger logger, Listener listener) {
        this(handler, SystemClock::elapsedRealtime, AapProbe::new, logger, listener);
    }

    ControlSession(
            Handler handler,
            LongSupplier clock,
            ProbeFactory probeFactory,
            AapProbe.Logger logger,
            Listener listener) {
        this.handler = handler;
        this.clock = clock;
        this.probeFactory = probeFactory;
        this.logger = logger;
        this.listener = listener;
    }

    void begin(String address) {
        targetAddress = address == null ? "" : address;
        earbudState.begin(targetAddress);
    }

    boolean isReady() {
        return ready;
    }

    boolean isConnectingOrConnected() {
        return probe != null;
    }

    EarbudSnapshot snapshot(String address) {
        return earbudState.snapshot(address, clock.getAsLong());
    }

    void connect(BluetoothDevice device, final String address, final long connectionGeneration) {
        if (stopped || device == null || probe != null || address == null || address.isEmpty()) return;
        if (!address.equalsIgnoreCase(targetAddress)) {
            begin(address);
        }
        final long generation = ++sessionGeneration;
        final AapProbe[] created = new AapProbe[1];
        created[0] = probeFactory.create(device, logger, new AapProbe.Listener() {
            private volatile boolean compatibilityRequired;

            @Override public void failure(AapProbe.Failure failure) {
                if (failure == AapProbe.Failure.COMPATIBILITY_REQUIRED) {
                    compatibilityRequired = true;
                }
            }

            private boolean valid() {
                return !stopped && generation == sessionGeneration
                        && created[0] == probe && address.equalsIgnoreCase(targetAddress);
            }

            @Override public void ready() {
                handler.post(() -> {
                    if (!valid()) return;
                    earbudState.connected();
                    ready = true;
                    listener.onReady(connectionGeneration, address);
                });
            }

            @Override public void packet(byte[] data) {
                handler.post(() -> {
                    if (!valid()) return;
                    handlePacket(address, data);
                    listener.onPacket(data);
                });
            }

            @Override public void ended() {
                handler.post(() -> {
                    if (!valid()) return;
                    earbudState.disconnected();
                    ready = false;
                    probe = null;
                    listener.onEnded(connectionGeneration, address, compatibilityRequired);
                });
            }
        });
        probe = created[0];
        probe.start();
    }

    void reset() {
        final AapProbe previous = probe;
        if (previous != null) {
            ++sessionGeneration;
            probe = null;
            ready = false;
            earbudState.disconnected();
            previous.stop();
        }
    }

    void stop() {
        stopped = true;
        ++sessionGeneration;
        ready = false;
        earbudState.disconnected();
        final AapProbe active = probe;
        probe = null;
        if (active != null) active.stop();
    }

    boolean setSetting(String address, int id, int value) {
        if (stopped) return false;
        final long generation = sessionGeneration;
        final AapProbe active = probe;
        if (active == null) return false;
        final EarbudCommands.PendingWrite write =
                earbudState.queueRequest(address, id, value, clock.getAsLong());
        if (write == null) return false;
        boolean queued = handler.post(() -> {
            if (!isCurrentSession(generation, active)) return;
            active.sendPacketAsync(write.packet, sent -> handler.post(() -> {
                if (!isCurrentSession(generation, active)) return;
                if (!sent) {
                    earbudState.failed(id, write.token);
                    listener.onStateChanged();
                }
                else {
                    earbudState.sent(id, write.token);
                    active.requestState();
                }
                logger.write("EARBUD_SETTING id=" + id + " sent=" + sent + " confirmed=false");
            }));
        });
        if (!queued && generation == sessionGeneration) {
            earbudState.failed(id, write.token);
            listener.onStateChanged();
        }
        return queued;
    }

    boolean refreshState(String address) {
        if (stopped || !earbudState.snapshot(address, clock.getAsLong()).ready) return false;
        final long generation = sessionGeneration;
        final AapProbe active = probe;
        if (active == null) return false;
        return handler.post(() -> {
            if (isCurrentSession(generation, active)) {
                active.requestState();
            }
        });
    }

    boolean sendPacket(byte[] packet, BooleanSupplier allowed, Consumer<Boolean> done) {
        if (stopped) return false;
        final long generation = sessionGeneration;
        final AapProbe active = probe;
        if (active == null) return false;
        active.sendPacketAsync(
                packet,
                () -> isCurrentSession(generation, active) && allowed.getAsBoolean(),
                sent -> handler.post(() -> {
                    if (isCurrentSession(generation, active) && allowed.getAsBoolean()) {
                        done.accept(sent);
                    }
                }));
        return true;
    }

    private boolean isCurrentSession(long generation, AapProbe active) {
        return !stopped && generation == sessionGeneration && active == probe;
    }

    private void handlePacket(String address, byte[] data) {
        boolean accepted = earbudState.receive(data);
        byte[] statePacket = AapPackets.normalize(data);
        if (AapPackets.isEarbudStateReport(statePacket)) {
            logger.write("EARBUD_STATE opcode=" + (statePacket[4] & 255)
                    + " len=" + statePacket.length + " accepted=" + accepted);
        }
        if (accepted && AapPackets.isInformationReport(statePacket)) {
            EarbudSnapshot identity = earbudState.snapshot(address, clock.getAsLong());
            logger.write("EARBUD_MODEL " + identity.modelNumber + " known=" + identity.modelKnown());
        }
    }
}
