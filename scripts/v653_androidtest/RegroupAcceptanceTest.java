package com.ime.sandbox.testpad;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.accessibility.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class RegroupAcceptanceTest {
 Instrumentation inst;UiAutomation ui;UiDevice device;File out;JSONObject receipt=new JSONObject();
 AccessibilityNodeInfo find(AccessibilityNodeInfo n,String text,String id){
  if(n==null)return null;
  if(text!=null&&text.equals(String.valueOf(n.getText()))&&n.isVisibleToUser())return n;
  if(id!=null&&id.equals(n.getViewIdResourceName()))return n;
  for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=find(n.getChild(i),text,id);if(hit!=null)return hit;}return null;
 }
 AccessibilityNodeInfo node(String text,String id){for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo hit=find(w.getRoot(),text,id);if(hit!=null)return hit;}return null;}
 AccessibilityNodeInfo await(String text,String id)throws Exception{long end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){AccessibilityNodeInfo n=node(text,id);if(n!=null)return n;Thread.sleep(100);}throw new AssertionError("node unavailable "+text+" "+id);}
 void tap(String text)throws Exception{long end=SystemClock.uptimeMillis()+30000;Rect r=new Rect();while(SystemClock.uptimeMillis()<end){AccessibilityNodeInfo n=await(text,null);n.getBoundsInScreen(r);if(!r.isEmpty()&&r.centerX()>0&&r.centerY()>0&&r.centerY()<device.getDisplayHeight()){device.click(r.centerX(),r.centerY());Thread.sleep(400);return;}Thread.sleep(200);}throw new AssertionError("key has no displayed coordinates "+text+" "+r);}
 String field()throws Exception{return String.valueOf(await(null,"com.ime.sandbox.testpad:id/unused").getText());}
 String readField(){for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo n=input(w.getRoot());if(n!=null)return String.valueOf(n.getText());}throw new AssertionError("field missing");}
 AccessibilityNodeInfo input(AccessibilityNodeInfo n){if(n==null)return null;if("test_input".equals(String.valueOf(n.getContentDescription())))return n;for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo h=input(n.getChild(i));if(h!=null)return h;}return null;}
 void screenshot(String name)throws Exception{Bitmap b=ui.takeScreenshot();assertNotNull(b);try(FileOutputStream f=new FileOutputStream(new File(out,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,f);}b.recycle();}
 void launch()throws Exception{
  // Package clear/install may asynchronously reset the system IME selection.
  // Read the actual selection and bind this APK before testing its page/hint.
  String ime="com.simon.voiceime/.SimonIMEService";
  if(!device.executeShellCommand("settings get secure default_input_method").trim().equals(ime)){
   device.executeShellCommand("ime enable "+ime);device.executeShellCommand("ime set "+ime);
  }
  assertEquals("test must target the installed IME",ime,device.executeShellCommand("settings get secure default_input_method").trim());
  Intent i=new Intent(inst.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);inst.getTargetContext().startActivity(i);AccessibilityNodeInfo n=null;long deadline=SystemClock.uptimeMillis()+45000;while(n==null&&SystemClock.uptimeMillis()<deadline){for(AccessibilityWindowInfo w:ui.getWindows()){n=input(w.getRoot());if(n!=null)break;}if(n==null)Thread.sleep(200);}assertNotNull("testpad input window must appear",n);n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);n.performAction(AccessibilityNodeInfo.ACTION_CLICK);// Wait for an actual page control before choosing its navigation path.
  // A new editor can briefly expose neither keyboard during its transition.
  long pageDeadline=SystemClock.uptimeMillis()+45000;
  while(node("ㄗ",null)==null&&SystemClock.uptimeMillis()<pageDeadline){
   if(node("注",null)!=null){tap("注");break;}
   Thread.sleep(100);
  }
  await("ㄗ",null);ui.waitForIdle(500,10000);}
 void slip2Keys()throws Exception{
  for(String k:new String[]{"ㄗ","ㄞ","ˋ","ㄑ","ㄧ","ㄝ","空白","ㄏ","ㄨ","ㄢ"})tap(k);
  AccessibilityNodeInfo n=await("ㄓ",null);Rect r=new Rect();n.getBoundsInScreen(r);
  device.click(r.centerX()-(int)(r.width()*.44),r.centerY()+(int)(r.height()*.03));Thread.sleep(400);
  for(String k:new String[]{"ㄉ","ㄜ","˙","ㄈ","ㄤ","ㄕ","ˋ","ㄕ","ㄤ"})tap(k);
  String reading=await(null,"com.simon.voiceime:id/boPhoneticPreview").getText().toString();
  assertEquals("literal slip uses ㄓ, not a substituted tone","ㄗㄞˋㄑㄧㄝˉㄏㄨㄢㄓㄉㄜ˙ㄈㄤㄕˋㄕㄤ",reading);
  receipt.put("slip2_literal_reading",reading);screenshot("slip2-literal");
 }
 void mainKeys()throws Exception{for(String k:new String[]{"ㄗ","ㄞ","ˋ","ㄑ","ㄧ","ㄝ","空白","ㄏ","ㄨ","ㄢ","ˋ","ㄉ","ㄜ","˙","ㄈ","ㄤ","ㄕ","ˋ"})tap(k);assertEquals("physical taps must deliver the fixture reading","ㄗㄞˋㄑㄧㄝˉㄏㄨㄢˋㄉㄜ˙ㄈㄤㄕˋ",await(null,"com.simon.voiceime:id/boPhoneticPreview").getText().toString());}
 void cursor(int boundary,boolean drag)throws Exception{
  AccessibilityNodeInfo n=await(null,"com.simon.voiceime:id/boStreamPreview");Rect r=new Rect();n.getBoundsInScreen(r);
  android.util.DisplayMetrics dm=inst.getTargetContext().getResources().getDisplayMetrics();Paint p=new Paint();p.setTextSize(21*dm.scaledDensity);
  String text=n.getText().toString();int utf=text.offsetByCodePoints(0,boundary);int x=r.left+(int)(12*dm.density+p.measureText(text.substring(0,utf)));int y=r.centerY();
  if(drag)assertTrue(device.swipe(r.left+(int)(12*dm.density+p.measureText(text.substring(0,text.offsetByCodePoints(0,Math.min(4,text.codePointCount(0,text.length())))))) ,y,x,y,20));
  else assertTrue(device.click(x,y));Thread.sleep(300);
  String reading=await(null,"com.simon.voiceime:id/boPhoneticPreview").getText().toString();assertTrue("aligned phonetic caret "+reading,reading.contains("│"));receipt.put("boundary"+boundary+"_reading",reading);
 }
 void choose(String text)throws Exception{
  for(int tries=0;tries<40;tries++){
   AccessibilityNodeInfo n=node(text,null);if(n!=null){Rect r=new Rect();n.getBoundsInScreen(r);device.click(r.centerX(),r.centerY());Thread.sleep(200);return;}
   AccessibilityNodeInfo bar=await(null,"com.simon.voiceime:id/boCandidateScroll");Rect r=new Rect();bar.getBoundsInScreen(r);device.swipe(r.right-10,r.centerY(),r.left+10,r.centerY(),15);Thread.sleep(100);
  }throw new AssertionError("candidate not reachable: "+text);
 }
 @Test public void hintOnce()throws Exception{
  inst=InstrumentationRegistry.getInstrumentation();ui=inst.getUiAutomation();device=UiDevice.getInstance(inst);AccessibilityServiceInfo si=ui.getServiceInfo();si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(si);
  out=new File(inst.getTargetContext().getFilesDir(),"v650");out.mkdirs();
  launch();String hint="請看著鍵盤，每個字打完整注音和聲調（一聲按空白鍵）；打錯時把游標拉過去選。";
  assertNotNull("first zhuyin page must show the complete-typing hint",node(hint,null));screenshot("hint-shown");
  tap("ㄗ");assertNull("first key dismisses hint",node(hint,null));screenshot("hint-dismissed");
  tap("EN");tap("注");assertNull("hint never returns",node(hint,null));
  Intent settings=new Intent().setComponent(new ComponentName("com.simon.voiceime","com.simon.voiceime.SettingsActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  inst.getTargetContext().startActivity(settings);await(hint,null);screenshot("settings-hint-line");device.pressBack();
  launch();assertNull("new editor never repeats hint",node(hint,null));
 }
 @Test public void realTapsDragRegroupCommitAndRetype()throws Exception{
  inst=InstrumentationRegistry.getInstrumentation();ui=inst.getUiAutomation();device=UiDevice.getInstance(inst);AccessibilityServiceInfo si=ui.getServiceInfo();si.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(si);
  out=new File(inst.getTargetContext().getFilesDir(),"v650");out.mkdirs();receipt.put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256"));
  try{
   launch();mainKeys();AccessibilityNodeInfo n=await(null,"com.simon.voiceime:id/boStreamPreview");Rect r=new Rect();n.getBoundsInScreen(r);float d=inst.getTargetContext().getResources().getDisplayMetrics().density;
   receipt.put("tap_metrics", "bounds="+r+" density="+d+" scaled="+inst.getTargetContext().getResources().getDisplayMetrics().scaledDensity+" x="+(r.left+(int)((12+21*1.5)*d)));screenshot("before-character-tap");device.click(r.left+(int)((12+21*1.5)*d),r.centerY());Thread.sleep(300);receipt.put("after_tap_reading",await(null,"com.simon.voiceime:id/boPhoneticPreview").getText());screenshot("after-character-tap");choose("在｜切換");screenshot("character-choice");
   tap("↵");assertEquals("在切換的方式",readField());
   for(int b:new int[]{1,2}){
    launch();mainKeys();choose("再切");assertTrue("must begin with wrong grouping",await(null,"com.simon.voiceime:id/boStreamPreview").getText().toString().startsWith("再切"));screenshot("typed-"+b);cursor(b,b==1);screenshot("cursor-options-"+b);
    choose("在｜切換");screenshot("chosen-"+b);tap("↵");assertEquals("在切換的方式",readField());receipt.put("committed"+b,readField());screenshot("committed-"+b);
   }
   launch();mainKeys();cursor(1,false);choose("在｜切換");
   cursor(2,false);tap("⌫");screenshot("backspace-retype");tap("ㄏ");tap("ㄨ");tap("ㄟ");tap("ˊ");screenshot("retype-draft");tap("取消");screenshot("retype-cancelled");
   assertEquals("在切換的方式",await(null,"com.simon.voiceime:id/boStreamPreview").getText().toString());
   cursor(2,false);tap("⌫");tap("ㄏ");tap("ㄨ");tap("ㄟ");tap("ˊ");choose("回");tap("↵");assertEquals("在回換的方式",readField());receipt.put("retyped_commit",readField());screenshot("retype-committed");
   launch();slip2Keys();String literal=await(null,"com.simon.voiceime:id/boStreamPreview").getText().toString();
   receipt.put("slip2_literal_preview",literal);assertTrue(literal.endsWith("的方式上"));String repairedExpected=literal.substring(0,2)+"換的方式上";
   cursor(3,false);assertEquals("placing cursor cannot replace literal",literal,await(null,"com.simon.voiceime:id/boStreamPreview").getText().toString());
   await("取消",null);screenshot("slip2-bubble-options");choose("換");screenshot("slip2-repair-chosen");
   assertEquals("only slipped syllables change",repairedExpected,await(null,"com.simon.voiceime:id/boStreamPreview").getText().toString());
   tap("↵");assertEquals(repairedExpected,readField());receipt.put("slip2_commit",readField());screenshot("slip2-committed");
   receipt.put("verdict","PASS");
  }catch(Throwable t){receipt.put("verdict","FAIL");receipt.put("error",t.toString());screenshot("failure");throw t;}
  finally{try(FileOutputStream f=new FileOutputStream(new File(out,"receipt.json"))){f.write(receipt.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
 }
}
