package com.simon.voiceime;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import org.json.*;

public class BackspaceStuck685AndroidTest extends BackspacePreview684AndroidTest {
 String rawKeys()throws Exception {final String[] s={null};inst.runOnMainSync(()->s[0]=actualController().sentenceKeys());return s[0];}
 JSONArray events(long since)throws Exception {
  JSONArray rows=new JSONArray();java.io.File f=new java.io.File(inst.getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");
  if(f.isFile())for(String line:new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8).split("\n")){
   JSONObject e=new JSONObject(line);if(e.optLong("ts")>=since&&"backspace_hold".equals(e.optString("type")))rows.put(e);
  }return rows;
 }
 public void testTelemetryCountsAndEmpty()throws Exception {
  ready();out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
  keys("ㄐㄧㄣ ㄊㄧㄢ ，ㄍㄜ");Thread.sleep(250);inst.waitForIdleSync();long since=System.currentTimeMillis();
  for(int n=0;n<12;n++){tap("⌫");Thread.sleep(100);inst.waitForIdleSync();}
  Thread.sleep(500);JSONArray rows=events(since);save("telemetry.json",rows.toString());
  int downs=0,empty=0;for(int n=0;n<rows.length();n++){
   JSONObject e=rows.getJSONObject(n);assertTrue(e.get("field_empty") instanceof Boolean);assertTrue(e.get("composing_len") instanceof Integer);
   assertFalse(e.has("text"));
   if("down".equals(e.getString("phase"))){
    // Eleven physical keys including the literal comma; ten are phonetic/tone keys.
    assertEquals("literal removal leaves the phonetic count unchanged",Math.max(0,10-downs+(downs>=3?1:0)),e.getInt("composing_len"));
    downs++;if(e.getBoolean("field_empty"))empty++;
   }
  }
  assertEquals(12,downs);assertTrue(empty>=2);assertEquals("",editorText(await("test_input")));
 }
 public void testStartFeedback()throws Exception {
  ready();out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
  keys("ㄐㄧㄣ ㄊㄧㄢ ");tapBoundary(0);String before=shown();tap("⌫");Thread.sleep(150);inst.waitForIdleSync();
  assertEquals(before,shown());final android.widget.TextView[] status={null};inst.runOnMainSync(()->{
   status[0]=row1.getRootView().findViewById(R.id.statusText);
   assertEquals(android.view.View.VISIBLE,status[0].getVisibility());assertTrue(status[0].getText().toString().contains("游標在開頭"));
  });screenshot("cursor-start-feedback");
 }
 public void testPhysicalHoldThenFiveTaps()throws Exception {
  ready();out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
  keys("ㄐㄧㄣ ㄊㄧㄢ ，ㄍㄜ");Thread.sleep(250);inst.waitForIdleSync();
  readback("before-hold.json");screenshot("before-hold");
  Rect r=new Rect();await("⌫").getBoundsInScreen(r);long down=SystemClock.uptimeMillis();
  MotionEvent d=MotionEvent.obtain(down,down,0,r.centerX(),r.centerY(),0);
  try{assertTrue(ui.injectInputEvent(d,true));}finally{d.recycle();}
  try{SystemClock.sleep(Math.max(0,down+850-SystemClock.uptimeMillis()));}
  finally{MotionEvent u=MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,r.centerX(),r.centerY(),0);try{assertTrue(ui.injectInputEvent(u,true));}finally{u.recycle();}}
  Thread.sleep(250);inst.waitForIdleSync();readback("after-hold.json");
  JSONArray steps=new JSONArray();int stuck=0;
  for(int n=0;n<5;n++){
   String before=editorText(await("test_input")),keysBefore=rawKeys();tap("⌫");Thread.sleep(150);inst.waitForIdleSync();
   String after=editorText(await("test_input")),keysAfter=rawKeys();
   boolean progress=before.isEmpty()||after.length()<before.length()||keysAfter.length()<keysBefore.length();
   steps.put(new JSONObject().put("tap",n+1).put("before",before).put("after",after).put("keys_before_len",keysBefore.length()).put("keys_after_len",keysAfter.length()).put("progress",progress));
   if(!progress)stuck++;
  }
  save("backspace-steps.json",steps.toString());screenshot("before-preview-tap");readback("before-preview-tap.json");
  if(!shown().isEmpty()){tapBoundary(Math.min(2,shown().length()));tap("⌫");Thread.sleep(150);inst.waitForIdleSync();readback("after-preview-recovery.json");screenshot("after-preview-recovery");}
  assertEquals("each press removes a character or phonetic key, unless the field is already empty: "+steps,0,stuck);
 }
}
