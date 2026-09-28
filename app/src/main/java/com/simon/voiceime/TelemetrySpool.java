package com.simon.voiceime;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synchronized, bounded, durable retry queue; acknowledgement is the only removal path. */
final class TelemetrySpool {
    private final File file; private final long maxBytes; private final ArrayList<JSONObject> pending=new ArrayList<>();
    TelemetrySpool(File file,long maxBytes){this.file=file;this.maxBytes=maxBytes;load();}
    private synchronized void load(){if(!file.isFile())return;try(BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null)try{pending.add(new JSONObject(line));}catch(Exception ignored){}}catch(IOException ignored){}}
    synchronized void add(JSONObject event){pending.add(event);persist();}
    synchronized List<JSONObject> batch(int max){return new ArrayList<>(pending.subList(0,Math.min(max,pending.size())));}
    synchronized int size(){return pending.size();}
    synchronized boolean acknowledge(String batchId,int count)throws Exception{
        if(count<0||count>pending.size())return false;
        List<JSONObject> prefix=batch(count);
        if(!ImeTelemetry.stableBatchId(prefix).equals(batchId))return false;
        pending.subList(0,count).clear();persist();return true;
    }
    synchronized long bytes(){return encodedSize();}
    private long encodedSize(){long n=0;for(JSONObject e:pending)n+=e.toString().getBytes(StandardCharsets.UTF_8).length+1;return n;}
    private void persist(){
        try{File parent=file.getParentFile();if(parent!=null)parent.mkdirs();while(encodedSize()>maxBytes&&!pending.isEmpty())pending.remove(0);
            try(BufferedWriter out=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),StandardCharsets.UTF_8))){for(JSONObject e:pending){out.write(e.toString());out.newLine();}}
            file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true);
        }catch(IOException ignored){}
    }
}
