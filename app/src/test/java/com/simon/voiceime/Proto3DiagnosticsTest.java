package com.simon.voiceime;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import java.io.File;
import static org.junit.Assert.*;

public class Proto3DiagnosticsTest {
    @Test public void telemetryUsesDefaultAuthorizationWhenPasswordWasNeverSaved() {
        assertEquals("Bearer guangxin_voice_2026", ImeTelemetry.makeRequest("https://example.test/log", AuthConfig.authorizationHeader(null), "{}").header("Authorization"));
        assertEquals("Bearer guangxin_voice_2026", ImeTelemetry.makeRequest("https://example.test/log", AuthConfig.authorizationHeader(""), "{}").header("Authorization"));
    }
    @Test public void syntheticUncaughtExceptionProducesBoundedCrashDetails() throws Exception {
        RuntimeException failure = new RuntimeException("window token missing");
        JSONObject event = ImeTelemetry.makeCrashEvent("main", failure, 160);
        assertEquals("crash", event.getString("type"));
        assertEquals("java.lang.RuntimeException", event.getString("exception_class"));
        assertEquals("window token missing", event.getString("message"));
        assertTrue(event.getString("stack").contains("syntheticUncaughtExceptionProducesBoundedCrashDetails"));
        assertTrue(event.getString("stack").length() <= 160);
    }
    @Test public void protectedFieldsPersistOnlySkipMarker() throws Exception {
        JSONObject fields=new JSONObject().put("text","do-not-record-this").put("key","secret");
        JSONObject event=ImeTelemetry.makeEvent(1,"s","v","commit","bopomofo",fields,true);
        assertEquals("protected_field_skipped",event.getString("type"));
        assertEquals("protected_field_skipped",event.getString("step"));
        assertFalse(event.toString().contains("do-not-record-this"));
        assertFalse(event.toString().contains("secret"));
    }
    @Test public void retryBatchHasStableId() throws Exception {
        JSONObject e=new JSONObject().put("ts",7).put("type","key");
        String first=ImeTelemetry.stableBatchId(Arrays.asList(e));
        assertEquals(first,ImeTelemetry.stableBatchId(Arrays.asList(new JSONObject(e.toString()))));
        assertNotEquals(first,ImeTelemetry.stableBatchId(Arrays.asList(new JSONObject().put("ts",8).put("type","key"))));
    }
    @Test public void failedUploadLeavesBatchForSameIdRetry() throws Exception {
        File dir=new File(System.getProperty("java.io.tmpdir"),"ime-spool-test-"+System.nanoTime());assertTrue(dir.mkdirs());
        try {
            TelemetrySpool q=new TelemetrySpool(new File(dir,"events.jsonl"),1024);
            q.add(new JSONObject().put("ts",1).put("type","key"));
            java.util.List<JSONObject> first=q.batch(200);String id=ImeTelemetry.stableBatchId(first);
            // A failed HTTP post performs no acknowledge; retry returns the same batch identity.
            assertEquals(1,q.size());assertEquals(id,ImeTelemetry.stableBatchId(q.batch(200)));
            assertTrue(q.acknowledge(id,1));assertEquals(0,q.size());
        } finally { for(File f:dir.listFiles())f.delete();dir.delete(); }
    }
    @Test public void spoolNeverExceedsLimitForSingleOversizedEvent() throws Exception {
        File dir=new File(System.getProperty("java.io.tmpdir"),"ime-spool-limit-"+System.nanoTime());assertTrue(dir.mkdirs());
        try {
            TelemetrySpool q=new TelemetrySpool(new File(dir,"events.jsonl"),128);
            q.add(new JSONObject().put("payload",new String(new char[256]).replace('\0','x')));
            assertTrue(q.bytes()<=128);assertEquals(0,q.size());
        } finally { for(File f:dir.listFiles())f.delete();dir.delete(); }
    }
    @Test public void crashDetailsOmitTypedTextAndAudioFromMessagesAndCauses() throws Exception {
        RuntimeException failure=new RuntimeException("typed-private-sentence /data/private-audio.wav",new IllegalArgumentException("spoken-private-sentence"));
        JSONObject event=ImeTelemetry.makeCrashEvent("main",failure,8192);
        assertEquals("java.lang.RuntimeException",event.getString("exception_class"));
        assertFalse("typed content must not enter crash telemetry",event.toString().contains("typed-private-sentence"));
        assertFalse("audio path must not enter crash telemetry",event.toString().contains("private-audio.wav"));
        assertFalse("cause message must not enter crash telemetry",event.toString().contains("spoken-private-sentence"));
        assertTrue(event.getString("stack").contains("crashDetailsOmitTypedTextAndAudioFromMessagesAndCauses"));
    }

    @Test public void systemExitTraceKeepsOnlyKnownJavaFrames() throws Exception {
        String raw="private typed sentence /private/audio.wav\njava.lang.IllegalArgumentException: spoken-private\n    at com.simon.voiceime.SimonIMEService.onCreate(SimonIMEService.java:570)\n    at android.app.ActivityThread.handleCreateService(ActivityThread.java:120)\n  at attacker.Private.typedInput(private-audio.wav:5)\n";
        String safe=ImeTelemetry.safeExitTrace(raw,8192);
        assertTrue(safe.contains("SimonIMEService.onCreate(SimonIMEService.java:570)"));
        assertTrue(safe.contains("ActivityThread.handleCreateService"));
        assertFalse(safe.contains("private"));assertFalse(safe.contains("spoken"));
        assertTrue(ImeTelemetry.safeExitTrace(raw,40).length()<=40);
    }

}
