package com.simon.voiceime;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Does not kill the guest: directly invokes the installed public JVM handler,
 * chains a sentinel predecessor, then creates a fresh collector to replay it. */
public class CrashPersistence666AndroidTest extends TestCase {
    public void testCrashProcess() throws Exception {
        assertEquals("destructive fixture must only run on emulator","ranchu",android.os.Build.HARDWARE);
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ImeTelemetry.install(c);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{throw new IllegalStateException("private-crash-sentinel");});
        Thread.sleep(10000);fail("Android predecessor must terminate the crashed process");
    }
    public void testRecoverProcessCrash() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ImeTelemetry.install(c);
        File spool=new File(c.getFilesDir(),"ime-diagnostics.jsonl");
        String events=new String(java.nio.file.Files.readAllBytes(spool.toPath()),StandardCharsets.UTF_8);
        boolean found=false;
        for(String line:events.split("\n"))if(!line.isEmpty()){
            JSONObject e=new JSONObject(line);
            if("error".equals(e.optString("type"))&&"previous_uncaught_handler".equals(e.optString("capture"))&&e.optString("stack").contains("testCrashProcess")){
                found=true;assertFalse(e.toString().contains("private-crash-sentinel"));
            }
        }
        assertTrue("fresh Android process must replay persisted fatal stack",found);
        assertFalse(new File(c.getFilesDir(),"ime-pending-crash.json").exists());
    }
    public void testCorruptPendingIsReportedOnce() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pending=new File(c.getFilesDir(),"ime-pending-crash.json");
        File spool=new File(c.getFilesDir(),"corrupt-fixture-spool.jsonl");spool.delete();
        Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();ImeTelemetry telemetry=null;
        try{
            java.nio.file.Files.write(pending.toPath(),"{broken-private-record".getBytes(StandardCharsets.UTF_8));
            telemetry=new ImeTelemetry(c,spool,"fixture",(u,b,body)->503);
            assertFalse("corrupt pending record must not retry forever",pending.exists());
            String events=new String(java.nio.file.Files.readAllBytes(spool.toPath()),StandardCharsets.UTF_8);
            assertTrue(events.contains("invalid_pending_crash"));assertFalse(events.contains("broken-private-record"));
        }finally{if(telemetry!=null)telemetry.close();Thread.setDefaultUncaughtExceptionHandler(original);pending.delete();spool.delete();}
    }
    public void testPersistAndReplay() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File pending=new File(c.getFilesDir(),"ime-pending-crash.json");pending.delete();
        File spool=new File(c.getFilesDir(),"crash-fixture-spool.jsonl");spool.delete();
        Thread.UncaughtExceptionHandler original=Thread.getDefaultUncaughtExceptionHandler();
        final int[] chained={0};ImeTelemetry first=null,second=null;
        try {
            Thread.setDefaultUncaughtExceptionHandler((t,e)->chained[0]++);
            first=new ImeTelemetry(c,spool,"fixture",(u,b,body)->503);
            RuntimeException failure=new RuntimeException("private typed sentence",new IllegalArgumentException("private cause"));
            failure.setStackTrace(new StackTraceElement[]{new StackTraceElement("com.simon.voiceime.SimonIMEService","renderZhuyinStreamPreview","SimonIMEService.java",4436)});
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),failure);
            assertEquals("predecessor must still run",1,chained[0]);
            assertTrue("synchronous private crash file must survive termination",pending.isFile());
            String saved=new String(java.nio.file.Files.readAllBytes(pending.toPath()),StandardCharsets.UTF_8);
            assertTrue(saved.contains("SimonIMEService"));assertFalse(saved.contains("private typed"));assertFalse(saved.contains("private cause"));
            first.close();first=null;java.nio.file.Files.write(spool.toPath(),"{torn-line\n".getBytes(StandardCharsets.UTF_8));
            second=new ImeTelemetry(c,spool,"next-version",(u,b,body)->503);
            String replay=new String(java.nio.file.Files.readAllBytes(spool.toPath()),StandardCharsets.UTF_8);
            boolean found=false;for(String line:replay.split("\\n"))if(!line.isEmpty()){
                JSONObject e;try{e=new JSONObject(line);}catch(org.json.JSONException invalid){continue;}if("error".equals(e.optString("type"))&&"previous_uncaught_handler".equals(e.optString("capture"))){found=true;assertEquals("java.lang.RuntimeException",e.getString("exception_class"));assertTrue(e.getString("stack").contains("SimonIMEService.java:4436"));assertEquals("fixture",e.getString("crash_app_version"));assertTrue(e.getLong("crash_ts")<=e.getLong("ts"));}
            }
            assertTrue("next-start error event must contain persisted frame",found);
            assertFalse("replayed private file must be cleared",pending.exists());
        } finally {if(first!=null)first.close();if(second!=null)second.close();Thread.setDefaultUncaughtExceptionHandler(original);pending.delete();spool.delete();}
    }
}
