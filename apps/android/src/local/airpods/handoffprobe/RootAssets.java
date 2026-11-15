package local.airpods.handoffprobe;

import android.content.Context;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;

/** Verify bundled assets before staging in device-protected private storage. */
final class RootAssets {
    private final Context context;
    final JSONObject manifest;
    private final BridgeAdapterRegistry registry;
    RootAssets(Context source) throws Exception {
        context=source.createDeviceProtectedStorageContext();
        if(!RootSessionConfig.PACKAGE.equals(context.getPackageName()) || context.getApplicationInfo().uid/100000!=0)
            throw new IOException("This standalone Root adapter requires the primary-user package");
        manifest=new JSONObject(text("manifest.json"));
        if(!RootSessionConfig.PACKAGE.equals(manifest.getString("package")))throw new IOException("Root payload package mismatch");
        if(manifest.getInt("schema")!=2)throw new IOException("Unsupported Root manifest schema");
        JSONObject assets=manifest.getJSONObject("files");
        Iterator<String> names=assets.keys();while(names.hasNext()) {
            String name=names.next();BridgeAdapterRegistry.validateAssetPath(name);
            if(!assets.getString(name).matches("[0-9a-f]{64}"))throw new IOException("Invalid asset digest");
        }
        ArrayList<BridgeAdapterRegistry.Adapter> adapters=new ArrayList<>();
        JSONArray records=manifest.getJSONArray("adapters");
        for(int index=0;index<records.length();index++) {
            JSONObject entry=records.getJSONObject(index);
            BridgeAdapterRegistry.Adapter adapter=new BridgeAdapterRegistry.Adapter(entry.getString("id"),entry.getString("module_path"),
                entry.getString("sha256"),entry.getString("build_id"),entry.getInt("build_id_offset"),entry.getString("profile_sha256"));
            for(String[] item:new String[][]{{"profile","profile.json"},{"agent","agent.template.js"},{"launcher","inject.template.sh"}})
                if(!entry.getString(item[0]).equals(adapter.asset(item[1])) || !assets.has(adapter.asset(item[1])))throw new IOException("Invalid adapter asset reference");
            // Packaged profiles use the same canonical bytes as the native runtime hash.
            if(!assets.getString(adapter.asset("profile.json")).equals(adapter.profileHash))throw new IOException("Profile digest mismatch");
            adapters.add(adapter);
        }
        registry=new BridgeAdapterRegistry(adapters);
        File log=new File(context.getFilesDir(),"root-bootstrap.log");
        if(!log.exists()&&!log.createNewFile())throw new IOException("Cannot prepare Root log");
        Os.chmod(log.getPath(),0600);
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=new FileInputStream(file)) { byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1)digest.update(block,0,n); }
        StringBuilder result=new StringBuilder();for(byte value:digest.digest())result.append(String.format(Locale.ROOT,"%02x",value&255));
        return result.toString();
    }
    private String text(String name) throws IOException {
        try(InputStream input=context.getAssets().open("root/"+name);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192];int n;while((n=input.read(block))!=-1) {if(output.size()+n>1024*1024)throw new IOException("Text payload too large");output.write(block,0,n);}
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private void extract(String name,File target,boolean executable) throws Exception {
        BridgeAdapterRegistry.validateAssetPath(name);
        String expected=manifest.getJSONObject("files").getString(name);
        File temporary=File.createTempFile(".root-",".tmp",target.getParentFile());
        try {
            try(InputStream input=context.getAssets().open("root/"+name);OutputStream output=new FileOutputStream(temporary)) {
                byte[] block=new byte[65536];int n;while((n=input.read(block))!=-1)output.write(block,0,n);
            }
            if(!sha256(temporary).equals(expected))throw new IOException("Bundled Root payload mismatch: "+name);
            Os.chmod(temporary.getPath(),executable?0700:0600);
            Os.rename(temporary.getPath(),target.getPath());
        } finally {temporary.delete();}
    }
    private String verifiedText(String name) throws Exception {
        BridgeAdapterRegistry.validateAssetPath(name);
        String value=text(name);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] actual=digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte item:actual)hex.append(String.format(Locale.ROOT,"%02x",item&255));
        if(!hex.toString().equals(manifest.getJSONObject("files").getString(name)))throw new IOException("Bundled Root payload mismatch: "+name);
        return value;
    }
    void stageRoot() throws Exception {
        File directory=context.getFilesDir().getParentFile();
        extract("libksud.so",new File(directory,"ksud"),true);
        extract("diagnostic.ko",new File(directory,"diagnostic.ko"),false);
        File source=new File(context.getApplicationInfo().nativeLibraryDir,"liblpbootstrap.so");
        File target=new File(directory,"bootstrap");
        Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);Os.chmod(target.getPath(),0700);
    }
    BridgeAdapterRegistry.Adapter matchBridge() throws Exception {
        for(BridgeAdapterRegistry.Adapter adapter:registry.adapters) {
            JSONObject profile=new JSONObject(verifiedText(adapter.asset("profile.json")));
            JSONObject build=profile.getJSONObject("build_id");
            if(!adapter.modulePath.equals(profile.getString("module_path")) || !adapter.sha256.equals(profile.getString("file_sha256")) ||
               !adapter.buildId.equals(build.getString("hex")) || adapter.buildIdOffset!=build.getInt("offset"))throw new IOException("Invalid adapter profile identity");
        }
        return registry.select(adapter->{
            File file=new File(adapter.modulePath);
            if(!file.isFile())return null;
            String hash=sha256(file);
            if(!hash.equals(adapter.sha256))return null;
            byte[] bytes=new byte[adapter.buildId.length()/2];
            try(RandomAccessFile input=new RandomAccessFile(file,"r")) {input.seek(adapter.buildIdOffset);input.readFully(bytes);}
            StringBuilder hex=new StringBuilder();for(byte value:bytes)hex.append(String.format(Locale.ROOT,"%02x",value&255));
            return new String[]{hash,hex.toString()};
        });
    }
    void stageBridge(String targetAddress,BridgeAdapterRegistry.Adapter adapter) throws Exception {
        if(adapter==null || !registry.adapters.contains(adapter))throw new IOException("Bridge adapter was not selected from this registry");
        String address=DeviceConfig.address(targetAddress,true);
        File directory=new File(context.getFilesDir(),"root-bundle");
        if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot stage bridge");
        Os.chmod(directory.getPath(),0700);
        extract("frida-inject",new File(directory,"frida-inject"),true);
        for(String[] item:new String[][]{{"agent.template.js","agent.js"},{"inject.template.sh","inject-bluetooth.sh"}}) {
            String template=verifiedText(adapter.asset(item[0]));
            if(!template.contains("__LINKPODS_TARGET_ADDRESS__"))throw new IOException("Missing target placeholder");
            Files.write(new File(directory,item[1]).toPath(),template.replace("__LINKPODS_TARGET_ADDRESS__",address).getBytes(StandardCharsets.UTF_8));
            Os.chmod(new File(directory,item[1]).getPath(),item[1].endsWith(".sh")?0700:0600);
        }
        JSONObject files=new JSONObject();
        for(String name:new String[]{"agent.js","frida-inject","inject-bluetooth.sh"})
            files.put(name,new JSONObject().put("sha256",sha256(new File(directory,name))));
        Files.write(new File(directory,"manifest.json").toPath(),files.toString().getBytes(StandardCharsets.UTF_8));
        Os.chmod(new File(directory,"manifest.json").getPath(),0600);
    }
    boolean targetMatches() {
        try {
            JSONObject target=manifest.getJSONObject("target");
            Map<String,String> expected=new HashMap<>(),observed=new HashMap<>();
            for(String key:new String[]{"model","android_sdk","firmware","kernel","bluetooth_sha256"})expected.put(key,target.getString(key));
            observed.put("model",android.os.Build.MODEL);observed.put("android_sdk",String.valueOf(android.os.Build.VERSION.SDK_INT));
            observed.put("firmware",android.os.Build.VERSION.INCREMENTAL);observed.put("kernel",System.getProperty("os.version",""));
            String modulePath=manifest.getString("temporary_root_module_path");
            BridgeAdapterRegistry.validateModulePath(modulePath);
            observed.put("bluetooth_sha256",sha256(new File(modulePath)));
            return RootTarget.matches(observed,expected) && !new File("/dev/df").exists();
        } catch(Exception error) {return false;}
    }
}
