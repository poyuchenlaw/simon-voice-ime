package com.simon.voiceime;
import android.graphics.Rect;import android.os.SystemClock;import android.view.*;import org.json.*;import java.io.File;import java.util.*;import java.util.concurrent.*;import androidx.test.platform.app.InstrumentationRegistry;
/** Identical physical input and render-commit observer on both release APKs. */
public class KeyLatency670AndroidTest extends TextRows669AndroidTest {
 ViewTreeObserver.OnPreDrawListener keyPreObserver;volatile CountDownLatch draw;volatile String priorKeys="";volatile long downNs,commitNs;java.lang.reflect.Field controller;Object service;JSONArray samples=new JSONArray();
 String keys(){try{return ((ZhuyinInputController)controller.get(service)).sentenceKeys();}catch(Exception e){throw new RuntimeException(e);}}
 public void testKeyToFrame()throws Exception {
  ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();startEndpoint();inst.runOnMainSync(()->{try{bindWindow();android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();service=c;controller=SimonIMEService.class.getDeclaredField("zhuyinInput");controller.setAccessible(true);assertTrue(root.isHardwareAccelerated());keyPreObserver=()->{CountDownLatch pending=draw;if(pending!=null&&!keys().equals(priorKeys)){draw=null;root.getViewTreeObserver().registerFrameCommitCallback(()->{commitNs=System.nanoTime();pending.countDown();});}return true;};root.getViewTreeObserver().addOnPreDrawListener(keyPreObserver);}catch(Exception e){throw new RuntimeException(e);}});
  int length=Integer.parseInt(InstrumentationRegistry.getArguments().getString("length","15"));String[] base={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};Map<Character,Rect> bounds=new HashMap<>();
  for(String s:base)for(char cp:s.toCharArray())if(!bounds.containsKey(cp)){Rect r=new Rect();await(cp==' '?"空白":String.valueOf(cp)).getBoundsInScreen(r);bounds.put(cp,r);}
  Thread.sleep(500);int step=0;
  for(int n=0;n<length;n++)for(char cp:base[n%base.length].toCharArray()){
   final String[] before={null};CountDownLatch pending=new CountDownLatch(1);inst.runOnMainSync(()->before[0]=keys());priorKeys=before[0];draw=pending;Rect r=bounds.get(cp);long t=SystemClock.uptimeMillis();downNs=System.nanoTime();MotionEvent d=MotionEvent.obtain(t,t,0,r.centerX(),r.centerY(),0),u=MotionEvent.obtain(t,t+20,1,r.centerX(),r.centerY(),0);assertTrue(ui.injectInputEvent(d,false)&&ui.injectInputEvent(u,false));d.recycle();u.recycle();
   assertTrue("updated controller must reach committed frame",pending.await(10,TimeUnit.SECONDS));double ms=(commitNs-downNs)/1e6;samples.put(new JSONObject().put("key",step++).put("character_index",n).put("latency_ms",ms).put("down_ns",downNs).put("frame_commit_ns",commitNs));long rest=197-(SystemClock.uptimeMillis()-t);if(rest>0)Thread.sleep(rest);
  }
  save("key-latency.json",new JSONObject().put("characters",length).put("cadence_ms",197).put("clock","System.nanoTime down to updated-controller pre-draw registration, same-frame commit callback").put("samples",samples).toString());
 }
 @Override protected void tearDown()throws Exception{if(out!=null)save("partial-key-latency.json",samples.toString());stop=true;if(endpoint!=null)endpoint.close();if(server!=null)server.join(5000);if(root!=null&&keyPreObserver!=null)inst.runOnMainSync(()->root.getViewTreeObserver().removeOnPreDrawListener(keyPreObserver));super.tearDown();}
}
