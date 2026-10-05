package com.simon.voiceime;
import android.view.*;import android.widget.*;import org.json.*;
/** Supplemental candidate DOWN-to-frame observer; primary cadence/clock unchanged. */
public class CandidateFrameDelay670AndroidTest extends KeyLatency670AndroidTest {
 android.view.ViewTreeObserver.OnPreDrawListener candidateObserver;JSONArray candidateSamples=new JSONArray();java.util.Set<Long> seen=new java.util.HashSet<>();java.lang.reflect.Field generation;Object owner;View wordRow;LinearLayout charRow;
 @Override void ready()throws Exception{
  super.ready();inst.runOnMainSync(()->{try{bindWindow();android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();owner=c;generation=SimonIMEService.class.getDeclaredField("textCandidateGeneration");generation.setAccessible(true);int wi=inst.getTargetContext().getResources().getIdentifier("boWordCandidateItems","id","com.simon.voiceime"),ci=inst.getTargetContext().getResources().getIdentifier("boCandidateItems","id","com.simon.voiceime");wordRow=root.findViewById(wi);charRow=root.findViewById(ci);
   candidateObserver=()->{try{Object g=wordRow.getTag(wi),request=wordRow.getTag();if(charRow.getChildCount()==0||!(g instanceof Long)||!(request instanceof Long)||downNs<=0||(Long)request<downNs||(Long)g!=generation.getLong(owner)||!seen.add((Long)g))return true;
    final long gen=(Long)g,down=downNs;root.getViewTreeObserver().registerFrameCommitCallback(()->{try{long frame=System.nanoTime();candidateSamples.put(new JSONObject().put("generation",gen).put("down_ns",down).put("candidate_frame_ns",frame).put("candidate_frame_delay_ms",(frame-down)/1e6));}catch(Exception e){throw new RuntimeException(e);}});
   }catch(Exception e){throw new RuntimeException(e);}return true;};root.getViewTreeObserver().addOnPreDrawListener(candidateObserver);
  }catch(Exception e){throw new RuntimeException(e);}});
 }
 @Override public void testKeyToFrame()throws Exception{
  super.testKeyToFrame();long until=android.os.SystemClock.uptimeMillis()+10000;final boolean[] latest={false};
  while(!latest[0]&&android.os.SystemClock.uptimeMillis()<until){inst.runOnMainSync(()->{try{Object g=wordRow.getTag(inst.getTargetContext().getResources().getIdentifier("boWordCandidateItems","id","com.simon.voiceime"));latest[0]=g instanceof Long&&(Long)g==generation.getLong(owner)&&charRow.getChildCount()>0&&candidateSamples.length()>0;}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(50);}
  assertTrue("latest candidate frame captured after typing",latest[0]);
 }
 @Override protected void tearDown()throws Exception{
  if(out!=null)save("candidate-frame-delay.json",new JSONObject().put("clock","physical DOWN System.nanoTime to latest-generation candidate row2 frame commit").put("samples",candidateSamples).put("threshold","record only").toString());
  if(root!=null&&candidateObserver!=null)inst.runOnMainSync(()->root.getViewTreeObserver().removeOnPreDrawListener(candidateObserver));super.tearDown();
 }
}
