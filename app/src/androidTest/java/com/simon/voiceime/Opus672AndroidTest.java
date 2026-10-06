package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Run on X1/real phone; host mocks cannot establish native codec behaviour. */
public class Opus672AndroidTest {
    @Test public void nativeCodecConfigPrecedesAudioAndEosIncludesTail()throws Exception {
        AtomicInteger configs=new AtomicInteger(),packets=new AtomicInteger(),delaySamples=new AtomicInteger();
        java.io.ByteArrayOutputStream wire=new java.io.ByteArrayOutputStream();
        OpusStreamEncoder.Sink sink=new OpusStreamEncoder.Sink(){
            public boolean configure(int delay){assertTrue(delay>=0&&delay<=16000);assertEquals(0,packets.get());delaySamples.set(delay);configs.incrementAndGet();return true;}
            public boolean send(byte[] message){assertEquals(1,configs.get());assertTrue(message.length>6);assertEquals('O',message[0]);wire.write(message,0,message.length);packets.incrementAndGet();return true;}
        };
        try(OpusStreamEncoder encoder=new OpusStreamEncoder()) {
            byte[] tone=new byte[64642]; // 2 seconds, 20ms and one sample: exercise EOS partial-frame padding.
            for(int n=0;n<tone.length/2;n++){short value=(short)(Math.sin(n*2*Math.PI*440/16000)*4000);tone[n*2]=(byte)value;tone[n*2+1]=(byte)(value>>>8);}
            for(int offset=0;offset<tone.length;offset+=1024){int length=Math.min(1024,tone.length-offset);encoder.write(java.util.Arrays.copyOfRange(tone,offset,offset+length),length,sink);}
            encoder.finish(sink);assertTrue(packets.get()>=101);assertTrue(encoder.wireBytes()<tone.length/4);assertEquals(64,encoder.wireSha().length());
            java.io.File output=new java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"v672");assertTrue(output.isDirectory()||output.mkdirs());
            java.nio.file.Files.write(new java.io.File(output,"native-opus.op20").toPath(),wire.toByteArray());
            org.json.JSONObject identity=new org.json.JSONObject().put("delay_samples",delaySamples.get()).put("byte_count",tone.length).put("wire_bytes",encoder.wireBytes()).put("wire_sha256",encoder.wireSha());
            java.nio.file.Files.write(new java.io.File(output,"native-opus.json").toPath(),identity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Test public void variedLengthsKeepEverySourceSampleAfterDelay()throws Exception {
        org.json.JSONArray cases=new org.json.JSONArray();
        for(int i=0;i<30;i++) {
            final int sampleCount=i<10?new int[]{1,2,319,320,321,639,640,641,959,960}[i]:16000+(i-10)*37;AtomicInteger delay=new AtomicInteger(),packets=new AtomicInteger();
            java.io.ByteArrayOutputStream wire=new java.io.ByteArrayOutputStream();
            OpusStreamEncoder.Sink sink=new OpusStreamEncoder.Sink(){
                public boolean configure(int n){delay.set(n);return true;}
                public boolean send(byte[] packet){wire.write(packet,0,packet.length);packets.incrementAndGet();return true;}
            };
            try(OpusStreamEncoder encoder=new OpusStreamEncoder()) {
                byte[] tone=new byte[sampleCount*2];
                for(int n=0;n<sampleCount;n++){short v=(short)(Math.sin(n*2*Math.PI*440/16000)*4000);tone[n*2]=(byte)v;tone[n*2+1]=(byte)(v>>>8);}
                encoder.write(tone,tone.length,sink);encoder.finish(sink);
                assertTrue("EOS truncated length="+sampleCount,packets.get()*320-delay.get()>=sampleCount);
                assertTrue("EOS excessive padding",packets.get()*320-delay.get()-sampleCount<=1600);
                java.io.File dir=new java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"v672");assertTrue(dir.isDirectory()||dir.mkdirs());
                java.nio.file.Files.write(new java.io.File(dir,"varied-"+i+".op20").toPath(),wire.toByteArray());
                cases.put(new org.json.JSONObject().put("index",i).put("samples",sampleCount).put("delay",delay.get()).put("packets",packets.get()));
            }
        }
        java.io.File dir=new java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"v672");
        java.nio.file.Files.write(new java.io.File(dir,"varied.json").toPath(),cases.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

}
