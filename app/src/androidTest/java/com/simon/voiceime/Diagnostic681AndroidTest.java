package com.simon.voiceime;
import android.os.*;
import android.graphics.Rect;
import android.view.MotionEvent;
import org.json.*;
import java.util.concurrent.*;

/** FIX2: one real main stall and one physical two-second backspace hold. */
public class Diagnostic681AndroidTest extends Delete681AndroidTest {
 JSONArray events(String type,long since)throws Exception {
  JSONArray result=new JSONArray();java.io.File file=new java.io.File(inst.getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");
  if(!file.isFile())return result;
  String[] lines=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).split("\n",-1);
  for(int n=0;n<lines.length-1;n++){JSONObject e=new JSONObject(lines[n]);if(e.optLong("ts")>=since&&type.equals(e.optString("type")))result.put(e);}
  return result;
 }
 static class ForcedStall implements Runnable {
  final CountDownLatch done=new CountDownLatch(1);
  public void run(){SystemClock.sleep(150);done.countDown();}
 }
 public void testMainStall()throws Exception {
  begin();text("");focusEmptyEditor();
  Looper main=Looper.getMainLooper();java.lang.reflect.Field field=Looper.class.getDeclaredField("mLogging");field.setAccessible(true);
  android.util.Printer printer=(android.util.Printer)field.get(main);assertNotNull("service dispatch monitor installed",printer);
  Handler handler=new Handler(main);long[] elapsed=new long[2];
  try {for(int pass=0;pass<2;pass++){
   final android.util.Printer logging=pass==0?null:printer;inst.runOnMainSync(()->main.setMessageLogging(logging));
   CountDownLatch done=new CountDownLatch(1000);Runnable task=done::countDown;long start=System.nanoTime();
   for(int n=0;n<1000;n++)handler.post(task);
   assertTrue("1000 actual messages finish",done.await(10,TimeUnit.SECONDS));elapsed[pass]=System.nanoTime()-start;
  }}finally{inst.runOnMainSync(()->main.setMessageLogging(printer));}
  save("main-monitor-overhead.json",new JSONObject().put("messages",1000).put("baseline_mean_ns",elapsed[0]/1000.0).put("monitored_mean_ns",elapsed[1]/1000.0).put("extra_mean_ns",(elapsed[1]-elapsed[0])/1000.0).put("includes","actual Looper logging, monitor, posting and scheduling").toString());
  long since=System.currentTimeMillis();ForcedStall task=new ForcedStall();handler.post(task);assertTrue(task.done.await(5,TimeUnit.SECONDS));inst.waitForIdleSync();
  JSONArray found=new JSONArray();long deadline=SystemClock.uptimeMillis()+4000;
  do{found=events("main_stall",since);if(found.length()>0)break;Thread.sleep(40);}while(SystemClock.uptimeMillis()<deadline);
  save("main-stall-queue.json",found.toString());readback("main-stall-editor-text.json");screenshot("main-stall");
  boolean matched=false;for(int n=0;n<found.length();n++){
   JSONObject e=found.getJSONObject(n);java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList("ts","type","session_id","page","app_version","ms","target","callback","what","input_active","candidates_active"));
   for(java.util.Iterator<String> keys=e.keys();keys.hasNext();)assertTrue("metadata keys only",allowed.contains(keys.next()));
   if(ForcedStall.class.getName().equals(e.getString("callback"))){assertTrue(e.getInt("ms")>=150);matched=true;}
  }assertTrue("150ms main work recorded with actual Runnable class",matched);
 }
 public void testBackspaceHoldTelemetry()throws Exception {
  begin();text("我們明天十點在法院見面".repeat(8));tap("test_input");await("ㄗ");bind();awaitStableWindow();
  readback("diagnostic-hold-before-text.json");long since=System.currentTimeMillis();Rect r=new Rect();await("⌫").getBoundsInScreen(r);long down=SystemClock.uptimeMillis();
  MotionEvent d=MotionEvent.obtain(down,down,0,r.centerX(),r.centerY(),0);try{assertTrue(ui.injectInputEvent(d,true));}finally{d.recycle();}
  try{SystemClock.sleep(Math.max(0,down+2000-SystemClock.uptimeMillis()));}
  finally{MotionEvent u=MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,r.centerX(),r.centerY(),0);try{assertTrue(ui.injectInputEvent(u,true));}finally{u.recycle();}}
  Thread.sleep(400);inst.waitForIdleSync();JSONArray found=events("backspace_hold",since);
  save("backspace-hold-queue.json",found.toString());readback("diagnostic-hold-after-text.json");screenshot("diagnostic-hold");
  int starts=0,ends=0,repeats=0,delta=0;for(int n=0;n<found.length();n++){
   JSONObject e=found.getJSONObject(n);assertEquals("backspace",e.getString("key"));
   java.util.Set<String> allowed=new java.util.HashSet<>(java.util.Arrays.asList("ts","type","session_id","page","app_version","key","phase","tick","length_delta"));
   for(java.util.Iterator<String> keys=e.keys();keys.hasNext();)assertTrue("metadata keys only",allowed.contains(keys.next()));
   switch(e.getString("phase")){case "down":starts++;break;case "up":ends++;break;case "repeat":repeats++;assertEquals(repeats,e.getInt("tick"));break;}
   if(!e.isNull("length_delta"))delta+=e.getInt("length_delta");
  }
  save("backspace-hold-summary.json",new JSONObject().put("down",starts).put("up",ends).put("repeat",repeats).put("length_delta_sum",delta).toString());
  assertEquals(1,starts);assertEquals(1,ends);assertTrue("timer fires recorded",repeats>0);assertTrue("actual editor length decreases",delta<0);
 }
}
