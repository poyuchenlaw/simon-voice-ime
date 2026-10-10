package com.simon.voiceime;

import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import androidx.test.platform.app.InstrumentationRegistry;

/** All dispatches during physical mode switch, app return and keyboard reopening. */
public class LifecycleStall683AndroidTest extends MainStall683AndroidTest {
    @Override void preparePreferences(){super.preparePreferences();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("t9_mode",false).putString("ai_sentence_mode","live").putBoolean("ai_sentence_auto_apply",true).commit();}
    final JSONArray stages=new JSONArray();
    void stage(String name)throws Exception {
        stages.put(new JSONObject().put("stage",name).put("elapsed_ns",SystemClock.elapsedRealtimeNanos()).put("uptime_ms",SystemClock.uptimeMillis()));
        save("lifecycle-stages.json",stages.toString());
    }
    void tagged(String tag)throws Exception {
        final Rect hit=new Rect();
        inst.runOnMainSync(()->{View v=root.findViewWithTag(tag);assertNotNull("physical key "+tag,v);assertTrue("visible physical key "+tag,v.getLocalVisibleRect(hit));int[] at=new int[2];v.getLocationOnScreen(at);hit.offset(at[0],at[1]);});
        tap(hit);
    }
    void reopen()throws Exception {
        Rect input=new Rect();await("test_input").getBoundsInScreen(input);
        // The editor fills the screen; its centre can move under an opening keyboard.
        int x=input.right-20,y=input.top+Math.min(100,input.height()/4);
        tap(new Rect(x-1,y-1,x+1,y+1));await("ㄗ");bind();awaitStableWindow();
    }
    @Override void finishObservation(boolean complete)throws Exception {
        sample=false;if(sampler!=null)sampler.join(3000);
        List<Dispatch> completed=new ArrayList<>();
        inst.runOnMainSync(()->{Looper.getMainLooper().setMessageLogging(original);completed.addAll(dispatches);});
        JSONArray timing=new JSONArray();double max=0,maxFrame=0,maxHandler=0;int frames=0,handlers=0,violations=0;
        for(Dispatch d:completed){double ms=(d.end-d.start)/1e6;max=Math.max(max,ms);if(ms>100)violations++;
            if(d.header.contains("Choreographer")){frames++;maxFrame=Math.max(maxFrame,ms);}
            if(d.header.contains("HandlerCaller")){handlers++;maxHandler=Math.max(maxHandler,ms);}
            timing.put(new JSONObject().put("header",d.header).put("start_elapsed_ns",d.start).put("end_elapsed_ns",d.end).put("ms",ms).put("target",true));
        }
        save("dispatches.json",timing.toString());StringBuilder trace=new StringBuilder();for(JSONObject s:stacks)trace.append(s).append('\n');save("stacks.jsonl",trace.toString());
        save("stall-result.json",new JSONObject().put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256")).put("workload_completed",complete).put("sampled",sampler!=null).put("dispatch_count",completed.size()).put("frame_count",frames).put("handler_count",handlers).put("max_ms",max).put("max_frame_ms",maxFrame).put("max_handler_ms",maxHandler).put("violations",violations).toString());
        if(!complete)return;
        assertNull("sampler completed",error);assertTrue("actual frame dispatch",frames>0);assertTrue("actual lifecycle dispatch",handlers>=10);
        assertEquals("every main dispatch <=100ms; max="+max+", frame="+maxFrame+", lifecycle="+maxHandler,0,violations);
    }
    public void testToggleOffTypeAndLifecycleUnder100ms()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        assertEquals(1.3f,row1.getResources().getConfiguration().fontScale,.01f);
        observe();boolean complete=false;
        try {
            stage("toggle-on");tagged("key:toT9");await("T9 1");
            stage("toggle-off");tagged("key:t9:function:注");
            // No extra settling pause before the first 41-key touch.
            stage("typing-ten");keys(KEYS);Thread.sleep(300);inst.waitForIdleSync();
            assertTrue("ten actual editor characters",editorText(await("test_input")).codePointCount(0,editorText(await("test_input")).length())>=10);
            readback("after-toggle-editor.json");String entered=editorText(await("test_input"));
            for(int n=0;n<5;n++){
                stage("other-app-"+n);shell("am start -W -a android.settings.SETTINGS");
                stage("return-"+n);shell("input keyevent 4");reopen();
                assertEquals("app return preserves editor text",entered,editorText(await("test_input")));if(!shown().isEmpty())assertEquals("retained preview agrees",entered,shown());
            }
            for(int n=0;n<5;n++){
                stage("hide-"+n);shell("input keyevent 4");
                stage("reopen-"+n);reopen();
                assertEquals("reopening preserves editor text",entered,editorText(await("test_input")));if(!shown().isEmpty())assertEquals("retained preview agrees",entered,shown());
            }
            stage("completed");readback("editor-final.json");complete=true;
        }finally{finishObservation(complete);}
    }

    Rect previewPoint(int boundary)throws Exception {
        final Rect hit=new Rect();
        inst.runOnMainSync(()->{
            android.text.Layout layout=row1.getLayout();String text=row1.getText().toString();
            int utf=text.offsetByCodePoints(0,boundary),line=layout.getLineForOffset(utf);
            View parent=(View)row1.getParent();if(parent instanceof android.widget.ScrollView)((android.widget.ScrollView)parent).scrollTo(0,Math.max(0,row1.getTotalPaddingTop()+layout.getLineTop(line)-10));
            int[] at=new int[2];row1.getLocationOnScreen(at);
            int x=at[0]+row1.getTotalPaddingLeft()+Math.round(layout.getPrimaryHorizontal(utf));
            int y=at[1]+row1.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2;
            Rect visible=new Rect();assertTrue(row1.getLocalVisibleRect(visible));visible.offset(at[0],at[1]);assertTrue("preview caret is physically visible",visible.contains(x,y));hit.set(x-1,y-1,x+1,y+1);
        });return hit;
    }
    void dragEvent(long down,int action,Rect hit)throws Exception {
        android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,hit.centerX(),hit.centerY(),0);
        try{assertTrue(ui.injectInputEvent(event,false));}finally{event.recycle();}
    }
    public void testPreviewCursorDispatchUnder100ms()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        observe();boolean complete=false;
        try {
            stage("preview-type-thirty");for(int n=0;n<3;n++)keys(KEYS);Thread.sleep(300);inst.waitForIdleSync();
            String entered=editorText(await("test_input"));assertTrue("thirty characters before preview cursor",entered.codePointCount(0,entered.length())>=30);
            for(int boundary:new int[]{27,15,1,29}){stage("preview-boundary-"+boundary);tap(previewPoint(boundary));assertEquals("caret touch never edits text",entered,editorText(await("test_input")));}
            Rect[] points={previewPoint(3),previewPoint(6),previewPoint(4),previewPoint(8)};
            stage("preview-batched-drag");long down=SystemClock.uptimeMillis();dragEvent(down,android.view.MotionEvent.ACTION_DOWN,points[0]);
            for(int n=1;n<points.length;n++){Thread.sleep(8);dragEvent(down,android.view.MotionEvent.ACTION_MOVE,points[n]);}
            dragEvent(down,android.view.MotionEvent.ACTION_UP,points[3]);Thread.sleep(400);inst.waitForIdleSync();
            assertEquals("drag preserves editor text",entered,editorText(await("test_input")));assertEquals("preview remains consistent",entered,shown());
            readback("editor-final.json");stage("completed");complete=true;
        }finally{finishObservation(complete);}
    }
    public void testPreviewCharTapBurstDispatchUnder100ms()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        observe();boolean complete=false;
        try {
            stage("preview-type-thirty");for(int n=0;n<3;n++)keys(KEYS);Thread.sleep(300);inst.waitForIdleSync();
            String entered=editorText(await("test_input"));assertTrue("thirty real characters",entered.codePointCount(0,entered.length())>=30);
            for(int boundary:new int[]{3,6,4}){
                Rect left=previewPoint(boundary-1),right=previewPoint(boundary);
                Rect middle=new Rect((left.centerX()+right.centerX())/2-1,left.centerY()-1,(left.centerX()+right.centerX())/2+1,left.centerY()+1);
                stage("preview-char-tap-"+boundary);long down=SystemClock.uptimeMillis();
                dragEvent(down,android.view.MotionEvent.ACTION_DOWN,middle);Thread.sleep(20);
                dragEvent(down,android.view.MotionEvent.ACTION_UP,middle);Thread.sleep(30);
            }
            Thread.sleep(400);inst.waitForIdleSync();
            assertEquals("char taps preserve editor",entered,editorText(await("test_input")));assertEquals("char taps preserve preview",entered,shown());
            readback("editor-final.json");screenshot("preview-char-taps");stage("completed");complete=true;
        }finally{finishObservation(complete);}
    }

}
