package local.airpods.handoffprobe;

import java.io.*;
import java.util.Arrays;

public final class RootProtocolTest {
    static void expect(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String nonce="0123456789abcdef0123456789abcdef";
        expect(RootProtocol.authenticated(0,0,nonce,nonce),"root peer and nonce");
        expect(!RootProtocol.authenticated(0,10001,nonce,nonce),"non-root peer rejected");
        expect(!RootProtocol.authenticated(10001,10002,nonce,nonce),"other app rejected");
        expect(!RootProtocol.authenticated(0,0,nonce,nonce.replace('0','1')),"wrong nonce rejected");
        expect(!RootProtocol.authenticated(0,0,"", ""),"empty nonce never authenticates");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        RootProtocol.write(output,"中文 status\nsecond line");
        expect(RootProtocol.read(new ByteArrayInputStream(output.toByteArray())).equals("中文 status\nsecond line"),"UTF8 framing");
        for(int length:new int[]{-1,RootProtocol.MAX_FRAME+1}) {
            ByteArrayOutputStream data=new ByteArrayOutputStream(); new DataOutputStream(data).writeInt(length);
            try { RootProtocol.read(new ByteArrayInputStream(data.toByteArray())); throw new AssertionError("invalid frame accepted"); }
            catch(IOException expected) {}
        }
        try { RootProtocol.read(new ByteArrayInputStream(Arrays.copyOf(output.toByteArray(),6))); throw new AssertionError("truncated frame accepted"); }
        catch(IOException expected) {}
        expect(RootProtocol.command("ID")==RootProtocol.Command.ID,"known command");
        for(String command:new String[]{"id;reboot","EXEC","ID\nBRIDGE_STOP","", "../script"})
            expect(RootProtocol.command(command)==null,"arbitrary command rejected");
        System.out.println("PASS: root/app peer UID, nonce, bounded UTF8 frames and fixed operations");
    }
}
