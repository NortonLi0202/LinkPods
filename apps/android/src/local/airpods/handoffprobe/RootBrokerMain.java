package local.airpods.handoffprobe;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Started by app_process after root, not an exported Android component. */
public final class RootBrokerMain {
    public static void main(String[] ignored) throws Exception {
        if(Os.getuid()!=0)throw new SecurityException("Root UID required");
        StructStat initial=Os.lstat(RootSessionConfig.PATH);
        if(initial.st_uid<10000 || !OsConstants.S_ISREG(initial.st_mode) || (initial.st_mode&0077)!=0)
            throw new SecurityException("Private app-owned configuration required");
        int uid=initial.st_uid;
        RootBrokerOperations operations=new RootBrokerOperations(uid);
        for(;;) {
            try(LocalSocket socket=new LocalSocket()) {
                StructStat current=Os.lstat(RootSessionConfig.PATH);
                if(current.st_uid!=uid || !OsConstants.S_ISREG(current.st_mode) || (current.st_mode&0077)!=0)
                    throw new SecurityException("Root client identity changed");
                File config=new File(RootSessionConfig.PATH);
                if(config.length()>2048)throw new IOException("Oversized configuration");
                RootSessionConfig session=RootSessionConfig.parse(new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8));
                socket.connect(new LocalSocketAddress(session.socket,LocalSocketAddress.Namespace.ABSTRACT));
                if(socket.getPeerCredentials().getUid()!=uid)throw new SecurityException("Unexpected app peer UID");
                RootProtocol.write(socket.getOutputStream(),"HELLO "+session.nonce);
                for(;;) {
                    RootProtocol.Command command=RootProtocol.command(RootProtocol.read(socket.getInputStream()));
                    String response;
                    try { response=command==null ? "ERROR\nUnsupported operation" : "OK\n"+operations.execute(command); }
                    catch(Exception error) { response="ERROR\n"+error.getClass().getSimpleName()+": "+error.getMessage(); }
                    RootProtocol.write(socket.getOutputStream(),response);
                }
            } catch(IOException error) { Thread.sleep(500); }
        }
    }
}
