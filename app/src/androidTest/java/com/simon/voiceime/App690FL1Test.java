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
        long down=SystemClock.uptimeMillis();float dx=direction.equals("left")?-32*density():direction.equals("right")?32*density():0;
        touch(cap,MotionEvent.ACTION_DOWN,down,0,0);
        if(dx!=0)for(int step=1;step<=6;step++){Thread.sleep(20);touch(cap,MotionEvent.ACTION_MOVE,down,dx*step/6,0);}
        else Thread.sleep(40);
        touch(cap,MotionEvent.ACTION_UP,down,dx,0);Thread.sleep(400);inst.waitForIdleSync();
    }
    private JSONObject connection()throws Exception {
        JSONObject host=hostCall("state",new Bundle());
        return main(()->{InputConnection c=((InputMethodService)service).getCurrentInputConnection();host.put("connection_available",c!=null);if(c!=null){ExtractedText t=c.getExtractedText(new ExtractedTextRequest(),0);host.put("connection_text",t==null?JSONObject.NULL:t.text.toString());CharSequence before=c.getTextBeforeCursor(1024,0),after=c.getTextAfterCursor(1024,0);host.put("connection_before",before==null?JSONObject.NULL:before.toString()).put("connection_after",after==null?JSONObject.NULL:after.toString());}Object pending=call(call(dual(),"buffer"),"pending");host.put("draft_pending",pending==null?JSONObject.NULL:pending).put("draft_syllables",new JSONArray((java.util.Collection<?>)call(call(dual(),"buffer"),"syllables")));return host;});
    }
    private JSONObject observe()throws Exception {
        return main(()->{InputMethodService ime=(InputMethodService)service;View view=(View)call(dual(),"view");Rect full=rect(view),visible=new Rect();boolean shown=view.getGlobalVisibleRect(visible);int[] origin=new int[2];view.getRootView().getLocationOnScreen(origin);visible.offset(origin[0],origin[1]);JSONArray caps=new JSONArray();boolean complete=true;for(View cap:keys(view)){Rect capFull=rect(cap),capVisible=new Rect();boolean capShown=cap.getGlobalVisibleRect(capVisible);capVisible.offset(origin[0],origin[1]);boolean ok=capShown&&capFull.equals(capVisible);complete&=ok;caps.put(new JSONObject().put("tag",String.valueOf(cap.getTag())).put("bounds",capFull.toShortString()).put("visible",capVisible.toShortString()).put("fully_visible",ok));}View extract=ime.getWindow().getWindow().getDecorView().findViewById(android.R.id.inputExtractEditText);
                return new JSONObject().put("service_orientation",ime.getResources().getConfiguration().orientation).put("observer_orientation",inst.getTargetContext().getResources().getConfiguration().orientation).put("page_orientation",view.getResources().getConfiguration().orientation).put("keyboard_bounds",full.toShortString()).put("visible_bounds",visible.toShortString()).put("keyboard_fully_visible",view.isShown()&&shown&&full.equals(visible)&&complete&&caps.length()>0).put("page_is_shown",view.isShown()).put("current_mode",String.valueOf(field(service,"currentKeyboardMode"))).put("dual_hand_selected",field(service,"dualHandSelected")).put("key_visibility",caps).put("input_connection_available",ime.getCurrentInputConnection()!=null).put("split_preference",inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).getBoolean("dual_hand_landscape_split",false)).put("actual_split",call(dual(),"landscapeSplit")).put("fullscreen_evaluated",ime.onEvaluateFullscreenMode()).put("fullscreen_actual",ime.isFullscreenMode()).put("extract_shown",ime.isExtractViewShown()).put("extract_present",extract!=null).put("extract_bounds",extract==null?JSONObject.NULL:rect(extract).toShortString()).put("screen_height_dp",ime.getResources().getConfiguration().screenHeightDp).put("safe_inset_px",field(dual(),"safeInset")).put("failed_split_key",field(dual(),"failedSplitKey"));});
    }
    private long awaitCurrentPage()throws Exception {
        long start=SystemClock.uptimeMillis(),end=start+5000;
        do{service=main(()->((java.lang.ref.WeakReference<?>)field(api("SimonIMEService"),"notificationVoiceService")).get());
            if(service!=null){root=main(()->(View)field(service,"rootView"));if(root!=null&&main(()->((View)call(dual(),"view")).isShown()&&((InputMethodService)service).getCurrentInputConnection()!=null))break;}
            Thread.sleep(50);
        }while(SystemClock.uptimeMillis()<end);
        assertNotNull(service);return SystemClock.uptimeMillis()-start;
    }
    public void testLandscapeReadback()throws Exception {
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("dual_hand_landscape_split",true).commit();
        page("dual_hand");text("");JSONObject result=new JSONObject();
        try{
            assertTrue(inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90));Thread.sleep(2000);inst.waitForIdleSync();
            service=main(()->((java.lang.ref.WeakReference<?>)field(api("SimonIMEService"),"notificationVoiceService")).get());assertNotNull(service);root=main(()->(View)field(service,"rootView"));
            result.put("normal_ready_wait_ms",awaitCurrentPage());JSONObject normal=observe();result.put("normal_rotation",normal).put("host_after_rotation",hostCall("state",new Bundle()));
            result.put("normal_connection",connection());
            if(!normal.getBoolean("input_connection_available")){hostCall("reopen",new Bundle());Thread.sleep(800);inst.waitForIdleSync();}
            service=main(()->((java.lang.ref.WeakReference<?>)field(api("SimonIMEService"),"notificationVoiceService")).get());assertNotNull(service);root=main(()->(View)field(service,"rootView"));
            boolean reselect=!main(()->((View)call(dual(),"view")).isShown());if(reselect)page("dual_hand");
            result.put("action_ready_wait_ms",awaitCurrentPage());result.put("page_reselected_for_actions",reselect).put("host_reopen_required",!normal.getBoolean("input_connection_available")).put("action_geometry",observe());
            result.put("before",connection());gesture("1","tap");result.put("tap",connection());main(()->{call(call(dual(),"buffer"),"clearBuffer");call(dual(),"refresh");return null;});gesture("Ø","right");result.put("space_ready",connection());gesture("symbols","tap");result.put("space",connection());gesture("core","left");result.put("delete",connection());result.put("post_action_geometry",observe());
            Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(output,"l1-landscape.png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
        }finally{save("l1-landscape.json",result);inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);}
    }
}
