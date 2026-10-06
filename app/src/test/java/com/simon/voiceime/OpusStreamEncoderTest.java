package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
public class OpusStreamEncoderTest {
    @Test public void knownTwentyMillisecondPacketsUseOp20LengthFraming()throws Exception {
        // RFC 6716 TOC: SILK/hybrid config 15 and CELT config 19 are 20 ms.
        assertArrayEquals(new byte[]{'O','P','2','0',0,2,0x78,0},OpusStreamEncoder.framePacket(new byte[]{0x78,0}));
        assertArrayEquals(new byte[]{'O','P','2','0',0,2,(byte)0x98,0},OpusStreamEncoder.framePacket(new byte[]{(byte)0x98,0}));
    }
    @Test public void WrongDurationAndMalformedPacketsAreRejected()throws Exception {
        for(byte[] packet:new byte[][]{new byte[0],new byte[1276],new byte[]{(byte)0x90,0},new byte[]{(byte)0x9b}}) {
            try{OpusStreamEncoder.framePacket(packet);fail("invalid packet accepted");}catch(IOException expected){}
        }
    }
    @Test public void codec2CombinedMetadataAndSeparateDelayAgree()throws Exception {
        java.nio.ByteBuffer separate=java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.nativeOrder()).putLong(6500000L);separate.flip();
        java.nio.ByteBuffer combined=java.nio.ByteBuffer.allocate(83).order(java.nio.ByteOrder.nativeOrder());
        combined.put("AOPUSHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putLong(19).put(new byte[19]);
        combined.put("AOPUSDLY".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putLong(8).putLong(6500000L);
        combined.put("AOPUSPRL".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putLong(8).putLong(80000000L);combined.flip();
        assertEquals(104,OpusStreamEncoder.metadataDelay(separate,null));
        assertEquals(104,OpusStreamEncoder.metadataDelay(null,combined));
        combined.limit(82);
        try{OpusStreamEncoder.metadataDelay(null,combined);fail("truncated metadata accepted");}catch(IOException expected){}
    }
}
