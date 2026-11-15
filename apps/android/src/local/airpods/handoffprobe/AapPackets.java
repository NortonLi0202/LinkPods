package local.airpods.handoffprobe;

import java.util.Arrays;

/** Session initialization wire fixtures, compared with pinned LibrePods AACPManager. */
final class AapPackets {
    static final class Step {
        final long delayMs;
        final byte[] packet;
        Step(long delay,byte[] bytes) {delayMs=delay;packet=bytes;}
    }
    static byte[] handshake() {return new byte[]{0,0,4,0,1,0,2,0,0,0,0,0,0,0,0,0};}
    static byte[] featureFlags() {return new byte[]{4,0,4,0,77,0,(byte)215,0,0,0,0,0,0,0};}
    static byte[] notifications() {return new byte[]{4,0,4,0,15,0,(byte)255,(byte)255,(byte)255,(byte)255};}
    static Step[] initialization() {
        return new Step[]{new Step(0,handshake()),new Step(0,featureFlags()),new Step(0,notifications()),
            new Step(200,handshake()),new Step(400,featureFlags()),new Step(600,notifications()),
            new Step(6000,handshake()),new Step(6000,featureFlags()),new Step(6000,notifications())};
    }
    private static boolean header(byte[] packet,int at) {
        return packet.length-at>=4 && packet[at]==4 && packet[at+1]==0 && packet[at+2]==4 && packet[at+3]==0;
    }
    static byte[] normalize(byte[] packet) {
        if(packet==null || !header(packet,0))return packet;
        int offset=0;
        while(header(packet,offset+4))offset+=4;
        return offset==0?packet:Arrays.copyOfRange(packet,offset,packet.length);
    }
}
