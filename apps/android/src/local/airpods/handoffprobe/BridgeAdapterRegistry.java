package local.airpods.handoffprobe;

import java.util.*;

/** Bridge identities: exact builds first, then one explicit prologue-resolution adapter per module. */
final class BridgeAdapterRegistry {
    static final class Adapter {
        final String id,modulePath,match,sha256,buildId,profileHash;
        final int buildIdOffset;
        Adapter(String id,String path,String match,String sha,String build,int offset,String profileHash) {
            if(id==null || !id.matches("[a-z0-9][a-z0-9-]{0,79}"))throw new IllegalArgumentException("Invalid adapter id");
            validateModulePath(path);
            String mode=match==null||match.isEmpty()?"exact":match;
            if(!mode.equals("exact")&&!mode.equals("prologue"))throw new IllegalArgumentException("Invalid adapter match mode");
            if(profileHash==null || !profileHash.matches("[0-9a-f]{64}") || offset<0)throw new IllegalArgumentException("Invalid adapter identity");
            if(mode.equals("exact")) {
                if(sha==null || !sha.matches("[0-9a-f]{64}") || build==null || !build.matches("(?:[0-9a-f]{2}){1,64}"))
                    throw new IllegalArgumentException("Invalid adapter identity");
            } else {
                sha=sha==null?"":sha;build=build==null?"":build;
                if(!sha.isEmpty() && !sha.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid adapter SHA-256");
                if(!build.isEmpty() && !build.matches("(?:[0-9a-f]{2}){1,64}"))throw new IllegalArgumentException("Invalid adapter BuildID");
            }
            this.id=id;modulePath=path;this.match=mode;this.sha256=sha;buildId=build;buildIdOffset=offset;this.profileHash=profileHash;
        }
        boolean exact() {return match.equals("exact");}
        String asset(String name) {return "adapters/"+id+"/"+name;}
    }
    final List<Adapter> adapters;
    BridgeAdapterRegistry(List<Adapter> values) {
        Set<String> ids=new HashSet<>(),identities=new HashSet<>(),prologuePaths=new HashSet<>();
        for(Adapter a:values) {
            if(a==null || !ids.add(a.id))throw new IllegalArgumentException("Duplicate adapter id");
            if(a.exact()) {
                if(!identities.add(a.modulePath+":"+a.sha256+":"+a.buildId))throw new IllegalArgumentException("Ambiguous adapter identity");
            } else if(!prologuePaths.add(a.modulePath)) {
                throw new IllegalArgumentException("Ambiguous prologue adapter for module path");
            }
        }
        adapters=Collections.unmodifiableList(new ArrayList<>(values));
    }
    Adapter match(String path,String sha,String build) {
        Adapter found=null;
        for(Adapter a:adapters)if(a.exact()&&a.modulePath.equals(path)&&a.sha256.equals(sha)&&a.buildId.equals(build)) {
            if(found!=null)throw new IllegalArgumentException("Ambiguous adapter identity");
            found=a;
        }
        return found;
    }
    interface IdentityReader {String[] read(Adapter adapter) throws Exception;}
    Adapter select(IdentityReader reader) throws Exception {
        Adapter found=null;
        for(Adapter adapter:adapters) {
            if(!adapter.exact())continue;
            String[] identity=reader.read(adapter);
            if(identity==null)continue;
            if(adapter.sha256.equals(identity[0]) && adapter.buildId.equals(identity[1])) {
                if(found!=null)throw new IllegalArgumentException("Ambiguous installed bridge adapters");
                found=adapter;
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
