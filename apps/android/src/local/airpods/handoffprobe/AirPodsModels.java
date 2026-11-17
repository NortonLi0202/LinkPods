/*
 * Port of the AirPods model registry from LibrePods commit
 * 89356c08cea3db0b6b54792d9329d09a87cc85fa:
 * android/app/src/main/java/me/kavishdevar/librepods/data/AirPods.kt
 *
 * LibrePods is free software under the GNU General Public License,
 * version 3 or later. This LinkPods adaptation keeps the same exact model
 * numbers and capability sets, but only maps them to controls that already
 * existed in this application. See THIRD_PARTY.md and docs/airpods-models.md.
 */
package local.airpods.handoffprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable registry keyed by the exact upstream A-number.
 *
 * <p>Bluetooth display names are never consulted. A model number is accepted
 * only from the dedicated INFORMATION field by {@link AirPodsInformation}.</p>
 */
public final class AirPodsModels {
    public enum Capability {
        LISTENING_MODE,
        CONVERSATION_AWARENESS,
        STEM_CONFIG,
        HEAD_GESTURES,
        LOUD_SOUND_REDUCTION,
        PPE,
        SLEEP_DETECTION,
        HEARING_AID,
        ADAPTIVE_AUDIO,
        ADAPTIVE_VOLUME,
        SWIPE_FOR_VOLUME,
        HRM
    }

    /** Immutable upstream metadata; mutating accessors return unmodifiable copies. */
    public static final class Model {
        public final String id;
        public final String name;
        public final String displayName;
        public final String manufacturer;
        public final List<String> modelNumbers;
        public final Set<Capability> capabilities;

        private Model(String id,String name,String displayName,String manufacturer,
                List<String> modelNumbers,Set<Capability> capabilities) {
            this.id=id;
            this.name=name;
            this.displayName=displayName;
            this.manufacturer=manufacturer;
            this.modelNumbers=Collections.unmodifiableList(new ArrayList<>(modelNumbers));
            EnumSet<Capability> copy=EnumSet.noneOf(Capability.class);
            copy.addAll(capabilities);
            this.capabilities=Collections.unmodifiableSet(copy);
        }
        public boolean supports(Capability capability) {
            return capability!=null && capabilities.contains(capability);
        }
    }

    private static final List<Model> MODELS;
    private static final Map<String,Model> BY_NUMBER;

    static {
        List<Model> models=Arrays.asList(
            new Model("airpods1","AirPods 1","AirPods","Apple Inc.",
                Arrays.asList("A1523","A1722"),EnumSet.noneOf(Capability.class)),
            new Model("airpods2","AirPods 2","AirPods","Apple Inc.",
                Arrays.asList("A2032","A2031"),EnumSet.noneOf(Capability.class)),
            new Model("airpods3","AirPods 3","AirPods","Apple Inc.",
                Arrays.asList("A2565","A2564"),EnumSet.of(Capability.HEAD_GESTURES)),
            new Model("airpods4","AirPods 4","AirPods","Apple Inc.",
                Arrays.asList("A3053","A3050","A3054"),
                EnumSet.of(Capability.HEAD_GESTURES,Capability.SLEEP_DETECTION,
                    Capability.ADAPTIVE_VOLUME)),
            new Model("airpods4anc","AirPods 4 (ANC)","AirPods","Apple Inc.",
                Arrays.asList("A3056","A3055","A3057"),
                EnumSet.of(Capability.LISTENING_MODE,Capability.CONVERSATION_AWARENESS,
                    Capability.HEAD_GESTURES,Capability.ADAPTIVE_AUDIO,
                    Capability.SLEEP_DETECTION,Capability.ADAPTIVE_VOLUME,
                    Capability.STEM_CONFIG)),
            new Model("airpods5","AirPods 5","AirPods","Apple Inc.",
                Arrays.asList("A3531","A3532","A3533"),
                EnumSet.of(Capability.LISTENING_MODE,Capability.CONVERSATION_AWARENESS,
                    Capability.HEAD_GESTURES,Capability.ADAPTIVE_AUDIO,
                    Capability.SLEEP_DETECTION,Capability.ADAPTIVE_VOLUME,
                    Capability.STEM_CONFIG)),
            new Model("airpods5wcc","AirPods 5 with Wireless Charging Case","AirPods","Apple Inc.",
                Arrays.asList("A3439","A3440","A3441"),
                EnumSet.of(Capability.LISTENING_MODE,Capability.CONVERSATION_AWARENESS,
                    Capability.HEAD_GESTURES,Capability.ADAPTIVE_AUDIO,
                    Capability.SLEEP_DETECTION,Capability.ADAPTIVE_VOLUME,
                    Capability.STEM_CONFIG,Capability.SWIPE_FOR_VOLUME)),
            new Model("pro1","AirPods Pro 1","AirPods Pro","Apple Inc.",
                Arrays.asList("A2084","A2083"),EnumSet.of(Capability.LISTENING_MODE)),
            new Model("pro2-lightning",
                "AirPods Pro 2 with Magsafe Charging Case (Lightning)","AirPods Pro","Apple Inc.",
                Arrays.asList("A2931","A2699","A2698"),
                EnumSet.of(Capability.LISTENING_MODE,Capability.CONVERSATION_AWARENESS,
                    Capability.STEM_CONFIG,Capability.LOUD_SOUND_REDUCTION,
                    Capability.SLEEP_DETECTION,Capability.HEARING_AID,
                    Capability.ADAPTIVE_AUDIO,Capability.ADAPTIVE_VOLUME,
                    Capability.SWIPE_FOR_VOLUME,Capability.HEAD_GESTURES)),
            new Model("pro2-usbc",
                "AirPods Pro 2 with Magsafe Charging Case (USB-C)","AirPods Pro","Apple Inc.",
                Arrays.asList("A3047","A3048","A3049"),
                EnumSet.of(Capability.LISTENING_MODE,Capability.CONVERSATION_AWARENESS,
                    Capability.STEM_CONFIG,Capability.LOUD_SOUND_REDUCTION,
                    Capability.SLEEP_DETECTION,Capability.HEARING_AID,
                    Capability.ADAPTIVE_AUDIO,Capability.ADAPTIVE_VOLUME,
                    Capability.SWIPE_FOR_VOLUME,Capability.HEAD_GESTURES)),
            new Model("pro3","AirPods Pro 3","AirPods Pro","Apple Inc.",
                Arrays.asList("A3063","A3064","A3065"),
                EnumSet.of(Capability.LISTENING_MODE,Capability.CONVERSATION_AWARENESS,
                    Capability.HEAD_GESTURES,Capability.STEM_CONFIG,
                    Capability.LOUD_SOUND_REDUCTION,Capability.PPE,
                    Capability.SLEEP_DETECTION,Capability.HEARING_AID,
                    Capability.ADAPTIVE_AUDIO,Capability.ADAPTIVE_VOLUME,
                    Capability.SWIPE_FOR_VOLUME,Capability.HRM))
        );
        MODELS=Collections.unmodifiableList(new ArrayList<>(models));
        Map<String,Model> byNumber=new LinkedHashMap<>();
        for(Model model:models) for(String number:model.modelNumbers) {
            if(byNumber.put(number,model)!=null)
                throw new IllegalStateException("duplicate model number: "+number);
        }
        BY_NUMBER=Collections.unmodifiableMap(byNumber);
    }

    private AirPodsModels() {}

    public static List<Model> all() { return MODELS; }

    /** Exact, case-sensitive lookup; unknown model numbers stay unknown. */
    public static Model find(String modelNumber) {
        return modelNumber==null ? null : BY_NUMBER.get(modelNumber);
    }

    /**
     * Model capability gate for an existing LinkPods scalar control.
     *
     * <p>A null model is an unknown AirPods and keeps the pre-existing
     * report-driven behavior. Only settings already implemented by LinkPods
     * are mapped; a known model never gains an unrelated feature by inference.</p>
     */
    public static boolean supportsSetting(Model model,int settingId) {
        if(model==null) return isKnownSetting(settingId);
        switch(settingId) {
            case EarbudCommands.LISTENING_MODE:
            case EarbudCommands.ONE_BUD_ANC:
                return model.supports(Capability.LISTENING_MODE);
            case EarbudCommands.CONVERSATION_AWARENESS:
                return model.supports(Capability.CONVERSATION_AWARENESS);
            case EarbudCommands.PERSONALIZED_VOLUME:
                return model.supports(Capability.ADAPTIVE_VOLUME);
            case EarbudCommands.VOLUME_SWIPE:
            case EarbudCommands.SWIPE_LENGTH:
                return model.supports(Capability.SWIPE_FOR_VOLUME);
            case EarbudCommands.EAR_DETECTION:
            case EarbudCommands.MICROPHONE:
            case EarbudCommands.CHIME_VOLUME:
                // These basic controls have no upstream capability bit; retaining
                // the existing report-driven behavior is conservative, not a claim
                // that a generation advertises a new feature.
                return true;
            default:
                return false;
        }
    }

    /**
     * Model gate for a listening mode. The caller must still require an actual
     * observation of the mode (or of a core mode for ANC/transparency).
     */
    public static boolean supportsListeningMode(Model model,int mode) {
        if(model==null)
            return mode==EarbudCommands.MODE_OFF || mode==EarbudCommands.MODE_ANC
                || mode==EarbudCommands.MODE_TRANSPARENCY
                || mode==EarbudCommands.MODE_ADAPTIVE;
        if(!model.supports(Capability.LISTENING_MODE)) return false;
        if(mode==EarbudCommands.MODE_ADAPTIVE) return model.supports(Capability.ADAPTIVE_AUDIO);
        return mode==EarbudCommands.MODE_OFF || mode==EarbudCommands.MODE_ANC
            || mode==EarbudCommands.MODE_TRANSPARENCY;
    }

    /** Existing scalar ids; owners still require a current-session observation. */
    public static boolean isKnownSetting(int settingId) {
        return settingId==EarbudCommands.LISTENING_MODE
            || settingId==EarbudCommands.ONE_BUD_ANC
            || settingId==EarbudCommands.EAR_DETECTION
            || settingId==EarbudCommands.VOLUME_SWIPE
            || settingId==EarbudCommands.PERSONALIZED_VOLUME
            || settingId==EarbudCommands.CONVERSATION_AWARENESS
            || settingId==EarbudCommands.MICROPHONE
            || settingId==EarbudCommands.CHIME_VOLUME
            || settingId==EarbudCommands.SWIPE_LENGTH;
    }
}
