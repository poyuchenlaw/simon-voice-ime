package com.simon.voiceime;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** One identical public Android observer for both installed product versions. */
public class RapidKeyboard676AndroidTest extends TestCase {
 Instrumentation inst;UiAutomation ui;File out;final JSONArray samples=new JSONArray();boolean completed;
 String shell(String command)throws Exception{
  try(ParcelFileDescriptor fd=ui.executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}
 }
 AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label){
  if(node==null)return null;
  if(label.equals(String.valueOf(node.getText()))||label.equals(String.valueOf(node.getContentDescription())))return node;
  for(int n=0;n<node.getChildCount();n++){AccessibilityNodeInfo hit=find(node.getChild(n),label);if(hit!=null)return hit;}return null;
 }
 AccessibilityNodeInfo node(String label){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo hit=find(window.getRoot(),label);if(hit!=null&&hit.isVisibleToUser())return hit;}return null;}
 AccessibilityNodeInfo await(String label)throws Exception{
  long deadline=SystemClock.uptimeMillis()+30000;do{AccessibilityNodeInfo hit=node(label);if(hit!=null)return hit;Thread.sleep(40);}while(SystemClock.uptimeMillis()<deadline);throw new AssertionError("missing visible "+label);
 }
 String text(AccessibilityNodeInfo node){return node.isShowingHintText()||node.getText()==null?"":node.getText().toString();}
 void save(String name,String data)throws Exception{Files.write(new File(out,name).toPath(),data.getBytes(StandardCharsets.UTF_8));}
 void tap(Rect bounds){
  assertFalse("nonempty physical key bounds",bounds.isEmpty());long t=SystemClock.uptimeMillis();
  MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0),up=MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);
  try{assertTrue(ui.injectInputEvent(down,true)&&ui.injectInputEvent(up,true));}finally{down.recycle();up.recycle();}inst.waitForIdleSync();
 }
 Rect bounds(String label)throws Exception{Rect result=new Rect();await(label).getBoundsInScreen(result);return result;}
 void capture(String name)throws Exception{
  AccessibilityNodeInfo input=await("test_input");save(name+"-text.json",new JSONObject().put("text",text(input)).put("start",input.getTextSelectionStart()).put("end",input.getTextSelectionEnd()).put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256")).toString());
  android.graphics.Bitmap image=ui.takeScreenshot();assertNotNull(image);try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new File(out,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}finally{image.recycle();}
 }
 void assertText(String expected)throws Exception{
  long deadline=SystemClock.uptimeMillis()+3000;while(!expected.equals(text(await("test_input")))&&SystemClock.uptimeMillis()<deadline)Thread.sleep(20);
  assertEquals("exact keyboard text",expected,text(await("test_input")));
 }
 static boolean emptyEditorReady(AccessibilityNodeInfo input){
  Bundle actual=input.getExtras();
  return input.isFocused()&&actual.getInt("fixture_text_length",-1)==0&&actual.getInt("fixture_selection_start",-1)==0&&actual.getInt("fixture_selection_end",-1)==0;
 }
 void awaitEmptyEditor()throws Exception{
  long deadline=SystemClock.uptimeMillis()+5000;AccessibilityNodeInfo input;
  do{input=await("test_input");input.refresh();if(emptyEditorReady(input))break;Thread.sleep(40);}while(SystemClock.uptimeMillis()<deadline);
  save("empty-editor-readiness.json",new JSONObject().put("focused",input.isFocused()).put("accessibility_start",input.getTextSelectionStart()).put("accessibility_end",input.getTextSelectionEnd()).put("fixture_text_length",input.getExtras().getInt("fixture_text_length",-1)).put("fixture_start",input.getExtras().getInt("fixture_selection_start",-1)).put("fixture_end",input.getExtras().getInt("fixture_selection_end",-1)).put("ready",emptyEditorReady(input)).toString());
  assertTrue("focused fixture empty text and actual caret 0 within deadline",emptyEditorReady(input));
 }
 void measuredTap(String key,Rect bounds)throws Exception{
  long start=SystemClock.uptimeMillis();tap(bounds);samples.put(new JSONObject().put("key",key).put("ms",SystemClock.uptimeMillis()-start));
 }
 public void testRapidTwentyCharactersAndTenDeletes()throws Exception{
  inst=InstrumentationRegistry.getInstrumentation();ui=inst.getUiAutomation();out=new File(inst.getTargetContext().getFilesDir(),"v666");assertTrue(out.isDirectory()||out.mkdirs());
  try{
   AccessibilityServiceInfo flags=ui.getServiceInfo();flags.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(flags);
   assertEquals("emulator only","1",shell("getprop ro.kernel.qemu").trim());
   inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("server_url","http://127.0.0.1:8181").putString("auth_password","sandbox").putString("ai_sentence_mode","shadow").putBoolean("auto_correction",false).putBoolean("ime_auto_upload",false).putString("layout_mode","text_word_char").commit();
   shell("ime enable com.simon.voiceime/.SimonIMEService");shell("ime set com.simon.voiceime/.SimonIMEService");shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity");tap(bounds("test_input"));
   long deadline=SystemClock.uptimeMillis()+30000;
   while(node("ㄗ")==null&&SystemClock.uptimeMillis()<deadline){AccessibilityNodeInfo switcher=node("注");if(switcher!=null)switcher.performAction(AccessibilityNodeInfo.ACTION_CLICK);else await("test_input").performAction(AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(100);}await("ㄗ");
   Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"");assertTrue(await("test_input").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value));tap(bounds("test_input"));assertText("");
   awaitEmptyEditor();
   Map<String,Rect> keys=new HashMap<>();JSONArray geometry=new JSONArray();
   String previous="";int stable=0;deadline=SystemClock.uptimeMillis()+5000;
   while(stable<3&&SystemClock.uptimeMillis()<deadline){String now=bounds("ㄊ").toShortString()+bounds("↵").toShortString();stable=now.equals(previous)?stable+1:0;previous=now;Thread.sleep(50);}assertEquals("keyboard layout settled",3,stable);
   for(String key:new String[]{"ㄊ","ㄧ","ㄢ","空白","↵","⌫"}){Rect r=bounds(key);assertFalse(r.isEmpty());keys.put(key,r);geometry.put(new JSONObject().put("key",key).put("rect",r.toShortString()));}save("benchmark-key-bounds.json",geometry.toString());
   for(int n=0;n<20;n++)for(String key:new String[]{"ㄊ","ㄧ","ㄢ","空白","↵"})measuredTap(key,keys.get(key));
   assertText("天".repeat(20));capture("rapid-twenty");
   for(int n=0;n<10;n++)measuredTap("⌫",keys.get("⌫"));
   assertText("天".repeat(10));capture("rapid-after-delete");assertEquals(110,samples.length());completed=true;
  }finally{
   save("rapid-latency.json",new JSONObject().put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256")).put("measurement","synchronous physical down/up injection through IME main-thread idle; editor readback outside timed region").put("completed",completed).put("samples",samples).toString());
   capture("final-observer");
  }
 }
}
