package com.simon.voiceime;
import android.os.*;import android.util.Printer;import android.view.*;import org.json.*;import java.util.*;
/** Same APK, real tap injection and platform Looper dispatch headers for both product versions. */
public class App690Fix5KeysTest extends App690FSupport {
 public void testThirtyColdKeys()throws Exception {
  View surface=page("bopomofo");JSONArray dispatches=new JSONArray(),inputs=new JSONArray();Printer[] previous={null};long[] started={-1};String[] header={""};int[] index={-1};
  main(()->{previous[0]=(Printer)field(Looper.getMainLooper(),"mLogging");Looper.getMainLooper().setMessageLogging(line->{long now=SystemClock.elapsedRealtimeNanos();if(previous[0]!=null)previous[0].println(line);try{if(line.startsWith(">>>>>")){started[0]=now;header[0]=line;}else if(line.startsWith("<<<<<")&&started[0]>=0){double ms=(now-started[0])/1e6;if(ms>=100)dispatches.put(new JSONObject().put("key_index",index[0]).put("ms",ms).put("header",header[0]).put("do_update_selection",header[0].contains("HandlerCaller")&&header[0].endsWith(": 90")).put("epoch_end_ms",System.currentTimeMillis()));started[0]=-1;}}catch(Exception e){throw new RuntimeException(e);}});return null;});
  try {
   List<View> input=main(()->{List<View> valid=new ArrayList<>();for(View v:keys(surface)){String tag=String.valueOf(v.getTag());if(tag.startsWith("key:")&&tag.substring(4).codePointCount(0,tag.substring(4).length())==1)valid.add(v);}return valid;});assertFalse("safe output keys found",input.isEmpty());
   for(int i=0;i<30;i++){index[0]=i;View key=input.get(i%input.size());long start=System.currentTimeMillis();tap(key);Thread.sleep(60);inst.waitForIdleSync();inputs.put(new JSONObject().put("index",i).put("tag",String.valueOf(key.getTag())).put("start_epoch_ms",start).put("end_epoch_ms",System.currentTimeMillis()));}
  }finally{main(()->{Looper.getMainLooper().setMessageLogging(previous[0]);return null;});save("fix5-keys.json",new JSONObject().put("inputs",inputs).put("dispatches_ge100",dispatches).put("actual_inputs",inputs.length()).put("pid",android.os.Process.myPid()).put("clock","elapsedRealtimeNanos").put("attribution","Android14 HandlerCaller what90=DO_UPDATE_SELECTION"));}
  assertEquals("30 real taps",30,inputs.length());
 }
}
