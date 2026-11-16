package local.airpods.handoffprobe;

import android.content.Context;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.*;

/** Explicit supported-target adapter; uses bundled assets, no ADB or manager app. */
final class StandaloneRootProvider implements RootAccess.Provider {
    private final Context context;
    private final RootHost host;
    private final RootAssets assets;
    private String diagnostic="";
    StandaloneRootProvider(Context context,RootHost host,RootAssets assets) {this.context=context;this.host=host;this.assets=assets;}
    public RootAccess.Probe probe() {
        try {return host.await(1500)&&host.call(RootProtocol.Command.ID).trim().equals("0")?RootAccess.Probe.GRANTED:RootAccess.Probe.ABSENT;}
        catch(InterruptedException error){Thread.currentThread().interrupt();return RootAccess.Probe.UNKNOWN;}
        catch(IOException error){diagnostic=error.getMessage();return RootAccess.Probe.UNKNOWN;}
    }
    public boolean supported() {return android.os.Build.VERSION.SDK_INT>=28 && assets.targetMatches();}
    public boolean acquire() {
        if(!supported())return false;
        ExecutorService reader=Executors.newSingleThreadExecutor();Process process=null;
        try {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            assets.stageRoot();
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            IpSecManager manager=context.getSystemService(IpSecManager.class);
            if(manager==null)throw new IOException("IpSec manager unavailable");
            InetAddress loopback=InetAddress.getByName("127.0.0.1");
            try(IpSecManager.UdpEncapsulationSocket encapsulation=manager.openUdpEncapsulationSocket();
                IpSecManager.SecurityParameterIndex spi=manager.allocateSecurityParameterIndex(loopback)) {
                byte[] key=new byte[32];new SecureRandom().nextBytes(key);
                int port;try(DatagramSocket socket=new DatagramSocket()){port=socket.getLocalPort();}
                try(IpSecTransform transform=new IpSecTransform.Builder(context)
                    .setEncryption(new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC,key))
                    .setIpv4Encapsulation(encapsulation,port).buildTransportModeTransform(loopback,spi)) {
                    StringBuilder hex=new StringBuilder();for(byte value:key)hex.append(String.format(Locale.ROOT,"%02x",value&255));
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    process=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libdfroot.so").getPath(),
                        "--encap-port",String.valueOf(encapsulation.getPort()),"--sender-port",String.valueOf(port),
                        "--spi",Integer.toUnsignedString(spi.getSpi()),"--aes-key",hex.toString()).redirectErrorStream(true).start();
                    final Process child=process;
                    Future<?> logs=reader.submit(()->{
                        try(InputStream input=child.getInputStream();OutputStream output=new FileOutputStream(new File(context.createDeviceProtectedStorageContext().getFilesDir(),"root-attempt.log"))) {
                            byte[] block=new byte[4096];int count,total=0;while((count=input.read(block))!=-1){if(total+count<=1024*1024)output.write(block,0,count);total+=count;}
                        }catch(IOException ignored){}
                    });
                    if(!process.waitFor(120,TimeUnit.SECONDS))throw new IOException("Temporary Root timed out");
                    logs.get(5,TimeUnit.SECONDS);
                    // Exit success is insufficient. Only a live authenticated UID 0 channel counts.
                    return host.await(15000)&&host.call(RootProtocol.Command.ID).trim().equals("0");
                }
            }
        } catch(InterruptedException error){Thread.currentThread().interrupt();diagnostic="cancelled";return false;}
        catch(Exception error){diagnostic=error.getClass().getSimpleName()+": "+error.getMessage();return false;}
        finally {if(process!=null)process.destroy();reader.shutdownNow();}
    }
    String diagnostic(){return diagnostic.isEmpty()?assets.payloadProblem():diagnostic;}
}
