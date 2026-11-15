package local.airpods.handoffprobe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fixed-operation, length-prefixed protocol. Transport must check peer UID. */
public final class RootProtocol {
    public static final int MAX_FRAME=1024*1024;
    public enum Command { ID, LOCAL_ADDRESS, BRIDGE_PREPARE, BRIDGE_STATUS, BRIDGE_STOP }
    public static Command command(String name) {
        try { return Command.valueOf(name); }
        catch(IllegalArgumentException|NullPointerException error) { return null; }
    }
    public static boolean authenticated(int expectedUid,int actualUid,String expectedNonce,String actualNonce) {
        return expectedUid>=0 && actualUid>=0 && expectedUid==actualUid && expectedNonce!=null && actualNonce!=null
            && expectedNonce.matches("[0-9a-f]{32}") && actualNonce.matches("[0-9a-f]{32}")
            && MessageDigest.isEqual(expectedNonce.getBytes(StandardCharsets.US_ASCII),actualNonce.getBytes(StandardCharsets.US_ASCII));
    }
    public static void write(OutputStream stream,String message) throws IOException {
        byte[] bytes=message.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FRAME)throw new IOException("Root frame too large");
        DataOutputStream out=new DataOutputStream(stream);
        out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
    public static String read(InputStream stream) throws IOException {
        DataInputStream in=new DataInputStream(stream);
        int length=in.readInt();
        if(length<0 || length>MAX_FRAME)throw new IOException("Invalid Root frame length");
        byte[] data=new byte[length]; in.readFully(data);
        return new String(data,StandardCharsets.UTF_8);
    }
}
