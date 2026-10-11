package com.simon.voiceime;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.util.ArrayList;
import java.util.List;

/** Controlled real Looper dispatches, including a simulated lifecycle callback. */
public class MainStall687AndroidTest extends TestCase {
    private String metadata(MainDispatchMonitor monitor) {
        try {return MainDispatchMonitor.class.getDeclaredMethod("service").invoke(monitor)+":"+MainDispatchMonitor.class.getDeclaredMethod("lifecycle").invoke(monitor);}
        catch(Exception error){throw new AssertionError(error);}
    }
    public void testDispatchAndDestroyMetadata() throws Exception {
        android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        android.content.Context c=inst.getTargetContext();
        c.getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("ime_auto_upload",true).commit();
        java.io.File spool=new java.io.File(c.getFilesDir(),"app687-stall-"+System.nanoTime()+".jsonl");
        ImeTelemetry telemetry=new ImeTelemetry(c,spool,"6.87",(url,bearer,body)->503);
        List<String> events=new ArrayList<>(); final MainDispatchMonitor[] ref={null};
        inst.runOnMainSync(()->{
            ref[0]=new MainDispatchMonitor((ms,target,callback,what,input,candidates)->
                {events.add(metadata(ref[0])+":"+ms);
                try {
                    String[] fields=metadata(ref[0]).split(":",-1);
                    ImeTelemetry.class.getDeclaredMethod("mainStall",int.class,String.class,String.class,int.class,boolean.class,boolean.class,String.class,String.class)
                        .invoke(telemetry,ms,target,callback,what,input,candidates,fields[0],fields[1]);
                }catch(Exception error){throw new AssertionError(error);}});
            try {
                MainDispatchMonitor.class.getDeclaredMethod("service",String.class).invoke(ref[0],"com.simon.voiceime.SimonIMEService");
            } catch(Exception e){throw new AssertionError(e);}
            Looper.getMainLooper().setMessageLogging(line->ref[0].accept(line,SystemClock.uptimeMillis(),false));
        });
        try {
            Handler main=new Handler(Looper.getMainLooper());
            java.util.concurrent.CountDownLatch normal=new java.util.concurrent.CountDownLatch(1);
            main.post(()->{SystemClock.sleep(160);normal.countDown();});
            assertTrue(normal.await(5,java.util.concurrent.TimeUnit.SECONDS));inst.waitForIdleSync();
            inst.runOnMainSync(()->{
                try {
                    MainDispatchMonitor.class.getDeclaredMethod("beginLifecycle",String.class,long.class).invoke(ref[0],"onDestroy",SystemClock.uptimeMillis());
                    SystemClock.sleep(170);
                    MainDispatchMonitor.class.getDeclaredMethod("endLifecycle",long.class).invoke(ref[0],SystemClock.uptimeMillis());
                } catch(Exception e){throw new AssertionError(e);}
            });
            inst.waitForIdleSync();
            assertTrue(events.toString(),events.stream().anyMatch(e->e.startsWith("com.simon.voiceime.SimonIMEService::")));
            assertTrue(events.toString(),events.stream().anyMatch(e->e.startsWith("com.simon.voiceime.SimonIMEService:onDestroy:")));
            for(String e:events){assertTrue(e,Integer.parseInt(e.substring(e.lastIndexOf(':')+1))>100);android.util.Log.i("APP687_STALL_TEST",e);}
            long deadline=SystemClock.uptimeMillis()+5000;boolean ordinary=false,destroy=false;
            while(SystemClock.uptimeMillis()<deadline&&!(ordinary&&destroy)) {
                if(spool.exists())for(String row:java.nio.file.Files.readAllLines(spool.toPath())){
                    org.json.JSONObject event=new org.json.JSONObject(row);
                    if(!"main_stall".equals(event.optString("type")))continue;
                    assertEquals("com.simon.voiceime.SimonIMEService",event.getString("service"));
                    if(event.getString("lifecycle").isEmpty())ordinary=true;
                    if("onDestroy".equals(event.getString("lifecycle")))destroy=true;
                    android.util.Log.i("APP687_STALL_TEST",event.toString());
                }
                if(!(ordinary&&destroy))SystemClock.sleep(50);
            }
            assertTrue("ordinary main_stall reached spool",ordinary);assertTrue("onDestroy main_stall reached spool",destroy);
        } finally {inst.runOnMainSync(()->Looper.getMainLooper().setMessageLogging(null));telemetry.close();}
    }
}
