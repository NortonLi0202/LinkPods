package local.airpods.handoffprobe;

import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/** Immutable copy of earbud-reported state; null is unknown, pending never implies success. */
public final class EarbudSnapshot {
    public final String targetAddress;
    public final boolean ready;
    public final Integer leftBattery,rightBattery,caseBattery;
    public final String status;
    public final Map<Integer,Integer> settings;
    public final Set<Integer> pending;
    private final Set<Integer> listeningModes;

    EarbudSnapshot(String target,boolean ready,Integer left,Integer right,Integer box,String status,
            Map<Integer,Integer> settings,Set<Integer> pending,Set<Integer> modes) {
        targetAddress=target==null ? "" : target;
        this.ready=ready;leftBattery=left;rightBattery=right;caseBattery=box;this.status=status;
        this.settings=Collections.unmodifiableMap(new HashMap<>(settings));
        this.pending=Collections.unmodifiableSet(new HashSet<>(pending));
        listeningModes=Collections.unmodifiableSet(new HashSet<>(modes));
    }
    static EarbudSnapshot empty(String target,String reason) {
        return new EarbudSnapshot(target,false,null,null,null,reason,Collections.emptyMap(),Collections.emptySet(),Collections.emptySet());
    }
    public Integer value(int id) { return settings.get(id); }
    public Boolean enabled(int id) {
        Integer value=settings.get(id);
        return !EarbudCommands.isBoolean(id) || value==null ? null : Boolean.valueOf(value==1);
    }
    public boolean supportsListeningMode(int mode) { return ready && listeningModes.contains(mode); }
}
