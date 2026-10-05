package com.simon.voiceime;
import android.view.*;import android.os.*;import org.json.*;import java.io.File;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Actual IME window: demonstrate registration-before-capture versus registration-after-capture. */
public class FrameObserverCalibration670AndroidTest extends TextRows669AndroidTest {
 ViewTreeObserver.OnPreDrawListener pre;ViewTreeObserver.OnDrawListener late;
 private void freezeBlink(View v)throws Exception{if(v instanceof PreviewCursorView){java.lang.reflect.Field f=PreviewCursorView.class.getDeclaredField("blink");f.setAccessible(true);v.removeCallbacks((Runnable)f.get(v));}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)freezeBlink(g.getChildAt(i));}}
 private void calibration(boolean oldMustBeCurrent)throws Exception{
  ready();inst.waitForIdleSync();Thread.sleep(1500);out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();CountDownLatch early=new CountDownLatch(1),old=new CountDownLatch(1);AtomicInteger epoch=new AtomicInteger();int[] actual={0,0};long[] times=new long[2];AtomicBoolean scheduledEarly=new AtomicBoolean(),scheduledOld=new AtomicBoolean();
  inst.runOnMainSync(()->{try{bindWindow();freezeBlink(root);pre=()->{int n=epoch.incrementAndGet();if(scheduledEarly.compareAndSet(false,true))root.getViewTreeObserver().registerFrameCommitCallback(()->{actual[0]=epoch.get();times[0]=System.nanoTime();early.countDown();});return true;};late=()->{if(scheduledOld.compareAndSet(false,true))root.getViewTreeObserver().registerFrameCommitCallback(()->{actual[1]=epoch.get();times[1]=System.nanoTime();old.countDown();});};root.getViewTreeObserver().addOnPreDrawListener(pre);root.getViewTreeObserver().addOnDrawListener(late);root.invalidate();}catch(Exception failure){throw new RuntimeException(failure);}});
  assertTrue("current-frame callback",early.await(10,TimeUnit.SECONDS));Thread.sleep(200);inst.runOnMainSync(()->root.invalidate());assertTrue("late registration needs later frame",old.await(10,TimeUnit.SECONDS));
  save("observer-calibration.json",new JSONObject().put("pre_draw_callback_epoch",actual[0]).put("on_draw_callback_epoch",actual[1]).put("early_ns",times[0]).put("late_ns",times[1]).put("extra_wait_ms",(times[1]-times[0])/1e6).put("old_assertion",oldMustBeCurrent).toString());
  if(oldMustBeCurrent)assertEquals("OLD observer incorrectly claims current frame",actual[0],actual[1]);else{assertEquals("pre-draw callback belongs to first frame",1,actual[0]);assertTrue("onDraw callback belongs to a later frame",actual[1]>actual[0]);}
 }
 public void testOldOnDrawClaimsCurrentFrame()throws Exception{calibration(true);}
 public void testPreDrawUsesCurrentFrame()throws Exception{calibration(false);}
 @Override protected void tearDown()throws Exception{if(root!=null)inst.runOnMainSync(()->{if(pre!=null)root.getViewTreeObserver().removeOnPreDrawListener(pre);if(late!=null)root.getViewTreeObserver().removeOnDrawListener(late);});super.tearDown();}
}
