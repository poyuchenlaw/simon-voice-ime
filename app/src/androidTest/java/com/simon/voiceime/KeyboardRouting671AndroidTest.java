package com.simon.voiceime;
import android.os.SystemClock;import android.view.*;import android.widget.*;import org.json.*;
public class KeyboardRouting671AndroidTest extends TextRows669AndroidTest {
 public void testStaleInputOriginKeepsPhysicalKey()throws Exception{
  ready();out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();
  final String[] actual={null};inst.runOnMainSync(()->{try{
   bindWindow();View target=root.findViewWithTag("key:ㄠ");assertNotNull(target);int[] at=new int[2],origin=new int[2];target.getLocationOnScreen(at);keyboard.getLocationOnScreen(origin);long t=SystemClock.uptimeMillis();float x=at[0]+target.getWidth()/2f,y=at[1]+target.getHeight()/2f;
   MotionEvent d=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0),u=MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,x,y,0);d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
   // Controlled reproduction of the 69px discrepancy from the prior trace:
   // raw coordinates remain physical; delivered local coordinates use old origin.
   d.offsetLocation(-origin[0],-origin[1]-69);u.offsetLocation(-origin[0],-origin[1]-69);keyboard.dispatchTouchEvent(d);keyboard.dispatchTouchEvent(u);d.recycle();u.recycle();
   android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField("zhuyinInput");f.setAccessible(true);actual[0]=((ZhuyinInputController)f.get(c)).sentenceKeys();
  }catch(Exception ex){throw new RuntimeException(ex);}});
  inst.waitForIdleSync();
  inst.runOnMainSync(()->{try{android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField("zhuyinInput");f.setAccessible(true);actual[0]=((ZhuyinInputController)f.get(c)).sentenceKeys();}catch(Exception ex){throw new RuntimeException(ex);}});
  save("routing-result.json",new JSONObject().put("expected_key","ㄠ").put("actual_keys",actual[0]).put("simulated_origin_lag_px",69).put("real_input_dispatcher",false).toString());assertEquals("raw physical intended key survives stale local origin","ㄠ",actual[0]);
 }
}
