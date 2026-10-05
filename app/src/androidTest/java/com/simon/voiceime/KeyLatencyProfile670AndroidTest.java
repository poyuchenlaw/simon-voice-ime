package com.simon.voiceime;
import android.os.Looper;import org.json.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
/** Diagnostic-only stack sampling; never used for the latency acceptance ratio. */
public class KeyLatencyProfile670AndroidTest extends KeyLatency670AndroidTest {
 public void testProfileKeyToFrame()throws Exception {
  AtomicBoolean running=new AtomicBoolean(true);Map<String,Integer> samples=new TreeMap<>();Thread main=Looper.getMainLooper().getThread();
  Thread sampler=new Thread(()->{while(running.get()){
   StringBuilder key=new StringBuilder();for(StackTraceElement f:main.getStackTrace())if(f.getClassName().startsWith("com.simon.voiceime"))key.append(f).append("\n");
   String at=key.length()==0?java.util.Arrays.toString(main.getStackTrace()):key.toString();samples.put(at,samples.getOrDefault(at,0)+1);
   try{Thread.sleep(10);}catch(InterruptedException end){return;}
  }},"DiagnosticStackSampler");sampler.start();
  try{super.testKeyToFrame();}finally{running.set(false);sampler.join(5000);JSONArray rows=new JSONArray();for(Map.Entry<String,Integer>x:samples.entrySet())rows.put(new JSONObject().put("stack",x.getKey()).put("samples",x.getValue()));if(out!=null)save("stack-profile.json",rows.toString());}
 }
}
