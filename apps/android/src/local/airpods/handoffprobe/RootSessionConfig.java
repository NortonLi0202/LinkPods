package local.airpods.handoffprobe;

import java.io.IOException;

public final class RootSessionConfig {
    public static final String PACKAGE="lp.pods";
    public static final String DIRECTORY="/data/user_de/0/"+PACKAGE;
    public static final String PATH=DIRECTORY+"/files/root-channel.config";
    public final String socket,nonce,apk;
    private RootSessionConfig(String socket,String nonce,String apk) { this.socket=socket;this.nonce=nonce;this.apk=apk; }
    public static RootSessionConfig parse(String text) throws IOException {
        if(text==null || text.length()>2048)throw new IOException("Invalid root session configuration");
        String[] lines=text.split("\n",-1);
        if(lines.length!=4 || !lines[3].isEmpty() || !lines[0].matches("linkpods\\.[0-9a-f]{32}")
            || !lines[1].matches("[0-9a-f]{32}") || !lines[2].startsWith("/data/app/")
            || !lines[2].endsWith(".apk") || lines[2].indexOf('\0')>=0)
            throw new IOException("Invalid root session configuration");
        return new RootSessionConfig(lines[0],lines[1],lines[2]);
    }
}
