package local.airpods.handoffprobe;

import java.util.*;

/** User-selected devices. No developer addresses or Android dependencies. */
public final class DeviceConfig {
    public final String target, local, peer;
    private DeviceConfig(String target, String local, String peer) {
        this.target = target; this.local = local; this.peer = peer;
    }
    public static String address(String value, boolean required) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() && !required) return "";
        if (!normalized.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
            || normalized.equals("00:00:00:00:00:00") || normalized.equals("02:00:00:00:00:00"))
            throw new IllegalArgumentException("无效的蓝牙设备地址");
        return normalized;
    }
    public static DeviceConfig create(String target, String local, String peer) {
        String t = address(target, true), l = address(local, false), p = address(peer, false);
        if (t.equals(l) || t.equals(p) || (!l.isEmpty() && l.equals(p)))
            throw new IllegalArgumentException("耳机、本机和对端必须是不同设备");
        return new DeviceConfig(t, l, p);
    }
    public Map<String,String> save() {
        Map<String,String> values = new HashMap<>();
        values.put("target", target); values.put("local", local); values.put("peer", peer);
        return values;
    }
    public static DeviceConfig mergeLocal(DeviceConfig existing,DeviceConfig requested) {
        if(existing==null)return requested;
        if(!existing.target.equals(requested.target) || !existing.peer.equals(requested.peer)
                || (!existing.local.isEmpty() && !requested.local.isEmpty() && !existing.local.equals(requested.local)))
            throw new IllegalArgumentException("请先停止当前会话，再更换设备配置");
        return create(existing.target,existing.local.isEmpty()?requested.local:existing.local,existing.peer);
    }
    public static DeviceConfig restore(Map<String,String> values) {
        try { return create(values.get("target"), values.get("local"), values.get("peer")); }
        catch (IllegalArgumentException error) { return null; }
    }
    public static String choosePeer(Collection<String> candidates, DeviceConfig config) {
        return choosePeer(candidates, config, true);
    }
    public static String choosePeer(Collection<String> candidates, DeviceConfig config, boolean completeList) {
        if (!config.peer.isEmpty()) return config.peer;
        if (config.local.isEmpty() || !completeList) return "";
        Set<String> remote = new HashSet<>();
        for (String candidate : candidates) {
            try {
                String value = address(candidate, true);
                if (!value.equals(config.local) && !value.equals(config.target)) remote.add(value);
            } catch (IllegalArgumentException ignored) {}
        }
        return remote.size() == 1 ? remote.iterator().next() : "";
    }
}
