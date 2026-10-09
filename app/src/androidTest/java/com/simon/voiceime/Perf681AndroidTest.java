package com.simon.voiceime;
import android.view.View;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
public class Perf681AndroidTest extends Delete681AndroidTest {
 static JSONArray refreshValues(String snapshot,long started)throws Exception{
  JSONArray result=new JSONArray();String[] lines=snapshot.split("\\n",-1);for(int i=0;i<lines.length-1;i++){String line=lines[i];
   JSONObject e=new JSONObject(line);if(e.optLong("ts")>=started&&"text_candidates_refresh".equals(e.optString("step")))result.put(e.getLong("ms"));
  }return result;
 }
 public void testThirtyKeys()throws Exception{
  begin();text("");focusEmptyEditor();
  View ancestor=words;while(!(ancestor instanceof KeyboardInputLayout)&&ancestor.getParent() instanceof View)ancestor=(View)ancestor.getParent();
  assertTrue(ancestor instanceof KeyboardInputLayout);KeyboardInputLayout layout=(KeyboardInputLayout)ancestor;
  java.util.function.Consumer<android.view.MotionEvent> old=layout.beforeTouch;
  java.util.List<Long> delay=new java.util.concurrent.CopyOnWriteArrayList<>();
  java.util.List<Long> upDelay=new java.util.concurrent.CopyOnWriteArrayList<>();
  android.content.Context context=words.getContext();while(context instanceof android.content.ContextWrapper&&!(context instanceof SimonIMEService))context=((android.content.ContextWrapper)context).getBaseContext();
  android.view.Window window=((SimonIMEService)context).getWindow().getWindow();android.view.Window.Callback callback=window.getCallback();
  android.view.Window.Callback measured=(android.view.Window.Callback)java.lang.reflect.Proxy.newProxyInstance(android.view.Window.Callback.class.getClassLoader(),new Class[]{android.view.Window.Callback.class},(proxy,method,args)->{
   try{Object value=method.invoke(callback,args);if("dispatchTouchEvent".equals(method.getName())&&args[0] instanceof android.view.MotionEvent){android.view.MotionEvent event=(android.view.MotionEvent)args[0];if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP)upDelay.add(Math.max(0,SystemClock.uptimeMillis()-event.getEventTime()));}return value;}
   catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
  });
  inst.runOnMainSync(()->window.setCallback(measured));
  long started=System.currentTimeMillis();
  inst.runOnMainSync(()->layout.beforeTouch=e->{delay.add(Math.max(0,SystemClock.uptimeMillis()-e.getEventTime()));if(old!=null)old.accept(e);});
  try{
   String[] seq=new String[30];String[] one={"ㄊ","ㄧ","ㄢ","空白","↵"};for(int i=0;i<30;i++)seq[i]=one[i%5];
   rapid(seq);Thread.sleep(1500);inst.waitForIdleSync();readback("perf-final-text.json");screenshot("perf-final");
   String actual=editorText(await(inputName));
   JSONArray refresh=new JSONArray();java.io.File spool=new java.io.File(inst.getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");
   if(spool.isFile())refresh=refreshValues(new String(java.nio.file.Files.readAllBytes(spool.toPath()),java.nio.charset.StandardCharsets.UTF_8),started);
   JSONArray standard=new JSONArray();java.io.File spoolFile=new java.io.File(inst.getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");
   if(spoolFile.isFile()){String[] lines=new String(java.nio.file.Files.readAllBytes(spoolFile.toPath()),java.nio.charset.StandardCharsets.UTF_8).split("\\n",-1);for(int i=0;i<lines.length-1;i++){JSONObject e=new JSONObject(lines[i]);if(e.optLong("ts")>=started&&"touch_delay".equals(e.optString("step")))standard.put(e.getLong("ms"));}}
   save("perf-result.json",new JSONObject().put("touch_delay_down_ms",new JSONArray(delay)).put("touch_delay_up_completion_all_ms",new JSONArray(upDelay)).put("text_candidates_refresh_ms",refresh).put("touch_delay_standard_ms",standard).put("expected","天天天天天天").put("actual",actual).put("received_down_count",delay.size()).put("key_count",30).put("interval_ms",80).toString());
   assertEquals("all key downs received",30,delay.size());assertEquals("all six typed syllables committed exactly once","天天天天天天",actual);
  }finally{inst.runOnMainSync(()->{layout.beforeTouch=old;window.setCallback(callback);});}
 }
}
