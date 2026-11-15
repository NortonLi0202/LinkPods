package local.airpods.handoffprobe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;

/** Wire formats translated from LibrePods AACPManager (GPL-3.0-or-later). */
final class RoutingPackets {
    static byte[] ownership(boolean own) {
        return new byte[]{4,0,4,0,9,0,6,(byte)(own ? 1 : 0),0,0,0};
    }
    static boolean header(byte[] data, int length, int opcode) {
        return length >= 6 && length <= data.length && data[0] == 4 && data[1] == 0
            && data[2] == 4 && data[3] == 0 && (data[4] & 255) == opcode && data[5] == 0;
    }
    static Boolean ownershipEvent(byte[] data, int length) {
        if (!header(data, length, 9) || length < 8 || data[6] != 6) return null;
        return data[7] == 1;
    }
    static boolean releaseRequest(byte[] data, int length) {
        return header(data, length, 0x11) && new String(data, 6, length - 6,
            StandardCharsets.ISO_8859_1).contains("SetOwnershipToFalse");
    }
    static List<String> connectedDevices(byte[] data, int length) {
        List<String> result = new ArrayList<>();
        if (!header(data, length, 0x2e) || length < 9) return result;
        int count = data[8] & 255;
        for (int i = 0, at = 9; i < count && at + 8 <= length; i++, at += 8) {
            result.add(String.format(java.util.Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
                data[at]&255, data[at+1]&255, data[at+2]&255, data[at+3]&255, data[at+4]&255, data[at+5]&255));
        }
        return result;
    }
    private static void address(ByteBuffer b, String mac) {
        String[] bytes = mac.split(":");
        if (bytes.length != 6) throw new IllegalArgumentException("Invalid MAC");
        for (int i = 5; i >= 0; i--) b.put((byte)Integer.parseInt(bytes[i], 16));
    }
    private static void text(ByteBuffer b, String s) { b.put(s.getBytes(StandardCharsets.US_ASCII)); }
    private static byte[] data(ByteBuffer payload) {
        ByteBuffer b = ByteBuffer.allocate(payload.capacity() + 4);
        b.put(new byte[]{4,0,4,0}).put(payload.array());
        return b.array();
    }
    static byte[] hijack(String target) {
        return hijack(target,"Hijackv2");
    }
    static byte[] hijack(String target, String reason) {
        if (!reason.equals("Hijackv2") && !reason.equals("ManualRoute")) throw new IllegalArgumentException("Unknown routing reason");
        ByteBuffer b = ByteBuffer.allocate(100+reason.length());
        b.put(new byte[]{0x10,0}); address(b,target);
        b.put(new byte[]{(byte)(90+reason.length()),0,1,(byte)0xe5,0x4a}); text(b,"localscore");
        b.put(new byte[]{0x30,0x64,0x46}); text(b,"reason"); b.put((byte)(0x40+reason.length())); text(b,reason);
        b.put((byte)0x51); text(b,"audioRoutingScore"); b.put(new byte[]{0x31,0x2d,1,0x5f});
        text(b,"audioRoutingSetOwnershipToFalse"); b.put(new byte[]{1,0x4b}); text(b,"remotescore"); b.put((byte)0xa5);
        return data(b);
    }
    static byte[] announce(String self, String target) {
        ByteBuffer b = ByteBuffer.allocate(92);
        b.put(new byte[]{0x10,0}); address(b,target); b.put(new byte[]{0x52,0,1,(byte)0xe5,0x48});
        text(b,"idleTime"); b.put(new byte[]{8,0x47}); text(b,"newTipi"); b.put(new byte[]{1,0x49});
        text(b,"btAddress"); b.put((byte)0x51); text(b,self); b.put((byte)0x46); text(b,"btName");
        b.put((byte)0x47); text(b,"Android"); b.put((byte)0x50); text(b,"nearbyAudioScore"); b.put((byte)0x0e);
        return data(b);
    }
    static String audioSourceMac(byte[] packet) {
        if (!header(packet, packet.length, 0x0e) || packet.length < 13) return null;
        return String.format(java.util.Locale.ROOT, "%02X:%02X:%02X:%02X:%02X:%02X",
            packet[11]&255,packet[10]&255,packet[9]&255,packet[8]&255,packet[7]&255,packet[6]&255);
    }
    private static void opackString(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32) throw new IllegalArgumentException("Short OPACK string exceeds 32 bytes");
        out.write(0x40 + bytes.length); out.write(bytes,0,bytes.length);
    }
    static byte[] mediaInformation(String self, String target, boolean streaming) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1); out.write(0xe5);
        opackString(out,"playingApp"); opackString(out,"com.google.ios.youtube");
        opackString(out,"hostStreamingState"); opackString(out,streaming ? "YES" : "NO");
        opackString(out,"btAddress"); opackString(out,self);
        opackString(out,"btName"); opackString(out,"Android");
        opackString(out,"otherDeviceAudioCategory"); out.write(0x31); out.write(0x2d); out.write(1);
        byte[] body = out.toByteArray();
        ByteBuffer b = ByteBuffer.allocate(14 + body.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{4,0,4,0,0x10,0}); address(b,target); b.putShort((short)body.length); b.put(body);
        return b.array();
    }
}
