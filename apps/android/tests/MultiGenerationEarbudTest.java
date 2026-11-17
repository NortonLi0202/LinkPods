package local.airpods.handoffprobe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Session integration: reported identity, model capability gates and session
 * resets. Synthetic information includes a serial between manufacturer and
 * versions so a parser that misorders those fields fails here.
 */
public final class MultiGenerationEarbudTest {
    private static final String A="11:22:33:44:55:66",B="AA:BB:CC:DD:EE:FF";
    private static void expect(boolean yes,String message) {
        if(!yes) throw new AssertionError(message);
    }
    private static byte[] control(int id,int value) {
        return new byte[]{4,0,4,0,9,0,(byte)id,(byte)value,0,0,0};
    }
    private static void string(ByteArrayOutputStream out,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes,0,bytes.length);
        out.write(0);
    }
    private static byte[] info(String name,String model) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{4,0,4,0,0x1d,0},0,6);
        out.write(0x01);
        out.write(0);
        string(out,"prefix");
        string(out,name);
        string(out,model);
        string(out,"Apple Inc.");
        string(out,"SERIAL-TEST-ONLY");
        string(out,"8B123");
        string(out,"8B124");
        return out.toByteArray();
    }
    private static byte[] malformedInfo(String name,String model) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{4,0,4,0,0x1d,0},0,6);
        out.write(0x01);
        out.write(0);
        string(out,"prefix");
        string(out,name);
        out.write(model.getBytes(StandardCharsets.UTF_8),0,model.getBytes(StandardCharsets.UTF_8).length);
        return out.toByteArray();
    }
    private static EarbudCommands.State connected(String target) {
        EarbudCommands.State state=new EarbudCommands.State();
        state.begin(target);
        state.connected();
        return state;
    }
    public static void main(String[] args) {
        EarbudCommands.State pro2=connected(A);
        expect(!pro2.snapshot(A,0).modelKnown(),"identity is unknown before an information report");
        expect("型号尚未识别".equals(pro2.snapshot(A,0).modelIdentityLabel()),
            "no report uses a generic identity label");
        expect(pro2.receive(info("AirPods Pro 2","A3048")),"known information frame accepted");
        EarbudSnapshot pro2Snapshot=pro2.snapshot(A,0);
        expect(pro2Snapshot.modelKnown(),"known model metadata is in the snapshot");
        expect("pro2-usbc".equals(pro2Snapshot.model.id),"A3048 maps to Pro 2 USB-C");
        expect(!pro2Snapshot.isPro3(),"Pro 2 is not Pro 3 for artwork");
        expect(pro2Snapshot.modelIdentityLabel().contains("A3048"),"snapshot exposes the reported model number");
        expect("8B123".equals(pro2Snapshot.version1) && "8B124".equals(pro2Snapshot.version2),
            "snapshot versions follow the discarded serial field");
        expect(!"SERIAL-TEST-ONLY".equals(pro2Snapshot.version1)
            && !"SERIAL-TEST-ONLY".equals(pro2Snapshot.version2),
            "snapshot never exposes the information serial as a version");
        expect(pro2Snapshot.supportsSetting(EarbudCommands.CONVERSATION_AWARENESS),
            "Pro 2 model supports conversation awareness");
        pro2.receive(control(EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE));
        expect(pro2.snapshot(A,0).supportsListeningMode(EarbudCommands.MODE_ADAPTIVE),
            "Pro 2 adaptive mode requires model support and an observation");
        expect(pro2.queue(A,EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE,0)!=null,
            "Pro 2 observed adaptive mode can be queued");

        EarbudCommands.State pro1=connected(A);
        pro1.receive(info("AirPods Pro","A2083"));
        pro1.receive(control(EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE));
        pro1.receive(control(EarbudCommands.CONVERSATION_AWARENESS,1));
        pro1.receive(control(EarbudCommands.VOLUME_SWIPE,1));
        pro1.receive(control(EarbudCommands.ONE_BUD_ANC,1));
        EarbudSnapshot pro1Snapshot=pro1.snapshot(A,0);
        expect(!pro1Snapshot.supportsListeningMode(EarbudCommands.MODE_ADAPTIVE),
            "unexpected Pro 1 adaptive report cannot grant adaptive mode");
        expect(!pro1Snapshot.supportsSetting(EarbudCommands.CONVERSATION_AWARENESS),
            "Pro 1 snapshot marks conversation awareness unsupported");
        expect(!pro1Snapshot.supportsSetting(EarbudCommands.VOLUME_SWIPE),
            "Pro 1 snapshot marks volume swipe unsupported");
        expect(pro1.queue(A,EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE,0)==null,
            "Pro 1 adaptive write rejected at the command queue");
        expect(pro1.queue(A,EarbudCommands.CONVERSATION_AWARENESS,1,0)==null,
            "Pro 1 conversation write rejected at the command queue");
        expect(pro1.queue(A,EarbudCommands.VOLUME_SWIPE,1,0)==null,
            "Pro 1 swipe write rejected at the command queue");
        expect(pro1.queue(A,EarbudCommands.ONE_BUD_ANC,1,0)!=null,
            "Pro 1 observed one-bud ANC remains eligible");

        EarbudCommands.State basic=connected(A);
        basic.receive(info("AirPods 2","A2032"));
        for(int[] report:new int[][]{{EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ANC},
                {EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE},
                {EarbudCommands.CONVERSATION_AWARENESS,1},
                {EarbudCommands.VOLUME_SWIPE,1},
                {EarbudCommands.ONE_BUD_ANC,1}}) {
            expect(basic.receive(control(report[0],report[1])),"valid scalar report is still decoded");
        }
        EarbudSnapshot basicSnapshot=basic.snapshot(A,0);
        for(int id:new int[]{EarbudCommands.LISTENING_MODE,EarbudCommands.CONVERSATION_AWARENESS,
                EarbudCommands.VOLUME_SWIPE,EarbudCommands.ONE_BUD_ANC}) {
            expect(!basicSnapshot.supportsSetting(id),"basic AirPods snapshot rejects 0x"
                +Integer.toHexString(id));
        }
        expect(!basicSnapshot.supportsListeningMode(EarbudCommands.MODE_ANC),
            "basic AirPods never show a listening mode");
        for(int[] write:new int[][]{{EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ANC},
                {EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE},
                {EarbudCommands.CONVERSATION_AWARENESS,1},
                {EarbudCommands.VOLUME_SWIPE,1},
                {EarbudCommands.ONE_BUD_ANC,1}}) {
            expect(basic.queue(A,write[0],write[1],0)==null,"basic AirPods queue rejects 0x"
                +Integer.toHexString(write[0]));
        }
        basic.receive(control(EarbudCommands.EAR_DETECTION,1));
        expect(basic.queue(A,EarbudCommands.EAR_DETECTION,EarbudCommands.booleanValue(false),0)!=null,
            "baseline ear detection remains report-driven on known basic AirPods");

        EarbudCommands.State anc4=connected(A);
        anc4.receive(info("AirPods 4 (ANC)","A3056"));
        anc4.receive(control(EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE));
        anc4.receive(control(EarbudCommands.CONVERSATION_AWARENESS,1));
        anc4.receive(control(EarbudCommands.PERSONALIZED_VOLUME,1));
        anc4.receive(control(EarbudCommands.VOLUME_SWIPE,1));
        expect(anc4.queue(A,EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE,0)!=null,
            "AirPods 4 ANC observed adaptive mode is eligible");
        expect(anc4.queue(A,EarbudCommands.CONVERSATION_AWARENESS,EarbudCommands.booleanValue(false),0)!=null,
            "AirPods 4 ANC conversation awareness is eligible");
        expect(anc4.queue(A,EarbudCommands.PERSONALIZED_VOLUME,EarbudCommands.booleanValue(false),0)!=null,
            "AirPods 4 ANC adaptive volume is eligible");
        expect(anc4.queue(A,EarbudCommands.VOLUME_SWIPE,1,0)==null,
            "AirPods 4 ANC does not gain swipe volume from an unexpected report");

        EarbudCommands.State unknown=connected(A);
        unknown.receive(info("Future AirPods","A9999"));
        EarbudSnapshot unknownSnapshot=unknown.snapshot(A,0);
        expect(!unknownSnapshot.modelKnown(),"unknown A-number stays generic");
        expect("未识别型号（A9999）".equals(unknownSnapshot.modelIdentityLabel()),
            "unknown model shows a generic label with the reported number");
        expect(unknownSnapshot.supportsSetting(EarbudCommands.CONVERSATION_AWARENESS),
            "unknown model keeps report-driven eligibility");
        unknown.receive(control(EarbudCommands.CONVERSATION_AWARENESS,1));
        unknown.receive(control(EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE));
        expect(unknown.queue(A,EarbudCommands.CONVERSATION_AWARENESS,EarbudCommands.booleanValue(true),0)!=null,
            "unknown model observed conversation write is allowed");
        expect(unknown.queue(A,EarbudCommands.LISTENING_MODE,EarbudCommands.MODE_ADAPTIVE,0)!=null,
            "unknown model observed adaptive write is allowed");
        expect(!unknownSnapshot.isPro3(),"unknown model is not Pro 3 for artwork");

        EarbudCommands.State pro3=connected(A);
        pro3.receive(info("AirPods Pro 3","A3063"));
        expect(pro3.snapshot(A,0).isPro3(),"identified Pro 3 enables Pro 3 artwork");
        EarbudCommands.State malformed=connected(A);
        malformed.receive(info("AirPods Pro 3","A3063"));
        expect(!malformed.receive(malformedInfo("AirPods Pro 3","A3064")),
            "malformed information packet is rejected");
        expect(malformed.snapshot(A,0).isPro3(),
            "malformed information cannot erase a valid Pro 3 identity");
        expect(!malformed.receive(new byte[]{4,0,4,0,0x1d,0,1,2,3}),
            "unrecognized information layout is rejected");
        expect(malformed.snapshot(A,0).isPro3(),
            "unrecognized layout leaves the valid identity intact");

        EarbudCommands.State reset=connected(A);
        reset.receive(info("AirPods 2","A2032"));
        reset.receive(new byte[]{4,0,4,0,4,0,1,4,1,73,2,0});
        expect(reset.snapshot(A,0).leftBattery==73,"battery arrives before session reset");
        reset.receive(info("AirPods 2","A2032"));
        expect(reset.snapshot(A,0).leftBattery==73,"information report does not disturb battery state");
        reset.disconnected();
        EarbudSnapshot disconnected=reset.snapshot(A,0);
        expect(!disconnected.ready && !disconnected.modelKnown()
            && disconnected.modelNumber.isEmpty(),"disconnect clears model identity");
        expect(disconnected.leftBattery==73,"disconnect retains the last battery reading");
        expect(!reset.receive(info("AirPods 2","A2032")),"late information after disconnect is ignored");
        reset.connected();
        expect(!reset.snapshot(A,0).modelKnown() && reset.snapshot(A,0).leftBattery==null,
            "new session clears identity and stale battery readings");
        reset.receive(info("AirPods Pro 2","A3048"));
        expect("pro2-usbc".equals(reset.snapshot(A,0).model.id),"new session accepts a new identity");
        reset.begin(B);
        reset.connected();
        expect(!reset.snapshot(A,0).ready && !reset.snapshot(A,0).modelKnown(),
            "target replacement invalidates the old target snapshot");
        expect(!reset.snapshot(B,0).modelKnown(),"new target cannot inherit model capabilities");
        reset.receive(info("AirPods Pro 3","A3063"));
        expect("pro3".equals(reset.snapshot(B,0).model.id),"new target accepts its own identity");

        EarbudCommands.State missing=connected(A);
        missing.receive(info("AirPods 4","A3053"));
        missing.receive(new byte[]{4,0,4,0,4,0,1,4,1,42,2,0});
        expect(missing.snapshot(A,0).leftBattery==42
            && missing.snapshot(A,0).rightBattery==null
            && missing.snapshot(A,0).caseBattery==null,
            "known model with one reported battery component does not fabricate the others");
        System.out.println("PASS: information identity reaches session snapshots, capability gates apply to snapshots and queue, and resets clear metadata");
    }
}
