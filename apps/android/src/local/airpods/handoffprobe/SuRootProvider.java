package local.airpods.handoffprobe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Explicit app request; a root manager may ask the user for authorization. */
final class SuRootProvider implements RootAccess.Provider {
    private RootAccess.Probe last = RootAccess.Probe.UNKNOWN;
    private static final class Command {
        int exit; String output;
        Command(int exit,String output) { this.exit=exit; this.output=output; }
    }
    private Command execute(String command) throws IOException, InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        Process process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(15,TimeUnit.SECONDS)) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512]; int count;
            while ((count=process.getInputStream().read(buffer))!=-1) {
                if (output.size()+count>4096) return null;
                output.write(buffer,0,count);
            }
            return new Command(process.exitValue(), new String(output.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
        } finally { process.destroy(); }
    }
    public RootAccess.Probe probe() {
        try {
            Command result = execute("id -u");
            last = result==null ? RootAccess.Probe.UNKNOWN : RootAccess.fromCommand(result.exit,result.output);
        } catch (IOException error) { last=RootAccess.fromExecFailure(error.getMessage()); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); last=RootAccess.Probe.UNKNOWN; }
        return last;
    }
    public boolean supported() { return true; }
    public boolean acquire() { return false; }
    boolean startBroker(java.io.File binary) {
        if(last!=RootAccess.Probe.GRANTED || !binary.isFile() || !binary.getName().equals("liblpbootstrap.so"))return false;
        try {
            String path=binary.getCanonicalPath();
            if(!path.startsWith("/data/app/"))return false;
            Command result=execute("'"+path.replace("'","'\\''")+"' --broker");
            return result!=null && result.exit==0;
        } catch(IOException error){return false;}
        catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
    }
    String localBluetoothAddress() {
        if (last!=RootAccess.Probe.GRANTED) return "";
        try {
            Command result=execute("awk '/^Address =/{print toupper($3); exit}' /data/misc/bluedroid/bt_config.conf");
            return result==null || result.exit!=0 ? "" : DeviceConfig.address(result.output,true);
        } catch (IOException | IllegalArgumentException error) { return ""; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return ""; }
    }
}
