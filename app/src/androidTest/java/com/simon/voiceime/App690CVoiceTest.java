package com.simon.voiceime;
import junit.framework.TestCase;
import android.view.*;import android.widget.*;import android.graphics.*;
/** Device execution belongs to the locked integration/X1 phase; C only compiles this test. */
public class App690CVoiceTest extends TestCase {
 private android.app.Instrumentation inst(){return androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();}
 private final int[] keys={R.id.btnSettings,R.id.btnFullWidth,R.id.btnHalfWidth,R.id.btnClipboard,R.id.btnCommands,R.id.btnMode,R.id.btnSwitchIME,R.id.btnSpace,R.id.btnComma,R.id.btnPeriod,R.id.btnBackspace,R.id.btnEnter,R.id.btnMic};
 public void testVoiceRolesFontClassificationAndIconBounds(){
  inst().runOnMainSync(()->{
   var c=inst().getTargetContext();View root=LayoutInflater.from(c).inflate(R.layout.keyboard_view,null);for(int id:keys)root.findViewById(id).setTag(R.id.keyboard_visible_inset,0);KeyboardTheme.apply(c,root);
   float sd=c.getResources().getDisplayMetrics().scaledDensity;
   for(int id:keys){TextView v=root.findViewById(id);String label=v.getText().toString();v.setTypeface(KeyboardTypeface.forLabel(c,label,true));assertTrue(v.getBackground() instanceof KeyboardKeycaps.Cap);assertEquals(0f,((KeyboardKeycaps.Cap)v.getBackground()).insetDp,.01f);
    if("function".equals(KeyboardLabels.labelClass(label,false))){assertTrue(KeyboardTypeface.bundled(v.getTypeface()));assertEquals(12f,v.getTextSize()/sd,.01f);}
    else {assertTrue(label,new Paint().hasGlyph(label));assertEquals("␣".equals(label),KeyboardTypeface.bundled(v.getTypeface()));}
   }
   assertEquals("function",KeyboardLabels.labelClass("，",false));assertEquals("function",KeyboardLabels.labelClass("。",false));assertEquals("icon",KeyboardLabels.labelClass("␣",false));
   for(int id:new int[]{R.id.statusText,R.id.previewText,R.id.pendingVoiceCandidateNotice})assertFalse(KeyboardTypeface.bundled(((TextView)root.findViewById(id)).getTypeface()));
  });
 }
 public void testEveryVoiceKeyRoutesOneDownWithoutRepeat(){
  inst().runOnMainSync(()->{
   var c=inst().getTargetContext();View root=LayoutInflater.from(c).inflate(R.layout.keyboard_view,null);KeyboardTouchLayout voice=root.findViewById(R.id.voiceKeyboard);for(int id:keys)root.findViewById(id).setTag(R.id.keyboard_visible_inset,0);KeyboardTheme.apply(c,root);
   int width=Math.round(400*c.getResources().getDisplayMetrics().density);voice.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));voice.layout(0,0,width,voice.getMeasuredHeight());
   for(int id:keys){View key=voice.findViewById(id);final int[] downs={0};key.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)downs[0]++;return true;});Rect box=new Rect();key.getDrawingRect(box);voice.offsetDescendantRectToMyCoords(key,box);int before=KeyboardHaptics.requestCount();long time=android.os.SystemClock.uptimeMillis();
    for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(time,time+20,action,box.exactCenterX(),box.exactCenterY(),0);try{voice.dispatchTouchEvent(e);}finally{e.recycle();}}
    assertEquals("one delivered down",1,downs[0]);assertEquals("one request per physical down",1,KeyboardHaptics.requestCount()-before);
   }
  });
 }
}
