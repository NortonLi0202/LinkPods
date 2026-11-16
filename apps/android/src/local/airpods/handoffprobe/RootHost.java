package local.airpods.handoffprobe;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.system.Os;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** App-owned listener. Root connects back; no world-writable command queue. */
final class RootHost {
    private static RootHost instance;
    private final String nonce;
    private final LocalServerSocket listener;
    private LocalSocket connection;
    private RootHost(Context context) throws Exception {
        if(!RootSessionConfig.PACKAGE.equals(context.getPackageName()))throw new IOException("Standalone payload package required");
        byte[] random=new byte[16];new SecureRandom().nextBytes(random);
        StringBuilder token=new StringBuilder();for(byte value:random)token.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        nonce=token.toString(); String socket="linkpods."+nonce;
        listener=new LocalServerSocket(socket);
        File config=new File(context.createDeviceProtectedStorageContext().getFilesDir(),"root-channel.config");
        AtomicFile atomic=new AtomicFile(config);
        FileOutputStream out=null;
        try {
            out=atomic.startWrite();
            Os.fchmod(out.getFD(),0600);
            out.write((socket+"\n"+nonce+"\n"+context.getApplicationInfo().sourceDir+"\n").getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
            Os.chmod(config.getPath(),0600);
        } catch(Exception error) { if(out!=null)atomic.failWrite(out);listener.close();throw error; }
        Thread accept=new Thread(this::acceptLoop,"LinkPodsRootListener");accept.setDaemon(true);accept.start();
    }
    static synchronized RootHost get(Context context) throws Exception {
        if(instance==null)instance=new RootHost(context.getApplicationContext());
        return instance;
    }
    static synchronized RootHost current(){return instance;}
    private void acceptLoop() {
        for(;;) {
            LocalSocket accepted=null;
            try {
                accepted=listener.accept();accepted.setSoTimeout(5000);
                int uid=accepted.getPeerCredentials().getUid();
                if(uid!=0) {accepted.close();continue;}
                String hello=RootProtocol.read(accepted.getInputStream());
                if(!hello.startsWith("HELLO ") || !RootProtocol.authenticated(0,uid,nonce,hello.substring(6))) {accepted.close();continue;}
                accepted.setSoTimeout(90000);
                synchronized(this) {
                    if(connection!=null)connection.close();
                    connection=accepted;notifyAll();
                }
                // Only retry already-authorized cleanup; this never probes or acquires Root.
                RootEnvironment.rootChannelConnected();
            } catch(IOException error) { if(accepted!=null)try{accepted.close();}catch(IOException ignored){} }
        }
    }
    synchronized boolean await(long timeoutMs) throws InterruptedException {
        long deadline=android.os.SystemClock.elapsedRealtime()+timeoutMs;
        while(connection==null) {
            long left=deadline-android.os.SystemClock.elapsedRealtime();if(left<=0)return false;
            wait(left);
        }
        return true;
    }
    synchronized String call(RootProtocol.Command command) throws IOException {
        if(connection==null)throw new IOException("Root channel not connected");
        try {
            RootProtocol.write(connection.getOutputStream(),command.name());
            String response=RootProtocol.read(connection.getInputStream());
            if(!response.startsWith("OK\n"))throw new IOException(response.startsWith("ERROR\n")?response.substring(6):"Invalid Root response");
            return response.substring(3);
        } catch(IOException error) { try{connection.close();}catch(IOException ignored){} connection=null;throw error; }
    }
}
