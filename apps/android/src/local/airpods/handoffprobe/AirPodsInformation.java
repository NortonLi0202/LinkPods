/*
 * Bounded port of the INFORMATION (0x1D) field handling from LibrePods commit
 * 89356c08cea3db0b6b54792d9329d09a87cc85fa:
 * android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt
 *
 * LibrePods is free software under the GNU General Public License,
 * version 3 or later. See THIRD_PARTY.md and docs/airpods-models.md.
 */
package local.airpods.handoffprobe;

import java.nio.charset.StandardCharsets;

/**
 * Immutable, bounded view of one AACP INFORMATION notification.
 *
 * <p>Pinned upstream layout: the 04 00 04 00 1d 00 header is followed by a
 * binary prefix up to the first NUL, then one metadata prefix string that the
 * upstream parser discards, then name, model number, manufacturer, serial
 * number, version 1 and version 2. The serial field is consumed only to keep
 * the wire order, then discarded; later serial/hardware/updater fields are
 * ignored. Only the dedicated model string is used for identity, and no serial
 * value is exposed or searched for an A-number.</p>
 */
public final class AirPodsInformation {
    private static final int MAX_PACKET=4096;
    private static final int MAX_BINARY_PREFIX=64;
    private static final int MAX_FIELD=512;

    public final String name;
    public final String modelNumber;
    public final String manufacturer;
    public final String version1;
    public final String version2;

    private AirPodsInformation(String name,String modelNumber,String manufacturer,
            String version1,String version2) {
        this.name=name;
        this.modelNumber=modelNumber;
        this.manufacturer=manufacturer;
        this.version1=version1;
        this.version2=version2;
    }

    /**
     * Parse a normalized 04 00 04 00 1d 00 frame, tolerating repeated AACP
     * headers. Returns null for malformed, truncated or unrecognized layouts.
     * A syntactically valid but unknown A-number is returned; registry lookup
     * deliberately stays separate.
     */
    public static AirPodsInformation parse(byte[] packet) {
        packet=AapPackets.normalize(packet);
        if(packet==null || packet.length<8 || packet.length>MAX_PACKET) return null;
        if(packet[0]!=4 || packet[1]!=0 || packet[2]!=4 || packet[3]!=0
            || packet[4]!=0x1d || packet[5]!=0) return null;

        int index=6;
        int prefixStart=index;
        while(index<packet.length && packet[index]!=0) {
            if(index-prefixStart>=MAX_BINARY_PREFIX) return null;
            index++;
        }
        if(index>=packet.length) return null;

        // Upstream drops the first metadata string after the binary prefix.
        index=skipZeros(packet,index);
        if(index>=packet.length) return null;
        Field dropped=readField(packet,index);
        if(dropped==null) return null;
        index=dropped.next;

        index=skipZeros(packet,index);
        Field name=readRequired(packet,index);
        if(name==null) return null;
        index=name.next;

        index=skipZeros(packet,index);
        Field model=readRequired(packet,index);
        if(model==null || !isModelNumber(model.value)) return null;
        index=model.next;

        index=skipZeros(packet,index);
        Field manufacturer=readRequired(packet,index);
        if(manufacturer==null) return null;
        index=manufacturer.next;

        // Upstream field 3 is the serial number. Consume it to preserve the wire
        // order, but never retain it; versions are upstream fields 4 and 5.
        index=skipZeros(packet,index);
        Field serial=readField(packet,index);
        if(serial==null) return null;
        index=serial.next;

        String version1="",version2="";
        index=skipZeros(packet,index);
        if(index<packet.length) {
            Field first=readField(packet,index);
            if(first==null) return null;
            version1=first.value;
            index=skipZeros(packet,first.next);
            if(index<packet.length) {
                Field second=readField(packet,index);
                if(second==null) return null;
                version2=second.value;
            }
        }
        return new AirPodsInformation(name.value,model.value,manufacturer.value,version1,version2);
    }

    private static int skipZeros(byte[] packet,int index) {
        while(index<packet.length && packet[index]==0) index++;
        return index;
    }

    private static Field readRequired(byte[] packet,int index) {
        Field field=readField(packet,index);
        return field==null || field.value.isEmpty() ? null : field;
    }

    /** One NUL-terminated field; null means truncated, empty or over the limit. */
    private static Field readField(byte[] packet,int index) {
        if(index>=packet.length) return null;
        int start=index;
        while(index<packet.length && packet[index]!=0) {
            if(index-start>=MAX_FIELD) return null;
            index++;
        }
        if(index>=packet.length) return null;
        String value=new String(packet,start,index-start,StandardCharsets.UTF_8);
        return new Field(value,index+1);
    }

    private static boolean isModelNumber(String value) {
        if(value.length()!=5 || value.charAt(0)!='A') return false;
        for(int i=1;i<value.length();i++) if(value.charAt(i)<'0' || value.charAt(i)>'9') return false;
        return true;
    }

    private static final class Field {
        final String value;
        final int next;
        Field(String value,int next) {this.value=value;this.next=next;}
    }
}
