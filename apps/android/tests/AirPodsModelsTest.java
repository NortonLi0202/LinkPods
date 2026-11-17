package local.airpods.handoffprobe;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/** Exact pinned LibrePods model registry and capability-to-existing-control mapping. */
public final class AirPodsModelsTest {
    private static void expect(boolean yes,String message) {
        if(!yes) throw new AssertionError(message);
    }
    private static void aliases(String id,String... numbers) {
        AirPodsModels.Model model=AirPodsModels.find(numbers[0]);
        expect(model!=null,"registry must contain "+numbers[0]);
        expect(id.equals(model.id),"model id for "+numbers[0]);
        expect(model.modelNumbers.equals(Arrays.asList(numbers)),"exact alias order for "+id);
        for(String number:numbers) expect(AirPodsModels.find(number)==model,"every alias resolves to "+id+": "+number);
    }
    private static void capabilities(String number,AirPodsModels.Capability... expected) {
        AirPodsModels.Model model=AirPodsModels.find(number);
        expect(model!=null,"known model "+number);
        EnumSet<AirPodsModels.Capability> wanted=expected.length==0
            ?EnumSet.noneOf(AirPodsModels.Capability.class)
            :EnumSet.copyOf(Arrays.asList(expected));
        expect(model.capabilities.equals(wanted),
            "exact capability set for "+number+": "+model.capabilities);
    }
    private static void rejects(AirPodsModels.Model model,int... ids) {
        for(int id:ids) expect(!AirPodsModels.supportsSetting(model,id),
            model.name+" must reject existing setting 0x"+Integer.toHexString(id));
    }
    private static void allows(AirPodsModels.Model model,int... ids) {
        for(int id:ids) expect(AirPodsModels.supportsSetting(model,id),
            model.name+" must allow existing setting 0x"+Integer.toHexString(id));
    }
    public static void main(String[] args) {
        expect(AirPodsModels.all().size()==11,"pinned registry contains exactly eleven models");
        aliases("airpods1","A1523","A1722");
        aliases("airpods2","A2032","A2031");
        aliases("airpods3","A2565","A2564");
        aliases("airpods4","A3053","A3050","A3054");
        aliases("airpods4anc","A3056","A3055","A3057");
        aliases("airpods5","A3531","A3532","A3533");
        aliases("airpods5wcc","A3439","A3440","A3441");
        aliases("pro1","A2084","A2083");
        aliases("pro2-lightning","A2931","A2699","A2698");
        aliases("pro2-usbc","A3047","A3048","A3049");
        aliases("pro3","A3063","A3064","A3065");

        expect(AirPodsModels.find("AirPods 2")==null,"display names are not model identities");
        expect(AirPodsModels.find("a2032")==null,"registry matching is exact and case-sensitive");
        expect(AirPodsModels.find(" A2032")==null,"padded model number is not an identity");
        expect(AirPodsModels.find("A20320")==null,"partial/overlong model number is not an identity");
        expect(AirPodsModels.find("A3058")==null,"case model numbers are not in the earbud registry");
        expect(AirPodsModels.find("A2566")==null,"charging-case model numbers are not in the upstream registry");
        expect(AirPodsModels.find("A2096")==null,"AirPods Max is intentionally absent from the pinned Android registry");
        expect(AirPodsModels.find(null)==null,"null model number is unknown");
        expect(AirPodsModels.find("")==null,"empty model number is unknown");

        expect("AirPods 1".equals(AirPodsModels.find("A1523").name),"upstream AirPods 1 name");
        expect("AirPods 2".equals(AirPodsModels.find("A2032").name),"upstream AirPods 2 name");
        expect("AirPods 3".equals(AirPodsModels.find("A2565").name),"upstream AirPods 3 name");
        expect("AirPods 4".equals(AirPodsModels.find("A3053").name),"upstream AirPods 4 name");
        expect("AirPods 4 (ANC)".equals(AirPodsModels.find("A3056").name),"upstream AirPods 4 ANC name");
        expect("AirPods 5".equals(AirPodsModels.find("A3531").name),"upstream AirPods 5 name");
        expect("AirPods 5 with Wireless Charging Case".equals(AirPodsModels.find("A3439").name),
            "upstream AirPods 5 wireless-case name");
        expect("AirPods Pro 1".equals(AirPodsModels.find("A2084").name),"upstream AirPods Pro 1 name");
        expect("AirPods Pro 2 with Magsafe Charging Case (Lightning)".equals(AirPodsModels.find("A2931").name),
            "upstream AirPods Pro 2 Lightning name");
        expect("AirPods Pro 2 with Magsafe Charging Case (USB-C)".equals(AirPodsModels.find("A3048").name),
            "upstream AirPods Pro 2 USB-C name");
        expect("AirPods Pro 3".equals(AirPodsModels.find("A3063").name),"upstream AirPods Pro 3 name");
        expect("AirPods Pro".equals(AirPodsModels.find("A2083").displayName),"upstream Pro display name");
        expect("AirPods".equals(AirPodsModels.find("A2032").displayName),"upstream default display name");
        expect("Apple Inc.".equals(AirPodsModels.find("A2032").manufacturer),"upstream manufacturer");

        capabilities("A1523");
        capabilities("A2032");
        capabilities("A2565",AirPodsModels.Capability.HEAD_GESTURES);
        capabilities("A3053",AirPodsModels.Capability.HEAD_GESTURES,
            AirPodsModels.Capability.SLEEP_DETECTION,AirPodsModels.Capability.ADAPTIVE_VOLUME);
        capabilities("A3056",AirPodsModels.Capability.LISTENING_MODE,
            AirPodsModels.Capability.CONVERSATION_AWARENESS,AirPodsModels.Capability.HEAD_GESTURES,
            AirPodsModels.Capability.ADAPTIVE_AUDIO,AirPodsModels.Capability.SLEEP_DETECTION,
            AirPodsModels.Capability.ADAPTIVE_VOLUME,AirPodsModels.Capability.STEM_CONFIG);
        capabilities("A3531",AirPodsModels.Capability.LISTENING_MODE,
            AirPodsModels.Capability.CONVERSATION_AWARENESS,AirPodsModels.Capability.HEAD_GESTURES,
            AirPodsModels.Capability.ADAPTIVE_AUDIO,AirPodsModels.Capability.SLEEP_DETECTION,
            AirPodsModels.Capability.ADAPTIVE_VOLUME,AirPodsModels.Capability.STEM_CONFIG);
        capabilities("A3439",AirPodsModels.Capability.LISTENING_MODE,
            AirPodsModels.Capability.CONVERSATION_AWARENESS,AirPodsModels.Capability.HEAD_GESTURES,
            AirPodsModels.Capability.ADAPTIVE_AUDIO,AirPodsModels.Capability.SLEEP_DETECTION,
            AirPodsModels.Capability.ADAPTIVE_VOLUME,AirPodsModels.Capability.STEM_CONFIG,
            AirPodsModels.Capability.SWIPE_FOR_VOLUME);
        capabilities("A2084",AirPodsModels.Capability.LISTENING_MODE);
        capabilities("A2931",AirPodsModels.Capability.LISTENING_MODE,
            AirPodsModels.Capability.CONVERSATION_AWARENESS,AirPodsModels.Capability.STEM_CONFIG,
            AirPodsModels.Capability.LOUD_SOUND_REDUCTION,AirPodsModels.Capability.SLEEP_DETECTION,
            AirPodsModels.Capability.HEARING_AID,AirPodsModels.Capability.ADAPTIVE_AUDIO,
            AirPodsModels.Capability.ADAPTIVE_VOLUME,AirPodsModels.Capability.SWIPE_FOR_VOLUME,
            AirPodsModels.Capability.HEAD_GESTURES);
        capabilities("A3047",AirPodsModels.Capability.LISTENING_MODE,
            AirPodsModels.Capability.CONVERSATION_AWARENESS,AirPodsModels.Capability.STEM_CONFIG,
            AirPodsModels.Capability.LOUD_SOUND_REDUCTION,AirPodsModels.Capability.SLEEP_DETECTION,
            AirPodsModels.Capability.HEARING_AID,AirPodsModels.Capability.ADAPTIVE_AUDIO,
            AirPodsModels.Capability.ADAPTIVE_VOLUME,AirPodsModels.Capability.SWIPE_FOR_VOLUME,
            AirPodsModels.Capability.HEAD_GESTURES);
        capabilities("A3063",AirPodsModels.Capability.LISTENING_MODE,
            AirPodsModels.Capability.CONVERSATION_AWARENESS,AirPodsModels.Capability.HEAD_GESTURES,
            AirPodsModels.Capability.STEM_CONFIG,AirPodsModels.Capability.LOUD_SOUND_REDUCTION,
            AirPodsModels.Capability.PPE,AirPodsModels.Capability.SLEEP_DETECTION,
            AirPodsModels.Capability.HEARING_AID,AirPodsModels.Capability.ADAPTIVE_AUDIO,
            AirPodsModels.Capability.ADAPTIVE_VOLUME,AirPodsModels.Capability.SWIPE_FOR_VOLUME,
            AirPodsModels.Capability.HRM);

        AirPodsModels.Model basic1=AirPodsModels.find("A1523");
        AirPodsModels.Model basic2=AirPodsModels.find("A2032");
        AirPodsModels.Model basic3=AirPodsModels.find("A2565");
        AirPodsModels.Model basic4=AirPodsModels.find("A3053");
        AirPodsModels.Model anc4=AirPodsModels.find("A3056");
        AirPodsModels.Model pro1=AirPodsModels.find("A2084");
        AirPodsModels.Model pro2=AirPodsModels.find("A3048");
        AirPodsModels.Model pro3=AirPodsModels.find("A3063");
        for(AirPodsModels.Model model:Arrays.asList(basic1,basic2,basic3,basic4)) {
            rejects(model,EarbudCommands.LISTENING_MODE,EarbudCommands.ONE_BUD_ANC,
                EarbudCommands.CONVERSATION_AWARENESS,EarbudCommands.VOLUME_SWIPE,
                EarbudCommands.SWIPE_LENGTH);
            expect(!AirPodsModels.supportsListeningMode(model,EarbudCommands.MODE_ANC),
                model.name+" has no listening-mode capability");
            expect(!AirPodsModels.supportsListeningMode(model,EarbudCommands.MODE_TRANSPARENCY),
                model.name+" has no transparency capability");
            expect(!AirPodsModels.supportsListeningMode(model,EarbudCommands.MODE_OFF),
                model.name+" has no observed off mode capability");
            expect(!AirPodsModels.supportsListeningMode(model,EarbudCommands.MODE_ADAPTIVE),
                model.name+" has no adaptive audio capability");
        }
        rejects(basic1,EarbudCommands.PERSONALIZED_VOLUME);
        rejects(basic2,EarbudCommands.PERSONALIZED_VOLUME);
        rejects(basic3,EarbudCommands.PERSONALIZED_VOLUME);
        allows(basic4,EarbudCommands.PERSONALIZED_VOLUME);
        allows(basic1,EarbudCommands.EAR_DETECTION,EarbudCommands.MICROPHONE,EarbudCommands.CHIME_VOLUME);
        allows(basic2,EarbudCommands.EAR_DETECTION,EarbudCommands.MICROPHONE,EarbudCommands.CHIME_VOLUME);
        allows(basic3,EarbudCommands.EAR_DETECTION,EarbudCommands.MICROPHONE,EarbudCommands.CHIME_VOLUME);
        allows(basic4,EarbudCommands.EAR_DETECTION,EarbudCommands.MICROPHONE,EarbudCommands.CHIME_VOLUME);
        allows(anc4,EarbudCommands.LISTENING_MODE,EarbudCommands.ONE_BUD_ANC,
            EarbudCommands.CONVERSATION_AWARENESS,EarbudCommands.PERSONALIZED_VOLUME,
            EarbudCommands.EAR_DETECTION);
        rejects(anc4,EarbudCommands.VOLUME_SWIPE,EarbudCommands.SWIPE_LENGTH);
        expect(AirPodsModels.supportsListeningMode(anc4,EarbudCommands.MODE_ANC),
            "ANC model allows ANC mode when observed");
        expect(AirPodsModels.supportsListeningMode(anc4,EarbudCommands.MODE_ADAPTIVE),
            "ANC model allows adaptive mode when observed");
        allows(pro1,EarbudCommands.LISTENING_MODE,EarbudCommands.ONE_BUD_ANC,EarbudCommands.EAR_DETECTION);
        rejects(pro1,EarbudCommands.CONVERSATION_AWARENESS,EarbudCommands.PERSONALIZED_VOLUME,
            EarbudCommands.VOLUME_SWIPE,EarbudCommands.SWIPE_LENGTH);
        expect(AirPodsModels.supportsListeningMode(pro1,EarbudCommands.MODE_ANC),
            "Pro 1 allows its core listening modes");
        expect(!AirPodsModels.supportsListeningMode(pro1,EarbudCommands.MODE_ADAPTIVE),
            "Pro 1 must not gain adaptive audio from a report");
        for(AirPodsModels.Model model:Arrays.asList(pro2,pro3)) {
            allows(model,EarbudCommands.LISTENING_MODE,EarbudCommands.ONE_BUD_ANC,
                EarbudCommands.CONVERSATION_AWARENESS,EarbudCommands.PERSONALIZED_VOLUME,
                EarbudCommands.VOLUME_SWIPE,EarbudCommands.SWIPE_LENGTH,
                EarbudCommands.EAR_DETECTION,EarbudCommands.MICROPHONE,
                EarbudCommands.CHIME_VOLUME);
            expect(AirPodsModels.supportsListeningMode(model,EarbudCommands.MODE_ADAPTIVE),
                model.name+" allows adaptive audio when observed");
        }
        for(int id:new int[]{EarbudCommands.LISTENING_MODE,EarbudCommands.ONE_BUD_ANC,
                EarbudCommands.EAR_DETECTION,EarbudCommands.VOLUME_SWIPE,
                EarbudCommands.PERSONALIZED_VOLUME,EarbudCommands.CONVERSATION_AWARENESS,
                EarbudCommands.MICROPHONE,EarbudCommands.CHIME_VOLUME,EarbudCommands.SWIPE_LENGTH}) {
            expect(AirPodsModels.supportsSetting(null,id),
                "unknown model remains report-driven for existing setting 0x"+Integer.toHexString(id));
        }
        expect(!AirPodsModels.supportsSetting(basic2,0x7f),"known model rejects unknown setting ids");
        expect(!AirPodsModels.supportsSetting(null,0x7f),"unknown model does not invent setting ids");

        List<AirPodsModels.Model> models=AirPodsModels.all();
        try {models.add(basic1);throw new AssertionError("mutable model list");}
        catch(UnsupportedOperationException expected) {}
        try {basic1.capabilities.add(AirPodsModels.Capability.LISTENING_MODE);throw new AssertionError("mutable capabilities");}
        catch(UnsupportedOperationException expected) {}
        try {basic1.modelNumbers.add("A9999");throw new AssertionError("mutable model numbers");}
        catch(UnsupportedOperationException expected) {}
        System.out.println("PASS: exact pinned model aliases/capabilities, immutable registry and existing-control gates");
    }
}
