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
    public final AirPodsModels.Model model;
    public final String modelNumber,reportedName,version1,version2;
    private final Set<Integer> listeningModes;

    EarbudSnapshot(String target,boolean ready,Integer left,Integer right,Integer box,String status,
            Map<Integer,Integer> settings,Set<Integer> pending,Set<Integer> modes,
            AirPodsModels.Model model,String modelNumber,String reportedName,
            String version1,String version2) {
        targetAddress=target==null ? "" : target;
        this.ready=ready;leftBattery=left;rightBattery=right;caseBattery=box;this.status=status;
        this.settings=Collections.unmodifiableMap(new HashMap<>(settings));
        this.pending=Collections.unmodifiableSet(new HashSet<>(pending));
        listeningModes=Collections.unmodifiableSet(new HashSet<>(modes));
        this.model=model;
        this.modelNumber=modelNumber==null ? "" : modelNumber;
        this.reportedName=reportedName==null ? "" : reportedName;
        this.version1=version1==null ? "" : version1;
        this.version2=version2==null ? "" : version2;
    }
    static EarbudSnapshot empty(String target,String reason) {
        return new EarbudSnapshot(target,false,null,null,null,reason,Collections.emptyMap(),
            Collections.emptySet(),Collections.emptySet(),null,"","","","");
    }
    public Integer value(int id) { return settings.get(id); }
    public Boolean enabled(int id) {
        Integer value=settings.get(id);
        return !EarbudCommands.isBoolean(id) || value==null ? null : Boolean.valueOf(value==1);
    }
    public boolean modelKnown() { return model!=null; }
    public boolean isPro3() { return model!=null && "pro3".equals(model.id); }
    public String modelIdentityLabel() {
        if(model!=null) return model.name+" · "+modelNumber;
        if(!modelNumber.isEmpty()) return "未识别型号（"+modelNumber+"）";
        return "型号尚未识别";
    }
    /** Model-only eligibility; callers still require a current observation to write. */
    public boolean supportsSetting(int id) { return AirPodsModels.supportsSetting(model,id); }
    public boolean supportsListeningMode(int mode) {
        return ready && AirPodsModels.supportsListeningMode(model,mode)
            && listeningModes.contains(mode);
    }
}
