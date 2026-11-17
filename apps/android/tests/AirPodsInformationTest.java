package local.airpods.handoffprobe;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Synthetic fixtures follow the pinned LibrePods AACPManager information layout:
 * binary bytes up to the first NUL, one metadata prefix string, then name,
 * model number, manufacturer, a synthetic serial (consumed and discarded),
 * version 1 and version 2. They are not captures.
 */
public final class AirPodsInformationTest {
    private static final byte[] HEADER={4,0,4,0,0x1d,0};
    private static final String SERIAL="SERIAL-TEST-ONLY";
    private static void expect(boolean yes,String message) {
        if(!yes) throw new AssertionError(message);
    }
    private static void expectNull(byte[] packet,String message) {
        expect(AirPodsInformation.parse(packet)==null,message);
    }
    private static void string(ByteArrayOutputStream out,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        out.write(bytes,0,bytes.length);
        out.write(0);
    }
    private static byte[] begin(String dropped) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(HEADER,0,HEADER.length);
        out.write(0x01);
        out.write(0);
        string(out,dropped);
        return out.toByteArray();
    }
    private static byte[] join(byte[]... chunks) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(byte[] chunk:chunks) out.write(chunk,0,chunk.length);
        return out.toByteArray();
    }
    private static byte[] frame(byte[] binaryPrefix,String dropped,String name,String model,
            String manufacturer,String serial,String version1,String version2) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(HEADER,0,HEADER.length);
        out.write(binaryPrefix,0,binaryPrefix.length);
        out.write(0);
        string(out,dropped);
        string(out,name);
        string(out,model);
        string(out,manufacturer);
        string(out,serial);
        string(out,version1);
        string(out,version2);
        return out.toByteArray();
    }
    private static byte[] normal() {
        return frame(new byte[]{0x01,0x02},"boot","AirPods Pro 2","A3048","Apple Inc.",
            SERIAL,"8B123","8B124");
    }
    private static byte[] nested(byte[] packet) {
        return join(new byte[]{4,0,4,0},packet);
    }
    private static String repeat(char value,int count) {
        StringBuilder builder=new StringBuilder(count);
        for(int i=0;i<count;i++) builder.append(value);
        return builder.toString();
    }
    public static void main(String[] args) {
        AirPodsInformation info=AirPodsInformation.parse(normal());
        expect(info!=null,"normal synthetic information frame parses");
        expect("AirPods Pro 2".equals(info.name),"name is the first post-prefix field");
        expect("A3048".equals(info.modelNumber),"model number is the second post-prefix field");
        expect("Apple Inc.".equals(info.manufacturer),"manufacturer is the third post-prefix field");
        expect("8B123".equals(info.version1) && "8B124".equals(info.version2),
            "versions follow the independently consumed serial field");
        expect(!SERIAL.equals(info.version1) && !SERIAL.equals(info.version2),
            "the serial field must never be exposed as a firmware version");
        for(Field field:AirPodsInformation.class.getFields()) {
            if(field.getType()!=String.class) continue;
            try {
                expect(!SERIAL.equals(field.get(info)),"serial value leaked through "+field.getName());
            } catch(IllegalAccessException error) {
                throw new AssertionError(error);
            }
        }
        expect(AirPodsModels.find(info.modelNumber)==AirPodsModels.find("A3048"),"model field resolves exactly");

        AirPodsInformation repeated=AirPodsInformation.parse(nested(normal()));
        expect(repeated!=null && "A3048".equals(repeated.modelNumber),"repeated AACP headers are normalized");

        AirPodsInformation emptyPrefix=AirPodsInformation.parse(
            frame(new byte[0],"prefix","AirPods 2","A2032","Apple Inc.",SERIAL,"8B123","8B124"));
        expect(emptyPrefix!=null && "A2032".equals(emptyPrefix.modelNumber),"empty binary prefix is accepted");
        expect(AirPodsModels.find(emptyPrefix.modelNumber).id.equals("airpods2"),"empty-prefix identity");

        AirPodsInformation differentPrefix=AirPodsInformation.parse(
            frame(new byte[]{0x10,0x20,0x30},"a different prefix string","AirPods 3","A2565",
                "Apple Inc.",SERIAL,"8B123","8B124"));
        expect(differentPrefix!=null && "A2565".equals(differentPrefix.modelNumber),
            "only one metadata string is discarded before name/model");

        AirPodsInformation unknown=AirPodsInformation.parse(
            frame(new byte[]{0x05},"prefix","Future AirPods","A9999","Apple Inc.",SERIAL,"",""));
        expect(unknown!=null && "A9999".equals(unknown.modelNumber),"unknown A-number keeps its exact field");
        expect(AirPodsModels.find(unknown.modelNumber)==null,"unknown A-number is not mapped to a known model");

        AirPodsInformation spoofName=AirPodsInformation.parse(
            frame(new byte[]{0x01},"prefix","A3056","A2032","Apple Inc.",SERIAL,"",""));
        expect(spoofName!=null && "A2032".equals(spoofName.modelNumber),
            "a model-looking display name cannot replace the model field");
        expect(AirPodsModels.find(spoofName.modelNumber).id.equals("airpods2"),
            "display-name model text is never searched for identity");

        byte[] serialSpoof=frame(new byte[]{0x01},"prefix","AirPods","A2032","Apple Inc.",
            SERIAL,"8B123","8B124");
        AirPodsInformation serial=AirPodsInformation.parse(join(serialSpoof,
            "SERIAL-A3056".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "LEFT-A3056".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "RIGHT-A3056".getBytes(StandardCharsets.UTF_8),new byte[]{0}));
        expect(serial!=null && "A2032".equals(serial.modelNumber),
            "serial fields after the known fields cannot spoof identity");

        AirPodsInformation skipped=AirPodsInformation.parse(join(begin("prefix"),new byte[]{0},
            "AirPods 4".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "A3053".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "Apple Inc.".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            SERIAL.getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "8B123".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "8B124".getBytes(StandardCharsets.UTF_8),new byte[]{0}));
        expect(skipped!=null && "A3053".equals(skipped.modelNumber),"redundant NUL padding between fields is tolerated");

        expectNull(null,"null information packet is malformed");
        expectNull(new byte[0],"empty information packet is malformed");
        expectNull(new byte[]{4,0,4,0,0x1d},"short information header is malformed");
        expectNull(new byte[]{4,0,4,0,0x1d,1,0},"non-zero information trailer byte is malformed");
        expectNull(new byte[]{4,0,4,0,0x1e,0},"wrong opcode is not information");
        byte[] wrongHeader=normal();wrongHeader[2]=5;
        expectNull(wrongHeader,"wrong AACP service header is malformed");
        byte[] wrongVersion=normal();wrongVersion[1]=1;
        expectNull(wrongVersion,"wrong AACP version header is malformed");
        expectNull(new byte[]{4,0,4,0,0x1d,0,1,2,3},"binary prefix without a NUL is malformed");
        expectNull(new byte[]{4,0,4,0,0x1d,0,0},"missing metadata prefix string is malformed");
        expectNull(new byte[]{4,0,4,0,0x1d,0,0,'p',0},"missing name field is malformed");
        expectNull(frame(new byte[]{1},"prefix","","A2032","Apple Inc.",SERIAL,"",""),"empty name is malformed");
        expectNull(frame(new byte[]{1},"prefix","AirPods","","Apple Inc.",SERIAL,"",""),"empty model field is malformed");
        for(String model:new String[]{"A203","A20320","a2032","2032","A20A2","A2032 ","A 2032"}) {
            expectNull(frame(new byte[]{1},"prefix","AirPods",model,"Apple Inc.",SERIAL,"",""),
                "non A-number model field is malformed: "+model);
        }
        expectNull(join(begin("prefix"),"AirPods 2".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "A20".getBytes(StandardCharsets.UTF_8)),"truncated name/model stream is malformed");
        expectNull(join(begin("prefix"),"AirPods 2".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "A2032".getBytes(StandardCharsets.UTF_8)),"unterminated model field is malformed");
        expectNull(join(begin("prefix"),"AirPods 2".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "A2032".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "Appl".getBytes(StandardCharsets.UTF_8)),"unterminated manufacturer field is malformed");
        expectNull(join(begin("prefix"),"AirPods 2".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "A2032".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "Apple Inc.".getBytes(StandardCharsets.UTF_8),new byte[]{0}),
            "missing serial field is malformed");
        expectNull(join(begin("prefix"),repeat('x',600).getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "A2032".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            "Apple Inc.".getBytes(StandardCharsets.UTF_8),new byte[]{0},
            SERIAL.getBytes(StandardCharsets.UTF_8),new byte[]{0}),
            "oversized name field is rejected as malformed");

        for(Field field:AirPodsInformation.class.getFields()) {
            expect(!field.getName().toLowerCase(java.util.Locale.ROOT).contains("serial"),
                "parser does not expose serial numbers: "+field);
        }
        System.out.println("PASS: bounded upstream-layout information parsing, prefix handling, repeated headers and identity spoof rejection");
    }
}
