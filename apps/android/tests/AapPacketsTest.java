package local.airpods.handoffprobe;

import java.util.Arrays;

public final class AapPacketsTest {
    private static void expect(boolean value,String reason) {if(!value)throw new AssertionError(reason);}
    public static void main(String[] args) {
        byte[] handshake={0,0,4,0,1,0,2,0,0,0,0,0,0,0,0,0};
        byte[] flags={4,0,4,0,77,0,(byte)215,0,0,0,0,0,0,0};
        byte[] notifications={4,0,4,0,15,0,(byte)255,(byte)255,(byte)255,(byte)255};
        expect(Arrays.equals(AapPackets.handshake(),handshake),"pinned LibrePods handshake");
        expect(Arrays.equals(AapPackets.featureFlags(),flags),"pinned LibrePods flags");
        expect(Arrays.equals(AapPackets.notifications(),notifications),"all notifications, including settings and battery");
        long[] times={0,0,0,200,400,600,6000,6000,6000};
        byte[][] packets={handshake,flags,notifications,handshake,flags,notifications,handshake,flags,notifications};
        AapPackets.Step[] sequence=AapPackets.initialization();
        expect(sequence.length==times.length,"bounded initialization, no recurring timer");
        for(int i=0;i<times.length;i++) {
            expect(sequence[i].delayMs==times[i],"LibrePods phased initialization timing");
            expect(Arrays.equals(sequence[i].packet,packets[i]),"initialization has no setting, ownership or key writes");
        }
        sequence[0].packet[0]=99;
        expect(AapPackets.initialization()[0].packet[0]==0,"sessions do not share mutable packet bytes");
        expect(AapPackets.normalize(null)==null,"null input");
        expect(AapPackets.normalize(new byte[]{4}).length==1,"short input stays safely rejected by decoder");
        byte[] layers={4,0,4,0,4,0,4,0,4,0,4,0,9,0,13,2,0,0,0};
        expect(Arrays.equals(AapPackets.normalize(layers),new byte[]{4,0,4,0,9,0,13,2,0,0,0}),"multiple header layers normalize");
        expect(!AapPackets.isEarbudStateReport(null),"null is not a state report");
        for(int length=0;length<6;length++) {
            byte[] truncated=Arrays.copyOf(new byte[]{4,0,4,0,9,0},length);
            expect(!AapPackets.isEarbudStateReport(truncated),"state report requires six-byte header");
        }
        for(int opcode:new int[]{4,9,0x1d}) {
            byte[] report={4,0,4,0,(byte)opcode,0};
            expect(AapPackets.isEarbudStateReport(report),"known state opcode classified");
            for(int index=0;index<4;index++) {
                byte[] malformed=report.clone();malformed[index]^=1;
                expect(!AapPackets.isEarbudStateReport(malformed),"every header byte validated");
            }
            report[5]=1;
            expect(!AapPackets.isEarbudStateReport(report),"nonzero opcode high byte rejected");
        }
        for(int opcode:new int[]{0,6,15,77,255})
            expect(!AapPackets.isEarbudStateReport(new byte[]{4,0,4,0,(byte)opcode,0}),"unrelated opcode excluded");
        expect(!AapPackets.isInformationReport(null),"null is not information");
        expect(!AapPackets.isInformationReport(new byte[]{4,0,4,0}),"information requires opcode byte");
        expect(AapPackets.isInformationReport(new byte[]{4,0,4,0,0x1d}),"information opcode recognized at minimum length");
        expect(!AapPackets.isInformationReport(new byte[]{4,0,4,0,9,0}),"scalar report is not information");
        expect(AapPackets.isEarbudStateReport(AapPackets.normalize(layers)),"nested headers normalize before classification");
        System.out.println("PASS: AACP handshake, flags, subscriptions and bounded retry fixtures");
    }
}
