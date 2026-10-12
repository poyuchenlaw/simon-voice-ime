package com.simon.voiceime;

import android.os.*;
import android.view.*;
import org.json.*;

/** Same cleared Ø-right sequence as the 6.89.1 observer, with real multi-MOVE input. */
public class App690FS1Test extends App690FSupport {
    public void testThirtyColdSwipes()throws Exception {
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("ime_auto_upload",true).putString("server_url","http://127.0.0.1:9").commit();
        View surface=page("dual_hand");View key=main(()->surface.findViewWithTag("dh-key:Ø"));assertNotNull(key);
        Thread.sleep(800);JSONArray gestures=new JSONArray();
        for(int i=0;i<30;i++){
            main(()->{call(call(dual(),"buffer"),"clearBuffer");call(dual(),"refresh");return null;});text("");
            long start=System.currentTimeMillis(),down=SystemClock.uptimeMillis();touch(key,MotionEvent.ACTION_DOWN,down,0,0);
            for(int step=1;step<=6;step++){Thread.sleep(20);touch(key,MotionEvent.ACTION_MOVE,down,32*density()*step/6,0);}
            touch(key,MotionEvent.ACTION_UP,down,32*density(),0);Thread.sleep(400);inst.waitForIdleSync();
            String observed=main(()->((java.util.List<?>)call(call(dual(),"buffer"),"syllables")).get(0).toString());assertEquals("known Ø-right syllable", "ㄨ",observed);
            gestures.put(new JSONObject().put("index",i+1).put("start_epoch_ms",start).put("end_epoch_ms",System.currentTimeMillis()).put("move_samples",6).put("draft",observed));
        }
        save("s1-thirty-gestures.json",new JSONObject().put("pid",android.os.Process.myPid()).put("gestures",gestures));
    }
}
