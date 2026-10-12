package com.simon.voiceime;

import android.os.*;import android.util.Printer;import android.view.*;import org.json.*;import java.util.*;

/** Conservative Looper dispatch bounds, with the production Printer preserved and restored. */
public class App690PerfTest extends App690FSupport {
    private final JSONArray samples=new JSONArray(),stalls=new JSONArray();
    private Printer previous;private long begin=-1,lastLifecycle=-1;private boolean switchPending;private String measuredPage="",action="";
    private void observe()throws Exception {
        main(()->{Object monitor=field(service,"mainDispatchMonitor");previous=(Printer)field(Looper.getMainLooper(),"mLogging");assertNotNull(previous);Looper.getMainLooper().setMessageLogging(line->{long now=SystemClock.uptimeMillis();previous.println(line);try{if(line.startsWith(">>>>>"))begin=now;else if(line.startsWith("<<<<<")&&begin>=0){long ms=now-begin;if(ms>=100)stalls.put(new JSONObject().put("page",measuredPage).put("phase",action).put("ms",ms));String lifecycle=field(monitor,"dispatchLifecycle").toString();long started=(Long)field(monitor,"lifecycleStarted");if(action.equals("reopen")&&lifecycle.equals("onStartInputView")&&lastLifecycle!=started){lastLifecycle=started;samples.put(new JSONObject().put("page",measuredPage).put("action","onStartInputView").put("upper_ms",now-started));}if(switchPending){switchPending=false;samples.put(new JSONObject().put("page",measuredPage).put("action","switchKeyboard").put("upper_ms",now-begin));}begin=-1;}}catch(Exception e){throw new RuntimeException(e);}});return null;});
    }
    private double p90(JSONArray list,String page,String action)throws Exception {List<Double> ns=new ArrayList<>();for(int i=0;i<list.length();i++){JSONObject s=list.getJSONObject(i);if(s.getString("page").equals(page)&&s.getString("action").equals(action))ns.add(s.getDouble("upper_ms"));}assertEquals("30 actual dispatch samples "+page+" "+action,30,ns.size());Collections.sort(ns);return ns.get(26);}
    public void testFivePagesThirtyReopensAndSwitches()throws Exception {
        observe();JSONObject results=new JSONObject();String[] pages={"dual_hand","bopomofo","voice","english","numbers"};
        try{for(String p:pages){measuredPage=p;View surface=page(p);JSONObject before=counters();action="reopen";
                for(int i=0;i<30;i++){shell("input keyevent 4");Thread.sleep(80);hostCall("reopen",new Bundle());Thread.sleep(80);inst.waitForIdleSync();}
                JSONObject after=counters();results.put(p,new JSONObject().put("reopen_p90_ms",p90(samples,p,"onStartInputView")).put("before",before).put("after",after));assertTrue("reopen p90<50ms",results.getJSONObject(p).getDouble("reopen_p90_ms")<50);assertEquals("no unchanged-theme style reapply",before.getInt("style_reapply"),after.getInt("style_reapply"));assertEquals("font only loaded once",1,after.getInt("font_load"));assertEquals("cached fitting is not recomputed",before.getInt("fit"),after.getInt("fit"));assertEquals("unchanged haptic gate does not revisit tree",before.getInt("haptic_sync"),after.getInt("haptic_sync"));

            }
            for(int i=0;i<30;i++)for(String p:pages){measuredPage=p;action="switch";main(()->{set(service,"dualHandSelected",p.equals("dual_hand"));Class<?> mode=api("KeyboardPager$KeyboardMode");Object selected=null;for(Object x:mode.getEnumConstants())if(x.toString().equals(p.equals("voice")?"VOICE":p.equals("english")?"ENGLISH":p.equals("numbers")?"NUMBERS":"BOPOMOFO"))selected=x;switchPending=true;call(service,"switchKeyboard",selected);return null;});inst.waitForIdleSync();action="";}
            for(String p:pages){double value=p90(samples,p,"switchKeyboard");results.getJSONObject(p).put("switch_p90_ms",value);assertTrue("switch p90<50ms",value<50);}assertEquals("no >=100ms main_stall",0,stalls.length());
        }finally{action="";main(()->{Looper.getMainLooper().setMessageLogging(previous);return null;});save("perf-probe.json",new JSONObject().put("samples",samples).put("main_stall",stalls).put("pages",results).put("clock","uptimeMillis").put("measurement","callback begin from MainDispatchMonitor.lifecycleStarted to production Looper dispatch end; switch dispatch begin-to-end; conservative upper bounds, no method tracing"));}
    }
    public void testColdAndReopenedFirstThirtyActualInputs()throws Exception {
        String selected=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("perf_page");assertTrue("caller must cold-start one of five pages per invocation",Arrays.asList("dual_hand","bopomofo","voice","english","numbers").contains(selected));
        measuredPage=selected;View surface=page(selected);observe();
        try{List<View> input=main(()->{List<View> valid=new ArrayList<>();for(View v:keys(surface)){Object t=v.getTag();String tag=t==null?"":t.toString();if(tag.startsWith("dh-key:")&&Arrays.asList("1","2","3","4","5","6","A","B","C","D","E","F","Ø").contains(tag.substring(7))||tag.startsWith("key:")&&tag.substring(4).codePointCount(0,tag.substring(4).length())==1)valid.add(v);}if(selected.equals("voice")){valid.clear();valid.add(root.findViewById(inst.getTargetContext().getResources().getIdentifier("btnComma","id",inst.getTargetContext().getPackageName())));valid.add(root.findViewById(inst.getTargetContext().getResources().getIdentifier("btnPeriod","id",inst.getTargetContext().getPackageName())));}return valid;});assertFalse("safe output keys found",input.isEmpty());
            for(String phase:new String[]{"cold_first30","reopened_first30"}){action=phase;for(int i=0;i<30;i++)tap(input.get(i%input.size()));if(phase.equals("cold_first30")){shell("input keyevent 4");hostCall("reopen",new Bundle());Thread.sleep(100);}}assertEquals("no first30 main dispatch >=100ms",0,stalls.length());
        }finally{action="";main(()->{Looper.getMainLooper().setMessageLogging(previous);return null;});save("perf-first30-"+selected+".json",new JSONObject().put("page",selected).put("main_stall",stalls).put("actual_inputs_each_phase",30).put("pid",android.os.Process.myPid()).put("requires_caller_force_stop",true));}
    }
}
