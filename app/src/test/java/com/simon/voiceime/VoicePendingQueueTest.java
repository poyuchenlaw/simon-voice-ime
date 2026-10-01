package com.simon.voiceime;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

public class VoicePendingQueueTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private VoicePendingQueue queue(){return new VoicePendingQueue(temp.getRoot(),16000);}
    private String session(VoicePendingQueue q,int bytes)throws Exception {String id=q.begin();q.append(id,new byte[bytes],bytes);q.markPending(id,"test");return id;}
    private JSONObject receipt(File pcm,String id)throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pcm.toPath()));StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b&255));
        return new JSONObject().put("client_session_id",id).put("byte_count",pcm.length()).put("sha256",hex.toString());
    }
    private String response(File pcm,String id,String text)throws Exception {return new JSONObject().put("receipt",receipt(pcm,id)).put("text",text).toString();}
    @Test public void discardRetryDiskFailureStillBacksOffInMemory()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.discard(id);
        File marker=new File(temp.getRoot(),"voice_pending/"+id+".discard");assertTrue(marker.delete());assertTrue(marker.mkdir());
        try{q.serverDiscardFailed(id);fail("marker write must fail");}catch(IllegalStateException expected){}
        assertTrue("IO failure must not cause hot-loop retries",q.serverDiscardDelayMs(id)>=4900);
    }
    @Test public void discardBackoffDoublesToOneHourAndSurvivesRecovery()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.discard(id);
        long[] expected={5000,10000,20000,40000,80000,160000,320000,640000,1280000,2560000,3600000,3600000};
        for(long delay:expected){q.serverDiscardFailed(id);long observed=q.serverDiscardDelayMs(id);assertTrue("backoff "+observed+" expected "+delay,observed<=delay&&observed>=delay-250);}
        VoicePendingQueue recovered=queue();assertTrue(recovered.pendingServerDiscards().contains(id));assertTrue(recovered.serverDiscardDelayMs(id)>3599000);
        recovered.confirmServerDiscard(id);assertTrue(recovered.pendingServerDiscards().isEmpty());assertTrue(queue().pendingServerDiscards().isEmpty());
    }
    @Test public void serverCopyExceptionIsLimitedToRecordedFailedSession()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.noteStreamedBytes(id,8);q.runIO(()->null);
        JSONObject r=new JSONObject().put("client_session_id",id).put("byte_count",8).put("sha256","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertFalse(q.acceptServerCopyAfterWriteFailure(id,r));assertTrue(q.pcmFile(id).exists());
    }
    @Test public void appendFailureIsRecordedForItsOwnSession()throws Exception {
        VoicePendingQueue q=queue();String id=q.begin();q.append(id,new byte[]{1,2},2);
        java.lang.reflect.Field field=VoicePendingQueue.class.getDeclaredField("streams");field.setAccessible(true);
        q.runIO(()->{((Map<String,FileOutputStream>)field.get(q)).get(id).close();return null;});
        Thread.sleep(210);
        try{q.append(id,new byte[]{3,4},2);fail("closed file must fail write");}catch(Exception expected){}
        assertTrue("actual local write failure must be remembered",q.backupFailed(id));
        q.finishRecording(id);q.runIO(()->null);
    }
    @Test public void archivedMetadataExpiresSevenDaysAfterCustody()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.persistResult(id,"done");q.acknowledgeDelivery(id,true);q.acceptReceipt(id,receipt(q.pcmFile(id),id));
        File m=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject old=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        old.put("archived_at",System.currentTimeMillis()-8L*24*60*60*1000);Files.write(m.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        queue();assertFalse("completed archived metadata older than seven days must expire",m.exists());
    }
    @Test public void custodyWithoutTranscriptSurvivesProcessExitAndExpiry()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);
        File m=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject damaged=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        damaged.put("local_write_failed",true).put("streamed_bytes",4).put("streamed_bytes_final",true);
        Files.write(m.toPath(),damaged.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));q=queue();
        assertTrue(q.acceptServerCopyAfterWriteFailure(id,receipt(q.pcmFile(id),id)));
        JSONObject old=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        old.put("archived_at",System.currentTimeMillis()-8L*24*60*60*1000);Files.write(m.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VoicePendingQueue recovered=queue();assertTrue("transcript is still owed",m.exists());assertTrue(recovered.pendingOldestFirst().contains(id));
        List<String> delivered=new ArrayList<>();JSONObject r=old.getJSONObject("audio_receipt");
        recovered.uploadOne(id,(pcm,sid,rate)->new JSONObject().put("text","stored words").put("receipt",r).toString(),(t,start)->delivered.add(t),null);
        assertEquals(Arrays.asList("stored words"),delivered);
        recovered.uploadOne(id,(pcm,sid,rate)->{fail("no repeat transcription after settled delivery");return null;},(t,start)->fail("no duplicate delivery"),null);
    }
    @Test public void ordinaryReceiptWithoutTextSurvivesRecoveryAndExpiry()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);JSONObject r=receipt(q.pcmFile(id),id);
        assertTrue(q.acceptReceipt(id,r));
        File m=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject old=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        old.put("archived_at",System.currentTimeMillis()-8L*24*60*60*1000);Files.write(m.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VoicePendingQueue recovered=queue();assertTrue("missing text is owed, not settled silence",m.exists());
        assertTrue(recovered.pendingOldestFirst().contains(id));List<String> delivered=new ArrayList<>();
        recovered.uploadOne(id,(pcm,sid,rate)->new JSONObject().put("text","ordinary recovered").put("receipt",r).toString(),(t,start)->delivered.add(t),null);
        assertEquals(Arrays.asList("ordinary recovered"),delivered);assertTrue(recovered.delivered(id));
    }
    @Test public void ordinaryReceiptWithBlankLiveTextFetchesTranscript()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);JSONObject r=receipt(q.pcmFile(id),id);
        q.persistResult(id,"");assertTrue(q.acceptReceipt(id,r));q.markPending(id,"empty_live");List<String> delivered=new ArrayList<>();
        q.uploadOne(id,(pcm,sid,rate)->new JSONObject().put("text","blank recovered").put("receipt",r).toString(),(t,start)->delivered.add(t),null);
        assertEquals(Arrays.asList("blank recovered"),delivered);assertTrue(q.delivered(id));
    }
    @Test public void confirmedArchiveSilenceMetadataExpiresWithoutTextDelivery()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.persistResult(id,"");q.acknowledgeDelivery(id,false);q.acceptReceipt(id,receipt(q.pcmFile(id),id));
        File m=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject old=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        old.put("archived_at",System.currentTimeMillis()-8L*24*60*60*1000);Files.write(m.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        queue();assertFalse("silent archives have no text outbox to retain",m.exists());
    }
    @Test public void oldArchiveKeepsAnUndeliveredTextOutbox()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.persistResult(id,"not delivered yet");q.acceptReceipt(id,receipt(q.pcmFile(id),id));
        File m=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject old=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        old.put("archived_at",System.currentTimeMillis()-8L*24*60*60*1000);Files.write(m.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VoicePendingQueue recovered=queue();assertTrue(m.exists());assertTrue(recovered.pendingOldestFirst().contains(id));
    }
    @Test public void audioAppendDoesNotAllocateAllArchivedSnapshots()throws Exception {
        File dir=new File(temp.getRoot(),"voice_pending");dir.mkdirs();
        for(int i=0;i<1500;i++)Files.write(new File(dir,"seed"+i+".json").toPath(),new JSONObject().put("schema_version",6).put("state","archived").put("audio_archived",true).put("audio_receipt",new JSONObject()).put("started_at",System.currentTimeMillis()).put("delivered",true).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VoicePendingQueue q=queue();String id=q.begin();q.append(id,new byte[]{1,2},2);
        Object bean=Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
        java.lang.reflect.Method allocated=Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes",long.class);
        long used=q.runIO(()->{long thread=Thread.currentThread().getId();long before=(Long)allocated.invoke(bean,thread);q.append(id,new byte[]{3,4},2);return (Long)allocated.invoke(bean,thread)-before;});
        assertTrue("one audio read allocated "+used+" bytes with 1500 archives",used<32768);
        assertEquals(0,q.audioMs(id));assertEquals(4,q.totalBytes());
    }
    @Test public void fullArchiveHistoryOutboxRetriesAfterSinkFailure()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.persistResult(id,"part");q.acknowledgeDelivery(id,true);
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"part plus missing tail"),(t,start)->fail("no second live delivery"),null);
        assertFalse(q.pcmFile(id).exists());
        VoicePendingQueue recovered=queue();List<String> history=new ArrayList<>();
        recovered.uploadOne(id,(pcm,sid,rate)->{fail("custody already confirmed");return null;},new VoicePendingQueue.Delivery(){public void deliver(String t,String start){fail("no field delivery");}public void archiveToHistory(String t,String start){history.add(t);}},null);
        assertEquals(Arrays.asList("part plus missing tail"),history);
    }
    public static class CrashWriter {
        public static void main(String[] args)throws Exception {
            VoicePendingQueue q=new VoicePendingQueue(new File(args[0]),16000);String id=q.beginAsync();System.out.println(id);System.out.flush();
            for(int i=1;;i++){q.append(id,new byte[640],640);System.out.println(i*640);System.out.flush();Thread.sleep(20);}
        }
    }
    @Test public void killedRecorderLosesAtMost250msOfPcm()throws Exception {
        Set<String> locations=new LinkedHashSet<>();
        for(Class<?> type:new Class<?>[]{VoicePendingQueueTest.class,VoicePendingQueue.class,JSONObject.class,org.junit.Test.class,org.hamcrest.CoreMatchers.class})locations.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        Process child=new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",String.join(File.pathSeparator,locations),CrashWriter.class.getName(),temp.getRoot().getAbsolutePath()).start();
        String id;long accepted=0;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            id=reader.readLine();assertNotNull(id);
            for(int i=0;i<21;i++){String line=reader.readLine();assertNotNull(line);accepted=Long.parseLong(line);}
        }finally{child.destroyForcibly();child.waitFor();}
        long durable=new File(temp.getRoot(),"voice_pending/"+id+".pcm").length();
        assertTrue("lost "+(accepted-durable)+" bytes; 250ms permits only 8000",durable>=accepted-8000);
        VoicePendingQueue recovered=queue();assertTrue(recovered.pendingOldestFirst().contains(id));
    }
    @Test public void idleRecordingBytesReachDiskWithin250ms()throws Exception {
        VoicePendingQueue q=queue();String id=q.beginAsync();q.appendAsync(id,new byte[]{1,2},2);q.runIO(()->null);
        q.appendAsync(id,new byte[8000],8000);q.runIO(()->null);
        Thread.sleep(250);
        assertEquals("a paused microphone read must not strand buffered PCM",8002,q.pcmFile(id).length());
    }
    @Test public void shortSpellTextWithoutAudioReceiptNeverDeletes()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,12800);
        q.uploadOne(id,(pcm,sid,rate)->"B",(text,started)->{},null);
        assertTrue("400 ms SPELL audio requires custody receipt even after text",q.pcmFile(id).exists());
    }
    @Test public void textSemanticsNeverAuthorizeDeletion()throws Exception {
        for(String text:new String[]{"B","...，！？","","<sil>","[BLANK_AUDIO]","AI answer","translated words"}) {
            VoicePendingQueue q=queue();String id=session(q,text.equals("B")?12800:320000);q.persistResult(id,text);q.acknowledgeDelivery(id,false);
            q.uploadOne(id,(pcm,sid,rate)->new JSONObject().put("text",text).toString(),(t,start)->fail("already delivered"),null);
            assertTrue(q.pcmFile(id).exists());
        }
    }
    @Test public void matchingReceiptDeletesWithoutHttpAndKeepsDeliveryReceipt()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,8);JSONObject r=receipt(q.pcmFile(id),id);
        assertTrue(q.acceptReceipt(id,r));assertFalse(q.pcmFile(id).exists());q.persistResult(id,"live answer");q.acknowledgeDelivery(id,false);
        q=queue();assertTrue(q.delivered(id));assertTrue(q.pendingOldestFirst().isEmpty());assertTrue(q.acceptReceipt(id,r));
    }
    @Test public void mismatchAndPartialReceiptsKeepWholeFile()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,320000);JSONObject r=receipt(q.pcmFile(id),id);
        assertFalse(q.acceptReceipt(id,new JSONObject(r.toString()).put("byte_count",32000)));
        assertFalse(q.acceptReceipt(id,new JSONObject(r.toString()).put("sha256","0".repeat(64))));
        assertFalse(q.acceptReceipt(id,new JSONObject(r.toString()).put("client_session_id","other")));
        assertEquals(320000,q.pcmFile(id).length());
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"recovered"),(t,start)->assertEquals("recovered",t),null);assertFalse(q.pcmFile(id).exists());
    }
    @Test public void offlineThenNetworkArchivesDeliversAndDeletes()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        q.uploadOne(id,(pcm,sid,rate)->{throw new IOException("offline");},(t,start)->fail(),null);
        assertTrue(q.pcmFile(id).exists());assertFalse(q.due(id,System.currentTimeMillis()));q=queue();List<String> delivery=new ArrayList<>();
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"queued words"),(t,start)->delivery.add(t),null);
        assertEquals(List.of("queued words"),delivery);assertFalse(q.pcmFile(id).exists());
    }
    @Test public void archiveAfterLiveDeliveryNeverOverwritesClipboard()throws Exception {
        for(String mode:new String[]{"APPEND","AI_COMMAND","SPELL","TRANSLATE"}) {
            VoicePendingQueue q=queue();String id=session(q,12800);q.persistResult(id,mode+" result",false);q.acknowledgeDelivery(id,true);q=queue();AtomicInteger requests=new AtomicInteger();
            q.uploadOne(id,(pcm,sid,rate)->{requests.incrementAndGet();return response(pcm,sid,"different instruction");},(t,start)->fail("session already delivered"),null);
            assertEquals(1,requests.get());assertFalse(q.pcmFile(id).exists());assertTrue(q.delivered(id));
        }
    }
    @Test public void receiptOutboxSurvivesClipboardFailureAndCrashWithoutReupload()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"saved result"),(t,start)->{throw new IllegalStateException("clipboard offline");},null);
        assertFalse(q.pcmFile(id).exists());assertFalse(q.delivered(id));q=queue();List<String> delivery=new ArrayList<>();
        q.uploadOne(id,(pcm,sid,rate)->{fail("durable receipt already accepted");return "";},(t,start)->delivery.add(t),null);
        assertEquals(List.of("saved result"),delivery);assertTrue(q.delivered(id));
    }
    @Test public void silenceAndMarkersWithReceiptOnlyShowStatus()throws Exception {
        for(String text:new String[]{" ","< SIL >","[BLANK_AUDIO]","<hallucination>","...，！？"}) {
            VoicePendingQueue q=queue();String id=session(q,32000);List<String> events=new ArrayList<>();
            q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,text),(t,start)->fail("silence reaches clipboard"),(phase,sid,ms,bytes,n,error)->events.add(phase));
            assertEquals(List.of("silence"),events);assertFalse(q.pcmFile(id).exists());
        }
    }
    @Test public void shortRealSpeechAndSpellSymbolAreDeliveredWithReceipt()throws Exception {
        for(String text:new String[]{"B","$","→"}) {
            VoicePendingQueue q=queue();String id=session(q,12800);List<String> delivered=new ArrayList<>();
            q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,text),(t,start)->delivered.add(t),null);assertEquals(List.of(text),delivered);
        }
    }
    @Test public void hashingWhileRecordingIncludesFinalTail()throws Exception {
        VoicePendingQueue q=queue();String id=q.beginAsync();q.appendAsync(id,new byte[]{1,2},2);q.appendAsync(id,new byte[]{3,4},2);
        q.finishRecording(id);q.runIO(()->null);
        JSONObject identity=q.recordingIdentity(id);assertEquals(4,identity.getLong("byte_count"));
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",identity.getString("sha256"));
        assertArrayEquals(new byte[]{1,2,3,4},q.pcmBytes(id));
    }
    @Test public void prematureReceiptCannotDeleteRecorderTail()throws Exception {
        VoicePendingQueue q=queue();String id=q.beginAsync();q.appendAsync(id,new byte[]{1,2},2);q.runIO(()->null);
        JSONObject prefix=new JSONObject().put("client_session_id",id).put("byte_count",2).put("sha256","a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222");
        assertFalse(q.acceptReceipt(id,prefix));q.appendAsync(id,new byte[]{3,4},2);q.finishRecording(id);q.runIO(()->null);assertEquals(4,q.pcmFile(id).length());
    }
    @Test public void oldDoneAndRetryModeRecordsRequireReceiptAndDrain()throws Exception {
        File dir=new File(temp.getRoot(),"voice_pending");dir.mkdirs();
        for(String state:new String[]{"done","pending","recording","uploading"}) {
            String id="legacy-"+state;Files.write(new File(dir,id+".pcm").toPath(),new byte[]{1,2});
            Files.write(new File(dir,id+".json").toPath(),new JSONObject().put("state",state).put("retry_mode","SPELL").put("delivered",true).put("clipboard_written",true).put("result_text","already delivered").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        VoicePendingQueue q=queue();assertEquals(4,q.pendingOldestFirst().size());
        for(String id:q.pendingOldestFirst()) {assertTrue(q.pcmFile(id).exists());q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"instruction"),(t,start)->fail("legacy delivery acknowledged"),null);assertFalse(q.pcmFile(id).exists());}
    }
    @Test public void wholeFileBeyondTenMinutesIsArchived()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,19_200_002);
        q.uploadOne(id,(pcm,sid,rate)->{assertEquals(19_200_002,pcm.length());return response(pcm,sid,"whole file");},(t,start)->{},null);assertFalse(q.pcmFile(id).exists());
    }
    @Test public void concurrentDrainClaimsOneArchive()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);AtomicInteger calls=new AtomicInteger();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        Thread a=new Thread(()->q.uploadOne(id,(pcm,sid,rate)->{calls.incrementAndGet();entered.countDown();release.await();return response(pcm,sid,"words");},(t,start)->{},null));a.start();assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        q.uploadOne(id,(pcm,sid,rate)->{calls.incrementAndGet();return response(pcm,sid,"duplicate");},(t,start)->fail(),null);release.countDown();a.join();assertEquals(1,calls.get());
    }
    @Test public void protectedDiscardBlocksLateUploadAndReceipt()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,8);JSONObject r=receipt(q.pcmFile(id),id);q.discard(id);assertFalse(q.acceptReceipt(id,r));assertFalse(q.pcmFile(id).exists());
    }
    @Test public void sessionIsolationAndDurableRetryDeadline()throws Exception {
        VoicePendingQueue q=queue();String older=session(q,8),newer=session(q,8);assertTrue(q.acceptReceipt(newer,receipt(q.pcmFile(newer),newer)));assertTrue(q.pcmFile(older).exists());
        q.uploadOne(older,(pcm,sid,rate)->{throw new IOException("503");},(t,start)->fail(),null);q=queue();assertFalse(q.due(older,System.currentTimeMillis()+4000));assertTrue(q.due(older,System.currentTimeMillis()+6000));
    }
    @Test public void singletonSharedAndBackupAsyncIsNonblocking()throws Exception {
        assertSame(VoicePendingQueue.getInstance(temp.getRoot(),16000),VoicePendingQueue.getInstance(temp.getRoot(),16000));
        VoicePendingQueue q=VoicePendingQueue.getInstance(temp.getRoot(),16000);q.runIO(()->null);java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);q.execute(()->{try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}});
        long started=System.nanoTime();String id=q.beginAsync();q.appendAsync(id,new byte[]{1,2},2);assertTrue(System.nanoTime()-started<500_000_000L);release.countDown();q.finishRecording(id);q.runIO(()->null);assertEquals(2,q.pcmFile(id).length());
    }
    @Test public void responseValidationAndOrdinarySilWords()throws Exception {
        assertEquals("words",VoicePendingQueue.parseSuccessfulResponse("{\"text\":\"words\"}"));
        for(String raw:new String[]{"not-json","{}","{\"text\":null}","{\"text\":42}","{\"error\":\"failed\",\"text\":\"words\"}"}) {
            try{VoicePendingQueue.parseSuccessfulResponse(raw);fail(raw);}catch(IOException expected){}
        }
        assertFalse(VoiceResultText.isSilence("Brasil"));assertFalse(VoiceResultText.isNonSpeech("B",false,400));
    }
    @Test public void legacyIntentIsNotAnAcknowledgedDelivery()throws Exception {
        File dir=new File(temp.getRoot(),"voice_pending");dir.mkdirs();String id="r3-intent";
        Files.write(new File(dir,id+".pcm").toPath(),new byte[]{1,2});
        Files.write(new File(dir,id+".json").toPath(),new JSONObject().put("state","pending").put("delivered",true).put("result_text","unconfirmed preview").put("clipboard_written",false).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VoicePendingQueue q=queue();List<String> delivery=new ArrayList<>();
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"complete recovered words"),(t,start)->delivery.add(t),null);
        assertEquals(List.of("complete recovered words"),delivery);assertFalse(q.pcmFile(id).exists());
    }
}
