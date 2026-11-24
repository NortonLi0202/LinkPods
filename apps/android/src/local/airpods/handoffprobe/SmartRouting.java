package local.airpods.handoffprobe;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Decoder for the OPACK subset used by routing requests and peer relays. */
final class SmartRouting {
    static final class Message {
        final String sender;
        final Map<String,Object> fields;
        Message(String sender, Map<String,Object> fields) { this.sender=sender; this.fields=fields; }
    }
    static Message decode(byte[] wire) {
        if (wire.length<16 || wire[14]!=1 || !(RoutingPackets.header(wire,wire.length,0x10)
                || RoutingPackets.header(wire,wire.length,0x11))) return null;
        if (((wire[12]&255)|((wire[13]&255)<<8)) != wire.length-14) return null;
        try {
            Reader reader=new Reader(wire);
            Object value=reader.read(0);
            if (!(value instanceof Map) || reader.at!=wire.length) return null;
            Map<String,Object> fields=new LinkedHashMap<>();
            for (Map.Entry<?,?> e:((Map<?,?>)value).entrySet()) {
                if (!(e.getKey() instanceof String)) return null;
                fields.put((String)e.getKey(),e.getValue());
            }
            String sender=String.format(Locale.ROOT,"%02X:%02X:%02X:%02X:%02X:%02X",
                wire[11]&255,wire[10]&255,wire[9]&255,wire[8]&255,wire[7]&255,wire[6]&255);
            return new Message(sender,fields);
        } catch (IllegalArgumentException | IndexOutOfBoundsException error) { return null; }
    }
    private static final class Reader {
        final byte[] bytes; int at=15;
        final List<Object> objects=new ArrayList<>();
        Reader(byte[] bytes) { this.bytes=bytes; }
        int next() { if(at>=bytes.length)throw new IllegalArgumentException(); return bytes[at++]&255; }
        long integer(int size) { long v=0;for(int i=0;i<size;i++)v|=((long)next())<<(8*i);return v; }
        Object read(int depth) {
            if(depth>8)throw new IllegalArgumentException();
            int tag=next(); Object value;
            if(tag==1)return true;
            if(tag==2)return false;
            if(tag==4)return null;
            if(tag>=8 && tag<=0x2f)return (long)(tag-8);
            if(tag>=0xa0 && tag<=0xc0)return objects.get(tag-0xa0);
            if(tag>=0xe0 && tag<=0xee) {
                Map<Object,Object> map=new LinkedHashMap<>();
                for(int i=0;i<tag-0xe0;i++)map.put(read(depth+1),read(depth+1));
                return map;
            }
            if(tag>=0x30 && tag<=0x33) value=integer(1<<(tag-0x30));
            else if(tag>=0x40 && tag<=0x64) {
                long count=tag<=0x60 ? tag-0x40 : integer(tag-0x60);
                if(count<0 || count>bytes.length-at)throw new IllegalArgumentException();
                value=new String(bytes,at,(int)count,StandardCharsets.UTF_8); at+=(int)count;
            } else throw new IllegalArgumentException();
            if(!objects.contains(value))objects.add(value);
            return value;
        }
    }
}
