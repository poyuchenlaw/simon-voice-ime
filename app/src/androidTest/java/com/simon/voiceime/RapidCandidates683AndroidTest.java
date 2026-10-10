package com.simon.voiceime;

import android.os.SystemClock;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Recorded screen taps at 30/50/80ms: no idle barrier inside a burst. */
public class RapidCandidates683AndroidTest extends MainStall683AndroidTest {
    void inject(Rect hit)throws Exception {
        long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,hit.centerX(),hit.centerY(),0);
        MotionEvent up=MotionEvent.obtain(t,t+10,1,hit.centerX(),hit.centerY(),0);
        try{assertTrue(ui.injectInputEvent(down,false));Thread.sleep(10);assertTrue(ui.injectInputEvent(up,false));}finally{down.recycle();up.recycle();}
    }
    public void testFastCandidateBurstsStayConsistent()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        keys(KEYS);Thread.sleep(400);inst.waitForIdleSync();JSONArray bursts=new JSONArray();
        for(int cadence:new int[]{30,50,80}){
            final Rect[] hit={new Rect(),new Rect()};final String[] label={null,null};final String before=shown();
            inst.runOnMainSync(()->{
                int count=0;for(int n=0;n<characters.getChildCount()&&count<2;n++){View v=characters.getChildAt(n);if(v instanceof TextView&&v.isEnabled()&&!before.endsWith(((TextView)v).getText().toString())){
                    if(!v.getLocalVisibleRect(hit[count]))continue;int[] at=new int[2];v.getLocationOnScreen(at);hit[count].offset(at[0],at[1]);label[count++]=((TextView)v).getText().toString();
                }}
            });
            assertNotNull("first visible alternative character",label[0]);assertNotNull("second visible alternative character",label[1]);assertFalse("distinct candidate taps",label[0].equals(label[1]));assertEquals(1,label[0].codePointCount(0,label[0].length()));
            JSONArray taps=new JSONArray();long start=SystemClock.uptimeMillis();
            for(int n=0;n<2;n++){
                long due=start+n*cadence,wait=due-SystemClock.uptimeMillis();if(wait>0)Thread.sleep(wait);
                taps.put(SystemClock.uptimeMillis());inject(hit[n]);
            }
            Thread.sleep(400);inst.waitForIdleSync();
            String expected=before.substring(0,before.offsetByCodePoints(before.length(),-1))+label[0];
            assertEquals("burst only replaces the last character",expected,shown());assertEquals("editor and preview agree",expected,editorText(await("test_input")));
            final String[] rows={null};inst.runOnMainSync(()->{
                rows[0]=rowSnapshot().toString();
                for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})for(int n=0;n<row.getChildCount();n++){
                    View v=row.getChildAt(n);if(v.getTag() instanceof ZhuyinInputController.TextChoice){
                        ZhuyinInputController.TextChoice c=(ZhuyinInputController.TextChoice)v.getTag();
                        assertTrue("settled candidate enabled",v.isEnabled());assertEquals("no previous-text transaction remains",expected,c.witness);
                    }
                }
            });
            bursts.put(new JSONObject().put("cadence_ms",cadence).put("down_uptime_ms",taps).put("before",before).put("labels",new JSONArray(java.util.Arrays.asList(label))).put("expected",expected).put("actual",shown()).put("rows",new JSONArray(rows[0])));save("rapid-candidates.json",bursts.toString());
        }
        String last=shown();tap("↵");Thread.sleep(400);inst.waitForIdleSync();assertEquals("commit has no extra burst residue",last,editorText(await("test_input")));assertEquals("preview cleared after commit","",shown());readback("editor-final.json");
    }
}
