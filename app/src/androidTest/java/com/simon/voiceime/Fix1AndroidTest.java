package com.simon.voiceime;
import android.view.*;
import android.graphics.Rect;
import android.os.SystemClock;
import org.json.*;

/** FIX1: owned emulator only, physical gestures and public editor snapshots. */
public class Fix1AndroidTest extends Delete681AndroidTest {
 void hold(boolean slide,boolean composing)throws Exception{
  begin();text(composing?"":"我們明天十點在法院見面");
  if(composing){focusEmptyEditor();rapid(new String[]{"ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ"});}
  readback("hold-before-text.json");String before=editorText(await(inputName));
  Rect r=new Rect();await("⌫").getBoundsInScreen(r);long down=SystemClock.uptimeMillis();
  MotionEvent d=MotionEvent.obtain(down,down,0,r.centerX(),r.centerY(),0);try{assertTrue(ui.injectInputEvent(d,true));}finally{d.recycle();}
  try{
   for(int i=1;i<=100;i++){
    SystemClock.sleep(Math.max(0,down+i*20-SystemClock.uptimeMillis()));
    MotionEvent m=MotionEvent.obtain(down,SystemClock.uptimeMillis(),2,r.centerX()+(slide?(i%5-2)*3:0),r.centerY()+(slide?(i%3-1)*3:0),0);
    try{assertTrue(ui.injectInputEvent(m,false));}finally{m.recycle();}
   }
  }finally{MotionEvent u=MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,r.centerX(),r.centerY(),0);try{assertTrue(ui.injectInputEvent(u,true));}finally{u.recycle();}}
  Thread.sleep(400);inst.waitForIdleSync();readback("hold-after-text.json");screenshot("hold-after");
  String after=editorText(await(inputName));save("hold-result.json",new JSONObject().put("before",before).put("after",after).put("move_events",100).put("slide",slide).put("composing",composing).put("duration_ms",2000).toString());
  assertEquals("hold repeatedly clears editor", "",after);assertEquals("hold clears preview","",shown());
 }
 public void testHoldTwoSeconds()throws Exception{hold(false,false);}
 public void testHoldWithSlide()throws Exception{hold(true,false);}
 public void testHoldAfterZhuyin()throws Exception{hold(true,true);}
 public void testBackspaceMoveConsumed()throws Exception{
  begin();text("我們明天");View key=keyboard.findViewWithTag("key:backspace");final boolean[] consumed={false};
  inst.runOnMainSync(()->{long t=SystemClock.uptimeMillis();for(int action:new int[]{0,2,1}){MotionEvent e=MotionEvent.obtain(t,t,action,1,1,0);try{boolean c=key.dispatchTouchEvent(e);if(action==2)consumed[0]=c;}finally{e.recycle();}}});
  readback("move-consumed-text.json");screenshot("move-consumed");save("move-consumed.json",new JSONObject().put("consumed",consumed[0]).toString());assertTrue("held backspace owns MOVE",consumed[0]);
 }
 public void testFiveDeleteSnapshots()throws Exception{
  begin();text("");focusEmptyEditor();rapid(new String[]{"ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ"});
  for(int i=1;i<=5;i++)delete("delete-"+i);
  assertEquals("",editorText(await(inputName)));assertEquals("",shown());
 }
 public void testPreviewCaretDelete()throws Exception{
  begin();text("");focusEmptyEditor();for(String k:new String[]{"ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ","ㄢ","空白"})tap(k);
  tapBoundary(1);readback("preview-caret-before-text.json");String before=shown();delete("preview-caret");
  assertTrue("caret backspace changes preview",shown().length()<before.length());
 }
 public void testPartialDiagnosticLine()throws Exception{
  begin();String snapshot="{\"step\":\"text_candidates_refresh\",\"ms\":7}\n{\"step\":\"layout_diag\"";
  JSONArray values=Perf681AndroidTest.refreshValues(snapshot,0);save("partial-parser.json",values.toString());readback("partial-parser-text.json");screenshot("partial-parser");assertEquals(1,values.length());assertEquals(7,values.getLong(0));
 }
 void readCosts(boolean web)throws Exception{
  begin();text("我們明天十點在法院見面");
  if(web){inputName="web_editor";shell("am start -S -W -n com.ime.sandbox.testpad/.WebActivity");await("ㄗ");bind();awaitStableWindow();}
  android.content.Context c=words.getContext();while(c instanceof android.content.ContextWrapper&&!(c instanceof SimonIMEService))c=((android.content.ContextWrapper)c).getBaseContext();
  SimonIMEService service=(SimonIMEService)c;java.lang.reflect.Method method=SimonIMEService.class.getDeclaredMethod(web?"readExternalWindow":"readExternalText",android.view.inputmethod.InputConnection.class);method.setAccessible(true);
  java.util.List<Long> times=new java.util.ArrayList<>();final int[] valid={0};
  for(int i=0;i<30;i++)inst.runOnMainSync(()->{try{long started=System.nanoTime();Object value=method.invoke(service,service.getCurrentInputConnection());times.add(System.nanoTime()-started);if(value!=null)valid[0]++;}catch(Exception error){throw new RuntimeException(error);}});
  save("external-read-costs.json",new JSONObject().put("kind",web?"WebView window":"EditText extracted").put("elapsed_ns",new JSONArray(times)).put("valid_reads",valid[0]).put("main_thread",true).toString());readback("external-read-text.json");screenshot("external-read");
 }
 public void testReadCostsEditText()throws Exception{readCosts(false);}
 public void testReadCostsWebView()throws Exception{readCosts(true);}

}
