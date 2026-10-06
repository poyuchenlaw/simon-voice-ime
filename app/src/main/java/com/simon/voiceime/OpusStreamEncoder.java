package com.simon.voiceime;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

/** One native encoder per authenticated stream. Durable source remains uncompressed. */
final class OpusStreamEncoder implements AutoCloseable {
    interface Sink { boolean send(byte[] packet); default boolean configure(int delay){return true;} }
    private final MediaCodec codec;
    private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    private final byte[] frame=new byte[640];
    private final MessageDigest wireHash;
    private int used;
    private long samples,wireBytes;
    private boolean ended;
    private int delaySamples;
    private boolean configured;
    OpusStreamEncoder() throws Exception {
        String name=null;
        for(MediaCodecInfo candidate:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(!candidate.isEncoder())continue;
            for(String type:candidate.getSupportedTypes())if("audio/opus".equalsIgnoreCase(type)) {
                MediaCodecInfo.AudioCapabilities cap=candidate.getCapabilitiesForType(type).getAudioCapabilities();
                if(cap.isSampleRateSupported(16000)&&cap.getMaxInputChannelCount()>=1){name=candidate.getName();break;}
            }
            if(name!=null)break;
        }
        if(name==null)throw new IOException("native 16kHz Opus unsupported");
        codec=MediaCodec.createByCodecName(name);
        try {
            MediaFormat format=MediaFormat.createAudioFormat("audio/opus",16000,1);
            format.setInteger(MediaFormat.KEY_BIT_RATE,16000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,640);
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start();
            wireHash=MessageDigest.getInstance("SHA-256");
        }catch(Exception e){codec.release();throw e;}
    }
    synchronized void write(byte[] pcm,int length,Sink sink)throws Exception {
        if(ended)throw new IOException("encoder already finalized");
        if(length<0||length>pcm.length||(length&1)!=0)throw new IOException("invalid PCM read");
        for(int offset=0;offset<length;) {
            int n=Math.min(length-offset,frame.length-used);System.arraycopy(pcm,offset,frame,used,n);used+=n;offset+=n;
            if(used==frame.length){input(frame.length,0,sink);used=0;}
        }
        drain(sink,false);
    }
    private void input(int bytes,int flags,Sink sink)throws Exception {
        long deadline=System.nanoTime()+500_000_000L;int slot;
        while((slot=codec.dequeueInputBuffer(10000))<0) {
            drain(sink,false);if(System.nanoTime()>deadline)throw new IOException("Opus input stalled");
        }
        ByteBuffer in=codec.getInputBuffer(slot);if(in==null)throw new IOException("missing codec input");
        in.clear();in.put(frame,0,bytes);codec.queueInputBuffer(slot,0,bytes,samples*1_000_000L/16000,flags);samples+=bytes/2;
    }
    synchronized void finish(Sink sink)throws Exception {
        if(ended)return;
        if(used>0){java.util.Arrays.fill(frame,used,frame.length,(byte)0);input(frame.length,0,sink);used=0;}
        input(0,MediaCodec.BUFFER_FLAG_END_OF_STREAM,sink);drain(sink,true);ended=true;
    }
    private void drain(Sink sink,boolean eos)throws Exception {
        long deadline=System.nanoTime()+2_000_000_000L;
        while(true) {
            int slot=codec.dequeueOutputBuffer(info,eos?10000:0);
            if(slot==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format=codec.getOutputFormat();
                if(!configured && (format.getByteBuffer("csd-1")!=null || format.getByteBuffer("csd-0")!=null)) {
                    delaySamples=metadataDelay(format.getByteBuffer("csd-1"),format.getByteBuffer("csd-0"));
                    if(!sink.configure(delaySamples))throw new IOException("Opus config transport rejected");configured=true;
                }
                continue;
            }
            if(slot<0){if(!eos)return;if(System.nanoTime()>deadline)throw new IOException("Opus EOS stalled");continue;}
            try {
                if(!configured && info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)!=0) {
                    ByteBuffer metadata=codec.getOutputBuffer(slot);
                    if(metadata==null)throw new IOException("missing Opus metadata output");
                    metadata=metadata.duplicate();metadata.position(info.offset);metadata.limit(info.offset+info.size);
                    delaySamples=metadataDelay(null,metadata.slice());
                    if(!sink.configure(delaySamples))throw new IOException("Opus config transport rejected");configured=true;
                }
                if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0) {
                    if(!configured)throw new IOException("Opus packet before delay metadata");
                    ByteBuffer output=codec.getOutputBuffer(slot);if(output==null)throw new IOException("missing codec output");
                    output.position(info.offset);output.limit(info.offset+info.size);byte[] packet=new byte[info.size];output.get(packet);
                    byte[] message=framePacket(packet);
                    if(!sink.send(message))throw new IOException("Opus transport rejected");wireHash.update(message);wireBytes+=message.length;
                }
                if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return;
            } finally {codec.releaseOutputBuffer(slot,false);}
        }
    }
    static int metadataDelay(ByteBuffer separate,ByteBuffer combined)throws IOException {
        long nanos=-1;
        if(separate!=null) {
            if(separate.remaining()!=8)throw new IOException("invalid Opus delay metadata");
            nanos=separate.duplicate().order(java.nio.ByteOrder.nativeOrder()).getLong();
        } else if(combined!=null) {
            // Android Codec2 publishes AOPUS marker/length/value metadata in csd-0.
            ByteBuffer data=combined.duplicate().order(java.nio.ByteOrder.nativeOrder());
            boolean header=false;
            while(data.remaining()>0) {
                if(data.remaining()<16)throw new IOException("truncated Opus metadata");
                byte[] marker=new byte[8];data.get(marker);long length=data.getLong();
                if(length<0||length>data.remaining())throw new IOException("invalid Opus metadata length");
                String tag=new String(marker,java.nio.charset.StandardCharsets.US_ASCII);
                if("AOPUSHDR".equals(tag)) {
                    if(header||length<19||length>276)throw new IOException("invalid Opus header");header=true;
                } else if("AOPUSDLY".equals(tag)) {
                    if(length!=8||nanos!=-1)throw new IOException("invalid Opus delay field");
                    nanos=data.getLong(data.position());
                } else if(!"AOPUSPRL".equals(tag)||length!=8)throw new IOException("unknown Opus metadata field");
                data.position(data.position()+(int)length);
            }
            if(!header)throw new IOException("Opus header missing");
        }
        if(nanos<0||nanos>1_000_000_000L)throw new IOException("native Opus delay metadata missing or invalid");
        return (int)(nanos*16000L/1_000_000_000L);
    }
    static byte[] framePacket(byte[] packet)throws IOException {
        if(packet.length<1||packet.length>1275)throw new IOException("invalid Opus packet length");
        int toc=packet[0]&255,config=toc>>>3,code=toc&3;
        int micros=config<12?new int[]{10000,20000,40000,60000}[config&3]:config<16?new int[]{10000,20000}[config&1]:2500<<(config&3);
        int count=code==0?1:code==3?(packet.length<2?0:packet[1]&63):2;
        if(micros*count!=20000)throw new IOException("codec did not produce 20ms Opus");
        ByteArrayOutputStream out=new ByteArrayOutputStream(packet.length+6);out.write(new byte[]{'O','P','2','0',(byte)(packet.length>>>8),(byte)packet.length});out.write(packet,0,packet.length);return out.toByteArray();
    }
    synchronized long wireBytes(){return wireBytes;}
    synchronized int delaySamples(){return delaySamples;}
    synchronized String wireSha() {try {MessageDigest copy=(MessageDigest)wireHash.clone();StringBuilder hex=new StringBuilder();for(byte b:copy.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();}catch(CloneNotSupportedException e){throw new IllegalStateException(e);}}
    @Override public synchronized void close(){try{codec.stop();}finally{codec.release();}}
}
