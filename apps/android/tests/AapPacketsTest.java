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
        System.out.println("PASS: AACP handshake, flags, subscriptions and bounded retry fixtures");
    }
}
