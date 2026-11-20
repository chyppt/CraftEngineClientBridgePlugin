package cc.cebridge;
import cc.cebridge.wire.CatalogFrames;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CatalogFramesTest {
    @Test void exactRoundTripAcrossMultipleFrames() throws Exception {
        byte[] json=new byte[150_000];new Random(42).nextBytes(json);
        var frames=CatalogFrames.split(9,json);assertTrue(frames.size()>2);
        var receiver=new CatalogFrames.Receiver();Optional<byte[]> result=Optional.empty();
        for(var frame:frames) result=receiver.accept(frame,1);
        assertArrayEquals(json,result.orElseThrow());
    }
    @Test void orderAndTimeoutDiscardIncompleteGeneration() throws Exception {
        byte[] data=new byte[80_000];new Random(4).nextBytes(data);
        var frames=CatalogFrames.split(2,data);var receiver=new CatalogFrames.Receiver();
        receiver.accept(frames.get(0),0);
        assertThrows(IOException.class,()->receiver.accept(frames.get(2),1));
        receiver.accept(frames.get(0),0);
        assertThrows(IOException.class,()->receiver.accept(frames.get(1),30_001));
    }
    @Test void forgedFrameBoundsAreRejectedBeforeAllocation() throws Exception {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeLong(1);out.writeInt(0);out.writeInt(1);out.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class,()->new CatalogFrames.Receiver().accept(bytes.toByteArray(),0));
    }
    @Test void replacementAndDisconnectReleasePartialState() throws Exception {
        var r=new CatalogFrames.Receiver();var data=new byte[60_000];new Random(8).nextBytes(data);
        var old=CatalogFrames.split(1,data);r.accept(old.get(0),0);r.clear();
        assertThrows(IOException.class,()->r.accept(old.get(1),1));
        var fresh=CatalogFrames.split(2,"new generation".getBytes());
        assertArrayEquals("new generation".getBytes(),r.accept(fresh.get(0),2).orElseThrow());
    }
}
