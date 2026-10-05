package com.simon.voiceime;
import android.accessibilityservice.AccessibilityServiceInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.SystemClock;
public class TextRows669AndroidTest extends Layout668AndroidTest {
 void ready()throws Exception{
  inst=InstrumentationRegistry.getInstrumentation();ui=inst.getUiAutomation();AccessibilityServiceInfo f=ui.getServiceInfo();f.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(f);
  assertEquals("emulator only","1",shell("getprop ro.kernel.qemu").trim());
  preparePreferences();
  shell("ime enable com.simon.voiceime/.SimonIMEService");shell("ime set com.simon.voiceime/.SimonIMEService");shell("am force-stop com.ime.sandbox.testpad");shell("am start -W -n com.ime.sandbox.testpad/.MainActivity");tap("test_input");
  long end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(node("ㄗ")!=null)break;android.view.accessibility.AccessibilityNodeInfo switcher=node("注");if(switcher!=null)switcher.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);else {android.view.accessibility.AccessibilityNodeInfo input=node("test_input");if(input!=null){input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);}}Thread.sleep(300);}await("ㄗ");
 }
 void preparePreferences(){
  inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("server_url","http://127.0.0.1:8181").putString("auth_password","sandbox").putString("ai_sentence_mode","shadow").putBoolean("auto_correction",false).putBoolean("ime_auto_upload",false).putString("layout_mode","text_word_char").commit();
 }
 public void testNoPhoneticRow()throws Exception{
  ready();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白","ㄊ"})tap(k);
  inst.runOnMainSync(()->{try{bindWindow();}catch(Exception e){throw new RuntimeException(e);}});
  assertFalse("phonetic internal row must not be visible",row2.isShown());
 }
 @Override void tap(android.graphics.Rect r){
  long t=SystemClock.uptimeMillis();android.view.MotionEvent d=android.view.MotionEvent.obtain(t,t,0,r.centerX(),r.centerY(),0),u=android.view.MotionEvent.obtain(t,t+20,1,r.centerX(),r.centerY(),0);
  assertTrue(ui.injectInputEvent(d,true)&&ui.injectInputEvent(u,true));d.recycle();u.recycle();inst.waitForIdleSync();
 }
 public void testDestroyUnfinishedService()throws Exception{
  ready();inst.waitForIdleSync();Thread.sleep(1000);tap("ㄅ");inst.waitForIdleSync();
  final SimonIMEService[] service={null};
  inst.runOnMainSync(()->{try{
   bindWindow();android.content.Context c=row1.getContext();
   while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();
   assertTrue("visible window belongs to actual service",c instanceof SimonIMEService);service[0]=(SimonIMEService)c;
   java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField("zhuyinInput");f.setAccessible(true);
   ZhuyinInputController controller=(ZhuyinInputController)f.get(c);
   assertNotNull(controller);assertFalse("unfinished composition required",controller.sentenceKeys().isEmpty());
  }catch(Exception e){throw new RuntimeException(e);}});
  shell("logcat -c");
  // Real framework super.onDestroy re-enters finishViews with the view still up.
  // Switching IME can finish the view BEFORE destroying; this deterministic
  // lifecycle call locks down the reported active-view teardown ordering.
  inst.runOnMainSync(()->service[0].onDestroy());
  for(String name:new String[]{"zhuyinInput","sentencePhone","zhuyinWordIndex","touchLearning","layoutDiagnostics"}){
   java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField(name);f.setAccessible(true);assertNull("closed field "+name,f.get(service[0]));
  }
  assertFalse("teardown must have zero FATAL",shell("logcat -d -b crash").contains("FATAL EXCEPTION"));
 }
 public void testSwitchImeUnfinishedService()throws Exception{
  ready();inst.waitForIdleSync();Thread.sleep(1000);tap("ㄅ");
  final SimonIMEService[] service={null};
  inst.runOnMainSync(()->{try{bindWindow();android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();service[0]=(SimonIMEService)c;}catch(Exception e){throw new RuntimeException(e);}});
  shell("logcat -c");String other=null;
  for(String line:shell("ime list -s").split("\n"))if(!line.startsWith("com.simon.voiceime/")){other=line.trim();break;}
  assertNotNull(other);shell("ime set "+other);Thread.sleep(1500);
  java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField("zhuyinInput");f.setAccessible(true);assertNull("system switch called onDestroy",f.get(service[0]));
  assertFalse("switch must have zero FATAL",shell("logcat -d -b crash").contains("FATAL EXCEPTION"));
 }
}
