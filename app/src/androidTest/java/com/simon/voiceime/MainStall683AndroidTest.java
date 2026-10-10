package com.simon.voiceime;

import android.os.Looper;
import android.os.SystemClock;
import android.util.Printer;
import android.view.View;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Physical 41-key clicks, original dispatch monitor retained; no product hooks. */
public class MainStall683AndroidTest extends Preview679AndroidTest {
    static final String KEYS="ㄐㄧㄣ ㄊㄧㄢ ㄨㄛˇㄇㄣ˙ㄧˋㄑㄧˇㄊㄠˇㄌㄨㄣˋㄐㄧˋㄏㄨㄚˋ";
    static final class Dispatch {
        final String header; final long start; long end;
        Dispatch(String header,long start){this.header=header;this.start=start;}
    }
    final List<Dispatch> dispatches=new ArrayList<>();
    Printer original; java.lang.reflect.Field logging;
    volatile boolean sample; Thread sampler;
    final List<JSONObject> stacks=new java.util.concurrent.CopyOnWriteArrayList<>();
    void observe()throws Exception {
        logging=Looper.class.getDeclaredField("mLogging");logging.setAccessible(true);
        inst.runOnMainSync(()->{try{
            original=(Printer)logging.get(Looper.getMainLooper());assertNotNull("original main_stall detection must remain active",original);
            Looper.getMainLooper().setMessageLogging(new Printer(){Dispatch current;
                public void println(String line){
                    if(original!=null)original.println(line);
                    long now=SystemClock.elapsedRealtimeNanos();
                    if(line.startsWith(">>>>>"))current=new Dispatch(line,now);
                    else if(line.startsWith("<<<<<")&&current!=null){current.end=now;dispatches.add(current);current=null;}
                }
            });
        }catch(Exception e){throw new RuntimeException(e);}});
        if(Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("profile","false"))){
            sample=true;Thread main=Looper.getMainLooper().getThread();
            sampler=new Thread(()->{while(sample){try{
                StringBuilder stack=new StringBuilder();for(StackTraceElement frame:main.getStackTrace())stack.append(frame).append('\n');
                stacks.add(new JSONObject().put("uptime_ms",SystemClock.uptimeMillis()).put("stack",stack.toString()));Thread.sleep(10);
            }catch(Exception e){error=e;return;}}},"MainStallStackSampler");sampler.start();
        }
    }
    void finishObservation(boolean complete)throws Exception {
        sample=false;if(sampler!=null)sampler.join(3000);
        List<Dispatch> completed=new ArrayList<>();
        inst.runOnMainSync(()->{Looper.getMainLooper().setMessageLogging(original);completed.addAll(dispatches);});
        JSONArray timings=new JSONArray();int clicks=0,refreshes=0,violations=0;double maxClick=0,maxRefresh=0;
        for(Dispatch d:completed){double ms=(d.end-d.start)/1e6;boolean click=d.header.contains("android.view.View$PerformClick"),refresh=d.header.contains("SimonIMEService$23@");
            if(click){clicks++;maxClick=Math.max(maxClick,ms);}if(refresh){refreshes++;maxRefresh=Math.max(maxRefresh,ms);}
            if((click||refresh)&&ms>100)violations++;
            timings.put(new JSONObject().put("header",d.header).put("start_elapsed_ns",d.start).put("end_elapsed_ns",d.end).put("ms",ms).put("target",click||refresh));
        }
        save("dispatches.json",timings.toString());StringBuilder trace=new StringBuilder();for(JSONObject s:stacks)trace.append(s).append('\n');save("stacks.jsonl",trace.toString());
        save("stall-result.json",new JSONObject().put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256")).put("workload_completed",complete).put("sampled",sampler!=null).put("click_count",clicks).put("refresh_count",refreshes).put("max_click_ms",maxClick).put("max_refresh_ms",maxRefresh).put("violations",violations).put("preview",shown()).toString());
        if(!complete)return;
        assertNull("sampler completed",error);assertTrue("real click dispatches observed",clicks>=30);assertTrue("deferred $23 actually executed",refreshes>0);
        assertEquals("every key click and candidate refresh must be <=100ms; max click="+maxClick+", refresh="+maxRefresh,0,violations);
    }
    public void testPhysicalThirtyCharactersUnder100ms()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        assertEquals(1.3f,row1.getResources().getConfiguration().fontScale,.01f);
        observe();boolean complete=false;JSONArray stages=new JSONArray();
        try{
            for(int n=0;n<3;n++){
                keys(KEYS);Thread.sleep(300);inst.waitForIdleSync();
                // Pick the currently displayed native character with a real screen touch.
                final TextView[] choice={null};inst.runOnMainSync(()->{for(int i=0;i<characters.getChildCount();i++){View v=characters.getChildAt(i);if(v instanceof TextView&&v.isEnabled()){choice[0]=(TextView)v;break;}}});
                assertNotNull("character choice must be available",choice[0]);clickCandidate(choice[0]);
                stages.put(new JSONObject().put("round",n).put("preview",shown()).put("editor",editorText(await("test_input"))));save("workload-stages.json",stages.toString());
            }
            String typed=editorText(await("test_input"));assertTrue("at least30 actual Chinese editor characters: "+typed,typed.codePointCount(0,typed.length())>=30);
            tap("，");Thread.sleep(400);inst.waitForIdleSync();
            readback("editor-final.json");screenshot("typing-final");
            assertTrue("physical punctuation reached editor",editorText(await("test_input")).contains("，"));complete=true;
        }finally{finishObservation(complete);}
    }
}
