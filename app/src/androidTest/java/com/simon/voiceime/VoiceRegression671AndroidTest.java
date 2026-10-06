package com.simon.voiceime;
import androidx.test.platform.app.InstrumentationRegistry;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.io.File;
import java.util.*;
import org.json.JSONObject;
/** Voice-fix regression: real Simon phonetic keys, actual public touch events and installed IME. */
public class VoiceRegression671AndroidTest extends FastTyping671AndroidTest {
 public void testSimonKeys200()throws Exception{replay(false);}
 public void testSimonRaw200Observation()throws Exception{replay(true);}
 void replay(boolean observe)throws Exception{
  ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();startEndpoint();
  String offered=InstrumentationRegistry.getArguments().getString("key_sequence");
  assertNotNull(offered);assertEquals(200,offered.length());
  Map<Character,Rect> bounds=new HashMap<>();
  for(char key:offered.toCharArray())if(!bounds.containsKey(key)){Rect r=new Rect();await(String.valueOf(key)).getBoundsInScreen(r);bounds.put(key,r);}
  ui.adoptShellPermissionIdentity("android.permission.INJECT_EVENTS");
  Class<?> manager=Class.forName("android.hardware.input.InputManagerGlobal");
  Object owner=manager.getDeclaredMethod("getInstance").invoke(null);
  java.lang.reflect.Method inject=manager.getDeclaredMethod("injectInputEvent",android.view.InputEvent.class,int.class);inject.setAccessible(true);
  long start=SystemClock.uptimeMillis();
  for(int i=0;i<offered.length();i++){
   long rest=start+i*197-SystemClock.uptimeMillis();if(rest>0)Thread.sleep(rest);
   Rect r=bounds.get(offered.charAt(i));long t=SystemClock.uptimeMillis();
   MotionEvent down=MotionEvent.obtain(t,t,0,r.centerX(),r.centerY(),0),up=MotionEvent.obtain(t,t+20,1,r.centerX(),r.centerY(),0);
   assertTrue((Boolean)inject.invoke(owner,down,0));assertTrue((Boolean)inject.invoke(owner,up,0));down.recycle();up.recycle();
  }
  inst.waitForIdleSync();Thread.sleep(3000);
  final String[] actual={null};inst.runOnMainSync(()->{try{bindWindow();android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();service=c;controller=SimonIMEService.class.getDeclaredField("zhuyinInput");controller.setAccessible(true);actual[0]=keys();}catch(Exception e){throw new RuntimeException(e);}});
  String golden=InstrumentationRegistry.getArguments().getString("expected_keys","");
  if(!observe)golden=offered;
  boolean checked=!golden.isEmpty(),matches=checked&&golden.equals(actual[0]);
  save(observe?"raw-200.json":"simon-200.json",new JSONObject().put("offered",200).put("cadence_ms",197).put("offered_keys",offered).put("actual_keys",actual[0]).put("expected_keys",golden).put("composition_key_count",actual[0].length()).put("matches",matches).put("observation_only",!checked).put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256")).toString());
  if(checked)assertEquals("native composition matches the external expected sequence",golden,actual[0]);
  else assertFalse("baseline consumer must produce a composition",actual[0].isEmpty());
 }
}
