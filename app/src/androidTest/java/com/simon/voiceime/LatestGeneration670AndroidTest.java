package com.simon.voiceime;
import android.os.*;import android.widget.TextView;import org.json.*;import java.util.concurrent.*;
/** Real isolated-worker replies and deliberate late old-generation delivery. */
public class LatestGeneration670AndroidTest extends TextRows670AndroidTest {
 boolean collectDown;Object delayService;java.lang.reflect.Field delayGeneration;java.util.Map<Long,Long> physicalDown=new java.util.concurrent.ConcurrentHashMap<>();
 @Override void tap(android.graphics.Rect rect){long down=System.nanoTime();super.tap(rect);if(collectDown){final long[] generation={0};inst.runOnMainSync(()->{try{generation[0]=delayGeneration.getLong(delayService);}catch(Exception e){throw new RuntimeException(e);}});physicalDown.put(generation[0],down);}}

 public void testLateReplyCannotOverwriteNewText()throws Exception{
  begin();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白"})tap(k);awaitCandidateCount(characters,2);
  long old=(Long)words.getTag(id("boWordCandidateItems"));TextView stale=(TextView)characters.getChildAt(1);
  android.content.Context context=row1.getContext();while(!(context instanceof SimonIMEService)&&context instanceof android.content.ContextWrapper)context=((android.content.ContextWrapper)context).getBaseContext();final Object service=context;
  java.lang.reflect.Field endpoint=SimonIMEService.class.getDeclaredField("textCandidateReplies");endpoint.setAccessible(true);Messenger replies=(Messenger)endpoint.get(service);
  for(String k:new String[]{"ㄊ","ㄧ","ㄢ","空白"})tap(k);assertEquals("今天",shown());awaitCandidateCount(characters,2);
  long latest=(Long)words.getTag(id("boWordCandidateItems"));assertTrue(latest>old);
  Bundle data=new Bundle();data.putLong("generation",old);data.putString("keys","ㄐㄧㄣ ");data.putString("raw","金");data.putInt("boundary",-1);data.putInt("caret",-1);data.putParcelableArrayList("choices",new java.util.ArrayList<Bundle>());
  Message message=Message.obtain(null,2);message.setData(data);replies.send(message);inst.waitForIdleSync();
  assertEquals("old worker response cannot clear latest menu",latest,((Long)words.getTag(id("boWordCandidateItems"))).longValue());assertTrue(characters.getChildCount()>=2);
  inst.runOnMainSync(()->stale.performClick());assertEquals("今天",shown());
  assertTrue("worker native runtime must be a separate process",((Integer)characters.getTag())!=android.os.Process.myPid());
  tap("↵");assertEquals("今天",String.valueOf(await("test_input").getText()));
  save("generation-race.json",new JSONObject().put("old_generation",old).put("latest_generation",latest).put("stale_reply_ignored",true).put("stale_click_ignored",true).put("separate_native_process",true).toString());
 }
 public void testRapidBurstMatchesLatestComposition()throws Exception{
  begin();java.util.Map<Character,android.graphics.Rect> bounds=new java.util.HashMap<>();String sequence="ㄐㄧㄣ ㄊㄧㄢ ";
  for(char k:sequence.toCharArray())if(!bounds.containsKey(k)){android.graphics.Rect r=new android.graphics.Rect();await(k==' '?"空白":String.valueOf(k)).getBoundsInScreen(r);bounds.put(k,r);}
  for(int round=0;round<5;round++)for(char k:sequence.toCharArray()){
   android.graphics.Rect r=bounds.get(k);long t=SystemClock.uptimeMillis();android.view.MotionEvent d=android.view.MotionEvent.obtain(t,t,0,r.centerX(),r.centerY(),0),u=android.view.MotionEvent.obtain(t,t+10,1,r.centerX(),r.centerY(),0);
   assertTrue(ui.injectInputEvent(d,false)&&ui.injectInputEvent(u,false));d.recycle();u.recycle();Thread.sleep(25);
  }
  inst.waitForIdleSync();awaitCandidateCount(characters,2);
  android.content.Context context=row1.getContext();while(!(context instanceof SimonIMEService)&&context instanceof android.content.ContextWrapper)context=((android.content.ContextWrapper)context).getBaseContext();final Object service=context;
  java.lang.reflect.Field generation=SimonIMEService.class.getDeclaredField("textCandidateGeneration"),control=SimonIMEService.class.getDeclaredField("zhuyinInput");generation.setAccessible(true);control.setAccessible(true);
  inst.runOnMainSync(()->{try{
   assertEquals("row belongs to latest input",generation.getLong(service),((Long)words.getTag(id("boWordCandidateItems"))).longValue());
   ZhuyinInputController c=(ZhuyinInputController)control.get(service);java.lang.reflect.Field cache=ZhuyinInputController.class.getDeclaredField("textChoiceCache");cache.setAccessible(true);
   for(Object entry:(java.util.List<?>)cache.get(c)){ZhuyinInputController.TextChoice x=(ZhuyinInputController.TextChoice)entry;assertEquals(c.sentenceKeys(),x.keys);assertEquals(c.previewText(),x.witness);}
  }catch(Exception e){throw new RuntimeException(e);}});
  String preview=shown();assertFalse(preview.isEmpty());tap("↵");assertEquals(preview,String.valueOf(await("test_input").getText()));
  save("rapid-generation.json",new JSONObject().put("injected_keys",40).put("minimum_interval_ms",25).put("latest_menu_matches_keys_and_text",true).put("enter_matches_preview",true).toString());
 }
 android.view.ViewTreeObserver.OnPreDrawListener appearancePreObserver;
 @Override protected void tearDown()throws Exception{if(root!=null&&appearancePreObserver!=null)inst.runOnMainSync(()->root.getViewTreeObserver().removeOnPreDrawListener(appearancePreObserver));super.tearDown();}
 public void testCandidateAppearanceDelay()throws Exception{
  begin();android.content.Context context=row1.getContext();while(!(context instanceof SimonIMEService)&&context instanceof android.content.ContextWrapper)context=((android.content.ContextWrapper)context).getBaseContext();delayService=context;delayGeneration=SimonIMEService.class.getDeclaredField("textCandidateGeneration");delayGeneration.setAccessible(true);collectDown=true;
  JSONArray samples=new JSONArray();java.util.Set<Long> seen=new java.util.HashSet<>();CountDownLatch appeared=new CountDownLatch(1);
  appearancePreObserver=()->{Object g=words.getTag(id("boWordCandidateItems")),t=words.getTag();if(characters.getChildCount()>0&&g instanceof Long&&t instanceof Long&&seen.add((Long)g)){long generation=(Long)g,start=(Long)t;root.getViewTreeObserver().registerFrameCommitCallback(()->{try{samples.put(new JSONObject().put("generation",generation).put("frame_commit_ns",System.nanoTime()).put("enqueue_ns",start));appeared.countDown();}catch(Exception e){throw new RuntimeException(e);}});}return true;};
  inst.runOnMainSync(()->root.getViewTreeObserver().addOnPreDrawListener(appearancePreObserver));
  for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白","ㄊ","ㄧ","ㄢ","空白"})tap(k);
  assertTrue("candidate frame appears",appeared.await(10,TimeUnit.SECONDS));awaitCandidateCount(characters,2);Thread.sleep(500);
  JSONArray measured=new JSONArray();for(int n=0;n<samples.length();n++){JSONObject sample=samples.getJSONObject(n);Long down=physicalDown.get(sample.getLong("generation"));if(down!=null){sample.put("physical_down_ns",down);sample.put("candidate_frame_delay_ms",(sample.getLong("frame_commit_ns")-down)/1e6);measured.put(sample);}}assertTrue("physical DOWN matched to candidate frame",measured.length()>0);
  save("candidate-delay.json",new JSONObject().put("clock","physical DOWN System.nanoTime to matching-generation row2 hardware frame commit").put("samples",measured).put("threshold","record only").toString());
 }
}
