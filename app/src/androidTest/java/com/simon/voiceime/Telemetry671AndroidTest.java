package com.simon.voiceime;
import junit.framework.TestCase;import androidx.test.platform.app.InstrumentationRegistry;import android.view.*;import android.widget.*;import android.content.*;import org.json.*;import java.nio.charset.StandardCharsets;
public class Telemetry671AndroidTest extends TestCase {
 public void testModeAndTouchEventsPersistLocally()throws Exception {
  android.app.Instrumentation i=InstrumentationRegistry.getInstrumentation();Context c=i.getTargetContext();assertEquals("emulator only","ranchu",android.os.Build.HARDWARE);
  c.getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("ime_auto_upload",true).putString("server_url","http://127.0.0.1:8181").putString("ai_sentence_mode","shadow").putInt("ai_auto_apply_migration_version",670).commit();ImeTelemetry.install(c);
  AiSentencePhone.migrateAutoApply(c);
  i.runOnMainSync(()->{KeyboardTouchLayout k=new KeyboardTouchLayout(c,null);TextView v=new TextView(c){@Override public boolean dispatchTouchEvent(MotionEvent e){return false;}};v.setTag("key:ㄠ");k.addView(v,new LinearLayout.LayoutParams(100,69));k.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(69,View.MeasureSpec.EXACTLY));k.layout(0,0,100,69);long t=android.os.SystemClock.uptimeMillis()-300;MotionEvent d=MotionEvent.obtain(t,t,0,50,34,0),u=MotionEvent.obtain(t,t+20,1,50,34,0);d.offsetLocation(0,-10);u.offsetLocation(0,-10);k.dispatchTouchEvent(d);k.dispatchTouchEvent(u);d.recycle();u.recycle();});
  java.io.File spool=new java.io.File(c.getFilesDir(),"ime-diagnostics.jsonl");String text="";long until=android.os.SystemClock.uptimeMillis()+5000;while(android.os.SystemClock.uptimeMillis()<until){if(spool.exists())text=new String(java.nio.file.Files.readAllBytes(spool.toPath()),StandardCharsets.UTF_8);if(text.contains("mode_migrated")&&text.contains("touch_not_consumed")&&text.contains("touch_origin_rebased"))break;Thread.sleep(100);}
  boolean mode=false,miss=false,rebase=false;
  for(String line:text.split("\n"))if(!line.isEmpty()){JSONObject e=new JSONObject(line);if("mode_migrated".equals(e.optString("outcome"))){assertEquals("suggestions",e.getString("client_mode"));mode=true;}if("touch_not_consumed".equals(e.optString("step"))){assertFalse(e.getBoolean("ok"));assertEquals("",e.getString("key"));assertTrue("no candidate latency measured for dispatch-only event",e.isNull("key_to_candidate_ms"));miss=true;}if("touch_origin_rebased".equals(e.optString("step")))rebase=true;}
  assertTrue("migration event persisted",mode);assertTrue("not-consumed event persisted",miss);assertTrue("origin correction event persisted",rebase);
 }
}
