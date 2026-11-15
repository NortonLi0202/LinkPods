package local.airpods.handoffprobe;

import java.util.*;

/** Exact bridge identities. No model, Android-version or ordering fallback. */
final class BridgeAdapterRegistry {
    static final class Adapter {
        final String id,modulePath,sha256,buildId,profileHash;
        final int buildIdOffset;
        Adapter(String id,String path,String sha,String build,int offset,String profileHash) {
            if(id==null || !id.matches("[a-z0-9][a-z0-9-]{0,79}"))throw new IllegalArgumentException("Invalid adapter id");
            validateModulePath(path);
            if(sha==null || !sha.matches("[0-9a-f]{64}") || profileHash==null || !profileHash.matches("[0-9a-f]{64}") ||
               build==null || !build.matches("(?:[0-9a-f]{2}){1,64}") || offset<0)throw new IllegalArgumentException("Invalid adapter identity");
            this.id=id;modulePath=path;sha256=sha;buildId=build;buildIdOffset=offset;this.profileHash=profileHash;
        }
        String asset(String name) {return "adapters/"+id+"/"+name;}
    }
    final List<Adapter> adapters;
    BridgeAdapterRegistry(List<Adapter> values) {
        Set<String> ids=new HashSet<>(),identities=new HashSet<>();
        for(Adapter a:values) {
            if(a==null || !ids.add(a.id))throw new IllegalArgumentException("Duplicate adapter id");
            if(!identities.add(a.modulePath+":"+a.sha256+":"+a.buildId))throw new IllegalArgumentException("Ambiguous adapter identity");
        }
        adapters=Collections.unmodifiableList(new ArrayList<>(values));
    }
    Adapter match(String path,String sha,String build) {
        Adapter found=null;
        for(Adapter a:adapters)if(a.modulePath.equals(path)&&a.sha256.equals(sha)&&a.buildId.equals(build)) {
            if(found!=null)throw new IllegalArgumentException("Ambiguous adapter identity");
            found=a;
        }
        return found;
    }
    interface IdentityReader {String[] read(Adapter adapter) throws Exception;}
    Adapter select(IdentityReader reader) throws Exception {
        Adapter found=null;
        for(Adapter adapter:adapters) {
            String[] identity=reader.read(adapter);
            if(identity==null)continue;
            Adapter match=match(adapter.modulePath,identity[0],identity[1]);
            if(match!=null) {
                if(found!=null)throw new IllegalArgumentException("Ambiguous installed bridge adapters");
                found=match;
            }
        }
        return found;
    }
    static void validateModulePath(String path) {
        if(path==null || !path.matches("/[A-Za-z0-9_./-]+") || path.endsWith("/"))throw new IllegalArgumentException("Invalid module path");
        for(String part:path.substring(1).split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("Noncanonical module path");
    }
    static void validateAssetPath(String path) {
        if(path==null || path.startsWith("/"))throw new IllegalArgumentException("Invalid asset path");
        validateModulePath("/"+path);
    }
}
