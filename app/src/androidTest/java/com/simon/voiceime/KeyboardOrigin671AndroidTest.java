package com.simon.voiceime;
import junit.framework.TestCase;import androidx.test.platform.app.InstrumentationRegistry;import android.view.*;import android.widget.*;
/** Controlled public touch router: physical position stays fixed while local origin lags. */
public class KeyboardOrigin671AndroidTest extends TestCase {
 public void testRawPositionSurvivesStaleOrigin()throws Exception{
  final String[] selected={null};android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
  inst.runOnMainSync(()->{
   KeyboardTouchLayout k=new KeyboardTouchLayout(inst.getTargetContext(),null);k.setOrientation(LinearLayout.VERTICAL);
   for(String name:new String[]{"ㄟ","ㄠ"}){TextView v=new TextView(inst.getTargetContext()){@Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_UP)selected[0]=name;return true;}};v.setTag("key:"+name);k.addView(v,new LinearLayout.LayoutParams(100,69));}
   k.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(138,View.MeasureSpec.EXACTLY));k.layout(0,0,100,138);
   long t=android.os.SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(t,t,0,50,103,0),u=MotionEvent.obtain(t,t+20,1,50,103,0);d.offsetLocation(0,-69);u.offsetLocation(0,-69);k.dispatchTouchEvent(d);k.dispatchTouchEvent(u);d.recycle();u.recycle();
  });assertEquals("raw physical bottom key survives local origin lag","ㄠ",selected[0]);
 }
}
