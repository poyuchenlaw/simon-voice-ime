package com.simon.voiceime;

import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.graphics.*;
import org.json.*;

/** Baseline landscape observations; neither fullscreen nor split is forced into passing. */
public class App690FL1Test extends App690FSupport {
    private void gesture(String key,String direction)throws Exception {
        View cap=main(()->((View)call(dual(),"view")).findViewWithTag("dh-key:"+key));assertNotNull(cap);
        long down=SystemClock.uptimeMillis();float dx=direction.equals("left")?-32*density():0;
        touch(cap,MotionEvent.ACTION_DOWN,down,0,0);
        if(dx!=0)for(int step=1;step<=6;step++){Thread.sleep(20);touch(cap,MotionEvent.ACTION_MOVE,down,dx*step/6,0);}
        else Thread.sleep(40);
        touch(cap,MotionEvent.ACTION_UP,down,dx,0);Thread.sleep(400);inst.waitForIdleSync();
    }
    private JSONObject connection()throws Exception {
        JSONObject host=hostCall("state",new Bundle());
        return main(()->{InputConnection c=((InputMethodService)service).getCurrentInputConnection();assertNotNull(c);ExtractedText t=c.getExtractedText(new ExtractedTextRequest(),0);assertNotNull(t);return host.put("connection_text",t.text.toString());});
    }
    public void testLandscapeReadback()throws Exception {
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("dual_hand_landscape_split",true).commit();
        page("dual_hand");text("");JSONObject result=new JSONObject();
        try{
            assertTrue(inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90));Thread.sleep(2000);inst.waitForIdleSync();
            result=main(()->{InputMethodService ime=(InputMethodService)service;View view=(View)call(dual(),"view");Rect full=rect(view),visible=new Rect();boolean shown=view.getGlobalVisibleRect(visible);View extract=ime.getWindow().getWindow().getDecorView().findViewById(android.R.id.inputExtractEditText);
                return new JSONObject().put("service_orientation",ime.getResources().getConfiguration().orientation).put("observer_orientation",inst.getTargetContext().getResources().getConfiguration().orientation).put("page_orientation",view.getResources().getConfiguration().orientation).put("keyboard_bounds",full.toShortString()).put("visible_bounds",visible.toShortString()).put("keyboard_fully_visible",shown&&full.equals(visible)).put("split_preference",inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).getBoolean("dual_hand_landscape_split",false)).put("actual_split",call(dual(),"landscapeSplit")).put("fullscreen_evaluated",ime.onEvaluateFullscreenMode()).put("fullscreen_actual",ime.isFullscreenMode()).put("extract_shown",ime.isExtractViewShown()).put("extract_present",extract!=null).put("extract_bounds",extract==null?JSONObject.NULL:rect(extract).toShortString()).put("screen_height_dp",ime.getResources().getConfiguration().screenHeightDp).put("safe_inset_px",field(dual(),"safeInset")).put("failed_split_key",field(dual(),"failedSplitKey"));});
            result.put("before",connection());gesture("1","tap");result.put("tap",connection());gesture("symbols","tap");result.put("space",connection());gesture("core","left");result.put("delete",connection());
            Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(output,"l1-landscape.png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
        }finally{save("l1-landscape.json",result);inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);}
    }
}
