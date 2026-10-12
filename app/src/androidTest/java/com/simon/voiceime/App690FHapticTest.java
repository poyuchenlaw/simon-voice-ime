package com.simon.voiceime;

import android.os.*;
import android.view.*;
import org.json.*;
import java.util.*;

/** Count actual vibrator records by createTime, avoiding current/history section double counts. */
public class App690FHapticTest extends App690FSupport {
    private Set<String> records(String dump){
        Set<String> times=new HashSet<>();
        for(String line:dump.split("\n"))if(line.contains("createTime: ")&&line.contains("opPkg=com.simon.voiceime,")){int at=line.indexOf("createTime: ")+12;times.add(line.substring(at,line.indexOf(',',at)));}
        return times;
    }
    public void testActualPressVibrations()throws Exception {
        JSONArray samples=new JSONArray();
        try{for(String theme:new String[]{"dark","light"}){
            inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("keyboard_theme",theme).commit();main(()->{call(service,"onStartInputView",call(service,"getCurrentInputEditorInfo"),true);return null;});
            for(String pageName:new String[]{"dual_hand","bopomofo","voice","english","numbers"}){
                View surface=page(pageName);List<View> input=main(()->keys(surface));assertTrue(input.size()>=3);
                for(int i=0;i<3;i++){View key=input.get(i);String before=shell("dumpsys vibrator_manager");long down=SystemClock.uptimeMillis();touch(key,MotionEvent.ACTION_DOWN,down,0,0);Thread.sleep(160);touch(key,MotionEvent.ACTION_CANCEL,down,0,0);Thread.sleep(250);String after=shell("dumpsys vibrator_manager");Set<String> added=records(after);added.removeAll(records(before));
                    samples.put(new JSONObject().put("page",pageName).put("theme",theme).put("index",i).put("vibrator_events_delta",added.size()).put("new_create_times",new JSONArray(added)).put("before_raw",before).put("after_raw",after));}
            }
        }}finally{save("haptic-presses.json",samples);}
        for(String theme:new String[]{"dark","light"}){int control=0;for(int i=0;i<samples.length();i++){JSONObject s=samples.getJSONObject(i);if(s.getString("page").equals("dual_hand")&&s.getString("theme").equals(theme))control+=s.getInt("vibrator_events_delta");}assertTrue("dual-hand positive control "+theme,control>0);}
    }
    public void testBaselineBackspaceHold()throws Exception {
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("ime_auto_upload",true).putString("server_url","http://127.0.0.1:9").commit();
        View surface=page("dual_hand");text("測試文字");View key=main(()->surface.findViewWithTag("dh-key:core"));long down=SystemClock.uptimeMillis();touch(key,MotionEvent.ACTION_DOWN,down,0,0);
        for(int step=1;step<=6;step++){Thread.sleep(20);touch(key,MotionEvent.ACTION_MOVE,down,0,-32*density()*step/6);}
        Thread.sleep(700);touch(key,MotionEvent.ACTION_UP,down,0,-32*density());Thread.sleep(500);
        save("backspace-hold-readback.json",hostCall("state",new Bundle()));
    }
}
