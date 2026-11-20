package cc.cebridge.wire;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** 有界的单代传输；可同时用于 Bukkit plugin messages 与 Fabric payload。 */
public final class CatalogFrames {
    public static final int VERSION = 1, CHUNK = 24_000, MAX_ZIP = 16 * 1024 * 1024, MAX_JSON = 64 * 1024 * 1024;
    private CatalogFrames() {}
    public static List<byte[]> split(long generation, byte[] json) throws IOException {
        if (json.length > MAX_JSON) throw new IOException("Catalog exceeds JSON limit");
        var out = new ByteArrayOutputStream();
        try (var zip = new DeflaterOutputStream(out)) { zip.write(json); }
        byte[] compressed = out.toByteArray();
        if (compressed.length > MAX_ZIP) throw new IOException("Catalog exceeds compressed limit");
        int total = Math.max(1, (compressed.length + CHUNK - 1) / CHUNK);
        var frames = new ArrayList<byte[]>(total);
        for (int i=0; i<total; i++) {
            var frame = new ByteArrayOutputStream();
            var data = new DataOutputStream(frame);
            data.writeLong(generation); data.writeInt(i); data.writeInt(total); data.writeInt(compressed.length);
            data.write(compressed, i * CHUNK, Math.min(CHUNK, compressed.length - i * CHUNK));
            frames.add(frame.toByteArray());
        }
        return List.copyOf(frames);
    }
    public static final class Receiver {
        private long generation, started;
        private int total, received, size;
        private ByteArrayOutputStream buffer;
        public void clear() { buffer=null; received=total=size=0; }
        public Optional<byte[]> accept(byte[] frame, long now) throws IOException {
            if (frame.length < 20 || frame.length > CHUNK+20) throw new IOException("Invalid frame size");
            var input = new DataInputStream(new ByteArrayInputStream(frame));
            long id=input.readLong(); int index=input.readInt(), count=input.readInt(), length=input.readInt();
            if (length < 1 || length > MAX_ZIP || count != (length+CHUNK-1)/CHUNK
                    || index < 0 || index >= count || input.available() != Math.min(CHUNK,length-index*CHUNK)) {
                clear(); throw new IOException("Invalid catalog bounds");
            }
            if (index == 0) { clear(); generation=id; started=now; total=count; size=length; buffer=new ByteArrayOutputStream(length); }
            if (buffer == null || id!=generation || count!=total || size!=length || index!=received
                    || now-started>30_000) { clear(); throw new IOException("Stale or reordered catalog"); }
            input.transferTo(buffer); received++;
            if (received != total) return Optional.empty();
            byte[] zipped=buffer.toByteArray(); clear();
            try (var zip = new InflaterInputStream(new ByteArrayInputStream(zipped))) {
                byte[] json=zip.readNBytes(MAX_JSON+1);
                if(json.length>MAX_JSON || zip.read()!=-1) throw new IOException("Inflated catalog too large");
                return Optional.of(json);
            }
        }
    }
}
