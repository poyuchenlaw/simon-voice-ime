package com.simon.voiceime;

import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.JSONObject;

/** Same physical deletion scenarios on baseline and candidate APKs. */
public class Delete681AndroidTest extends TapCursor677AndroidTest {
 String inputName="test_input";
 @Override void readback(String name)throws Exception{
  if("web_editor".equals(inputName)){save(name,await("web_state").getText().toString());return;}
  AccessibilityNodeInfo input=await(inputName);
  save(name,new JSONObject().put("text",editorText(input)).put("start",input.getTextSelectionStart()).put("end",input.getTextSelectionEnd()).put("preview",shown()).toString());
 }
 void delete(String label)throws Exception{
  readback(label+"-before-text.json");tap("⌫");Thread.sleep(300);inst.waitForIdleSync();
  readback(label+"-after-text.json");screenshot(label+"-after");
 }
 void rapid(String[] sequence)throws Exception{
  java.util.Map<String,Rect> positions=new java.util.HashMap<>();
  for(String k:sequence)if(!positions.containsKey(k)){Rect r=new Rect();await(k).getBoundsInScreen(r);positions.put(k,r);}
  long start=SystemClock.uptimeMillis();
  for(int i=0;i<sequence.length;i++){
   long deadline=start+i*80;SystemClock.sleep(Math.max(0,deadline-SystemClock.uptimeMillis()));Rect r=positions.get(sequence[i]);
   long t=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(t,t,0,r.centerX(),r.centerY(),0),u=MotionEvent.obtain(t,t+10,1,r.centerX(),r.centerY(),0);
   try{assertTrue(ui.injectInputEvent(d,false));assertTrue(ui.injectInputEvent(u,false));}finally{d.recycle();u.recycle();}
  }
 }
 public void testRapidTenThenFiveDeletes()throws Exception{
  begin();text("");focusEmptyEditor();
  rapid(new String[]{"ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ","ㄢ","空白","ㄊ","ㄧ","⌫","⌫","⌫","⌫","⌫"});
  Thread.sleep(1200);inst.waitForIdleSync();readback("rapid-final-text.json");screenshot("rapid-final");
  assertEquals("five deletions clear three displayed syllables","",editorText(await(inputName)));assertEquals("",shown());
 }
 public void testWaitThenDelete()throws Exception{
  begin();text("");focusEmptyEditor();for(String k:new String[]{"ㄊ","ㄧ","ㄢ","空白"})tap(k);Thread.sleep(1000);
  readback("wait-ready-text.json");String before=editorText(await(inputName));assertFalse("composition reaches editor",before.isEmpty());delete("wait");assertEquals("",editorText(await(inputName)));
 }
 public void testMiddleThenDelete()throws Exception{
  begin();text("我們明天十點在法院見面");tapCaret(9);Thread.sleep(600);delete("middle");assertEquals("我們明天十點在法見面",editorText(await(inputName)));
 }
 public void testCandidateThenDelete()throws Exception{
  begin();text("我們明天十點在法院見面");tapCaret(9);awaitTapRows(9);TextView candidate=(TextView)characters.getChildAt(0);String glyph=candidate.getText().toString();clickCandidate(candidate);
  assertEquals("我們明天十點在法"+glyph+"見面",editorText(await(inputName)));delete("candidate");assertEquals("我們明天十點在法見面",editorText(await(inputName)));
 }
 void special(String activity,String description)throws Exception{
  begin();inputName=description;shell("am start -S -W -n com.ime.sandbox.testpad/."+activity);tap(inputName);await("ㄗ");bind();awaitStableWindow();
  AccessibilityNodeInfo input=await(inputName);String before=editorText(input);assertFalse("fixture has text",before.isEmpty());
  RectF glyph=glyphAt(input.getExtras().getParcelableArray("fixture_glyphs"),8);
  if(glyph!=null){int x=Math.round(glyph.right-1),y=Math.round(glyph.centerY());tap(new Rect(x,y,x+1,y+1));Thread.sleep(600);}
  input=await(inputName);int cursor=input.getTextSelectionStart();assertTrue("caret positioned",cursor>0);
  String expected=before.substring(0,cursor-1)+before.substring(cursor);delete(activity);assertEquals(expected,editorText(await(inputName)));
 }
 public void testNullExtractDelete()throws Exception{special("NullExtractActivity","null_extract_input");}
 public void testWebViewDelete()throws Exception{
  begin();inputName="web_editor";shell("am start -S -W -n com.ime.sandbox.testpad/.WebActivity");await("web_view");await("ㄗ");bind();awaitStableWindow();
  JSONObject initial=new JSONObject(await("web_state").getText().toString());int x=initial.getInt("sx"),y=initial.getInt("sy");tap(new Rect(x,y,x+1,y+1));Thread.sleep(600);
  JSONObject before=new JSONObject(await("web_state").getText().toString());assertEquals(9,before.getInt("start"));delete("web");
  JSONObject after=new JSONObject(await("web_state").getText().toString());assertEquals("我們明天十點在法見面",after.getString("text"));
 }
}
