package local.airpods.handoffprobe;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Root entry accepts fixed operations only. No caller-supplied command/path. */
final class RootBrokerOperations {
    private static final File RUNTIME=new File("/data/adb/handoff");
    private final int appUid;
    RootBrokerOperations(int appUid) { this.appUid=appUid; }
    String execute(RootProtocol.Command command) throws Exception {
        if(Os.getuid()!=0)throw new SecurityException("Root lost");
        switch(command) {
            case ID:return "0";
            case LOCAL_ADDRESS:
                try(BufferedReader reader=new BufferedReader(new FileReader("/data/misc/bluedroid/bt_config.conf"))) {
                    String line;while((line=reader.readLine())!=null)
                        if(line.startsWith("Address ="))return DeviceConfig.address(line.substring(9).trim(),true);
                }
                throw new IOException("Local Bluetooth address unavailable");
            case BRIDGE_STATUS:return readBounded(new File("/data/misc/bluedroid/handoff-runtime.status"));
            case BRIDGE_STOP:
                Files.write(new File("/data/misc/bluedroid/handoff-runtime.control").toPath(),"stop\n".getBytes(StandardCharsets.US_ASCII));return "stop requested";
            case BRIDGE_PREPARE:return prepareBridge();
            default:throw new IOException("Unsupported operation");
        }
    }
    private String readBounded(File file) throws IOException {
        if(file.length()>RootProtocol.MAX_FRAME-256)throw new IOException("Root response too large");
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    private String prepareBridge() throws Exception {
        File input=new File(RootSessionConfig.DIRECTORY,"files/root-bundle");
        verifyAppFile(new File(input,"manifest.json"));
        JSONObject files=new JSONObject(readBounded(new File(input,"manifest.json")));
        if(!RUNTIME.exists() && !RUNTIME.mkdirs())throw new IOException("Cannot create runtime directory");
        StructStat directory=Os.lstat(RUNTIME.getPath());
        if(directory.st_uid!=0 || !OsConstants.S_ISDIR(directory.st_mode) || (directory.st_mode&0022)!=0)
            throw new SecurityException("Runtime directory must be root-owned and private from writers");
        for(String name:new String[]{"agent.js","frida-inject","inject-bluetooth.sh"}) {
            File source=new File(input,name);verifyAppFile(source);
            String expected=files.getJSONObject(name).getString("sha256");
            if(!RootAssets.sha256(source).equals(expected))throw new IOException("Staged payload hash mismatch: "+name);
            File staged=File.createTempFile(".linkpods-",".tmp",RUNTIME);
            try {
                Os.chmod(staged.getPath(),name.equals("agent.js")?0600:0700);
                Files.copy(source.toPath(),staged.toPath(),StandardCopyOption.REPLACE_EXISTING);
                if(!RootAssets.sha256(staged).equals(expected))throw new IOException("Copied payload mismatch");
                Os.chmod(staged.getPath(),name.equals("agent.js")?0600:0700);
                Os.rename(staged.getPath(),new File(RUNTIME,name).getPath());
            } finally { staged.delete(); }
        }
        return run("/system/bin/sh",new File(RUNTIME,"inject-bluetooth.sh").getPath());
    }
    private void verifyAppFile(File file) throws Exception {
        StructStat stat=Os.lstat(file.getPath());
        if(stat.st_uid!=appUid || !OsConstants.S_ISREG(stat.st_mode))throw new SecurityException("Unexpected staged payload owner/type");
    }
    private String run(String... command) throws Exception {
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        ExecutorService reader=Executors.newSingleThreadExecutor();
        Future<String> output=reader.submit(()->{
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] block=new byte[8192];int count;
            while((count=process.getInputStream().read(block))!=-1) {
                if(data.size()+count>RootProtocol.MAX_FRAME-256)throw new IOException("Root command output too large");
                data.write(block,0,count);
            }
            return new String(data.toByteArray(),StandardCharsets.UTF_8);
        });
        try {
            if(!process.waitFor(60,TimeUnit.SECONDS))throw new IOException("Bridge preparation timed out");
            String text=output.get(5,TimeUnit.SECONDS);
            if(process.exitValue()!=0)throw new IOException("Bridge exit "+process.exitValue()+": "+text);
            return text;
        } finally { process.destroy();output.cancel(true);reader.shutdownNow(); }
    }
}
