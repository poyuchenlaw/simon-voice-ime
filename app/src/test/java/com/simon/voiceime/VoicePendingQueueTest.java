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
    @Test public void nonDurableAndDecodedReceiptsCannotDeleteOriginalAudio()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,8);JSONObject original=receipt(q.pcmFile(id),id);
        for(JSONObject unsafe:new JSONObject[]{new JSONObject(original.toString()).put("durable",false),new JSONObject(original.toString()).put("audio_format","opus"),new JSONObject(original.toString()).put("type","audio_ack"),new JSONObject(original.toString()).put("wire_sha256",original.getString("sha256"))}) {
            assertFalse("non-durable/decoded identity cannot own original PCM",q.acceptReceipt(id,unsafe));
            assertTrue(q.pcmFile(id).exists());assertFalse(q.receiptConfirmed(id));
        }
        assertTrue("legacy original PCM receipt remains compatible",q.acceptReceipt(id,original));assertFalse(q.pcmFile(id).exists());
    }
    @Test public void failedWriteRescueRejectsNonDurableDecodedCustody()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,8);JSONObject r=receipt(q.pcmFile(id),id);
        File file=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject m=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        m.put("local_write_failed",true).put("streamed_bytes",8).put("streamed_bytes_final",true);
        Files.write(file.toPath(),m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));q=queue();
        assertFalse(q.acceptServerCopyAfterWriteFailure(id,new JSONObject(r.toString()).put("durable",false)));
        assertFalse(q.acceptServerCopyAfterWriteFailure(id,new JSONObject(r.toString()).put("audio_format","opus")));
        assertTrue(q.pcmFile(id).exists());assertFalse(q.receiptConfirmed(id));
        assertTrue("legacy durable PCM rescue remains compatible",q.acceptServerCopyAfterWriteFailure(id,r));
    }
    @Test public void childReceiptsAndCorrectedTextCannotFinalizeParent()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,(int)VoicePendingQueue.CHUNK_BYTES+8);List<String> delivered=new ArrayList<>();
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"各段已校正文字"),(t,start)->delivered.add(t),null);
        assertTrue("parent must survive until whole-utterance correction and receipt",q.pcmFile(id).exists());
        assertTrue("child corrected text is never a final utterance",delivered.isEmpty());assertFalse(q.receiptConfirmed(id));
    }
    @Test public void parentCorrectionFailureRetainsAllSourceAndResumesAfterRestart()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,(int)VoicePendingQueue.CHUNK_BYTES+8);JSONObject original=receipt(q.pcmFile(id),id);
        AtomicInteger childrenSent=new AtomicInteger(),parents=new AtomicInteger();List<String> delivered=new ArrayList<>();
        VoicePendingQueue.Uploader uploader=new VoicePendingQueue.Uploader() {
            public String transcribe(File pcm,String sid,int rate)throws Exception {childrenSent.incrementAndGet();return new JSONObject().put("receipt",receipt(pcm,sid)).toString();}
            public String finalizeParent(JSONObject identity,org.json.JSONArray children,int rate)throws Exception {
                assertEquals(2,children.length());assertEquals(0,children.getJSONObject(0).getLong("offset"));assertEquals(3_840_000L,children.getJSONObject(1).getLong("offset"));
                assertEquals(original.getString("sha256"),identity.getString("sha256"));
                if(parents.incrementAndGet()==1)throw new VoicePendingQueue.UploadFailure(503,"audio_correction_rejected");
                return new JSONObject().put("text","整句重新整理的結果").put("ai_corrected",true).put("receipt",original).toString();
            }
        };
        q.uploadOne(id,uploader,(t,start)->delivered.add(t),null);
        assertEquals(2,childrenSent.get());assertTrue(q.pcmFile(id).exists());assertFalse(q.receiptConfirmed(id));assertTrue(delivered.isEmpty());
        q=queue();q.uploadOne(id,uploader,(t,start)->delivered.add(t),null);
        assertEquals("stored children are never uploaded again",2,childrenSent.get());assertEquals(2,parents.get());
        assertEquals(Arrays.asList("整句重新整理的結果"),delivered);assertTrue(q.receiptConfirmed(id));assertFalse(q.pcmFile(id).exists());
        q.uploadOne(id,uploader,(t,start)->fail("duplicate final"),null);assertEquals(2,parents.get());
    }
    @Test public void http413AdaptsCustodyRangesBeforeWholeParentCorrection()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32);JSONObject original=receipt(q.pcmFile(id),id);List<Long> sizes=new ArrayList<>();List<String> delivered=new ArrayList<>();
        q.uploadOne(id,new VoicePendingQueue.Uploader() {
            public String transcribe(File pcm,String sid,int rate)throws Exception {throw new VoicePendingQueue.PayloadTooLargeException();}
            public String archiveChunk(File pcm,String sid,int rate)throws Exception {
                sizes.add(pcm.length());if(pcm.length()>8)throw new VoicePendingQueue.PayloadTooLargeException();return new JSONObject().put("receipt",receipt(pcm,sid)).toString();
            }
            public String finalizeParent(JSONObject identity,org.json.JSONArray children,int rate)throws Exception {
                assertEquals(4,children.length());assertEquals(32,identity.getLong("byte_count"));
                for(int n=0;n<4;n++){assertEquals(n*8L,children.getJSONObject(n).getLong("offset"));assertEquals(8,children.getJSONObject(n).getLong("bytes"));}
                return new JSONObject().put("text","全文完成").put("ai_corrected",true).put("receipt",original).toString();
            }
        },(t,start)->delivered.add(t),null);
        assertEquals(Arrays.asList(16L,8L,8L,8L,8L),sizes);assertEquals(Arrays.asList("全文完成"),delivered);assertFalse(q.pcmFile(id).exists());
    }
    @Test public void uncorrectedOrMismatchedParentNeverDeletesSourceOrDelivers()throws Exception {
        for(boolean corrected:new boolean[]{false,true}) {
            VoicePendingQueue q=queue();String id=session(q,(int)VoicePendingQueue.CHUNK_BYTES+8);JSONObject original=receipt(q.pcmFile(id),id);
            q.uploadOne(id,new VoicePendingQueue.Uploader() {
                public String transcribe(File pcm,String sid,int rate)throws Exception {return new JSONObject().put("receipt",receipt(pcm,sid)).toString();}
                public String finalizeParent(JSONObject identity,org.json.JSONArray children,int rate)throws Exception {
                    if(corrected)original.put("client_session_id","wrong-parent");
                    return new JSONObject().put("text","不能交付的結果").put("ai_corrected",corrected).put("receipt",original).toString();
                }
            },(t,start)->fail("uncorrected/wrong parent cannot be delivered"),null);
            assertTrue(q.pcmFile(id).exists());assertFalse(q.receiptConfirmed(id));assertFalse(q.delivered(id));
        }
    }
    @Test public void nonDurableChildReceiptCannotArchiveParent()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,(int)VoicePendingQueue.CHUNK_BYTES+8);
        q.uploadOne(id,(pcm,sid,rate)->new JSONObject().put("receipt",receipt(pcm,sid).put("durable",false)).toString(),(t,start)->fail("non-durable child cannot deliver"),null);
        assertTrue(q.pcmFile(id).exists());assertFalse(q.receiptConfirmed(id));assertFalse(q.delivered(id));
    }
    @Test public void legacyChildJoinWithParentReceiptStillRequiresWholeCorrection()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,8);JSONObject original=receipt(q.pcmFile(id),id);
        File file=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject m=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        m.put("state","pending").put("audio_archived",true).put("audio_receipt",original).put("chunk_transcript","舊子段串接").put("result_text","舊子段串接")
                .put("chunk_receipts",new org.json.JSONArray().put(new JSONObject().put("offset",0).put("bytes",8).put("text","舊子段串接").put("receipt",original)));
        Files.write(file.toPath(),m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));q=queue();
        q.uploadOne(id,(pcm,sid,rate)->{fail("children already stored");return null;},(t,start)->fail("legacy child join is not whole corrected text"),null);
        assertTrue("full original survives parent finalization failure",q.pcmFile(id).exists());
    }
    @Test public void legacyDeletedParentUsesStoredRangesAndNeverOldJoinedText()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,8);JSONObject original=receipt(q.pcmFile(id),id);
        File file=new File(temp.getRoot(),"voice_pending/"+id+".json");JSONObject m=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        JSONObject child=new JSONObject(original.toString()).put("client_session_id",id+"-part-0-8");
        m.put("state","archived").put("audio_archived",true).put("chunk_transcript","舊子段串接").put("result_text","舊子段串接")
                .put("chunk_receipts",new org.json.JSONArray().put(new JSONObject().put("offset",0).put("bytes",8).put("text","舊子段串接").put("receipt",child)));
        Files.write(file.toPath(),m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));assertTrue(q.pcmFile(id).delete());q=queue();List<String> delivered=new ArrayList<>();
        q.uploadOne(id,new VoicePendingQueue.Uploader() {
            public String transcribe(File pcm,String sid,int rate)throws Exception {fail("all child audio already stored");return null;}
            public String finalizeParent(JSONObject identity,org.json.JSONArray children,int rate)throws Exception {
                assertEquals(8,identity.getLong("byte_count"));assertEquals(1,children.length());
                return new JSONObject().put("text","完整原句重新校正").put("ai_corrected",true).put("receipt",original).toString();
            }
        },(t,start)->delivered.add(t),null);
        assertEquals(Arrays.asList("完整原句重新校正"),delivered);assertTrue(q.receiptConfirmed(id));
        m=new JSONObject(new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8));assertTrue(m.optBoolean("chunk_parent_corrected"));
    }
    @Test public void partialChildCustodyResumesWithoutPartialDeliveryOrDuplicateUpload()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,(int)VoicePendingQueue.CHUNK_BYTES+8);JSONObject original=receipt(q.pcmFile(id),id);
        AtomicInteger first=new AtomicInteger(),tail=new AtomicInteger(),parents=new AtomicInteger();List<String> delivered=new ArrayList<>();
        VoicePendingQueue.Uploader uploader=new VoicePendingQueue.Uploader() {
            public String transcribe(File pcm,String sid,int rate)throws Exception {
                if(sid.endsWith("-part-0-3840000"))first.incrementAndGet();else if(tail.incrementAndGet()==1)throw new IOException("network down before tail custody");
                return new JSONObject().put("receipt",receipt(pcm,sid)).toString();
            }
            public String finalizeParent(JSONObject identity,org.json.JSONArray children,int rate)throws Exception {
                parents.incrementAndGet();assertEquals(2,children.length());
                return new JSONObject().put("text","原句全部保留").put("ai_corrected",true).put("receipt",original).toString();
            }
        };
        q.uploadOne(id,uploader,(t,start)->delivered.add(t),null);assertTrue(q.pcmFile(id).exists());assertTrue(delivered.isEmpty());assertEquals(0,parents.get());
        q=queue();q.uploadOne(id,uploader,(t,start)->delivered.add(t),null);
        assertEquals(1,first.get());assertEquals(2,tail.get());assertEquals(1,parents.get());assertEquals(Arrays.asList("原句全部保留"),delivered);assertFalse(q.pcmFile(id).exists());
    }
    @Test public void transientFailureRemainsAutomaticAfterManyAttempts()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        for(int n=0;n<12;n++)q.uploadOne(id,(pcm,sid,rate)->{throw new VoicePendingQueue.UploadFailure(503,"busy");},(t,start)->fail(),null);
        assertEquals(12,q.attempts(id));assertFalse(q.needsAttention(id));
        assertTrue(q.pendingOldestFirst().contains(id));assertFalse(q.due(id,System.currentTimeMillis()));
        q=queue();assertTrue(q.pendingOldestFirst().contains(id));assertTrue(q.retryDelayMs(id)>0);
        List<String> delivered=new ArrayList<>();
        q.uploadOne(id,(pcm,sid,rate)->response(pcm,sid,"自動恢復"),(t,start)->delivered.add(t),null);
        assertEquals(Arrays.asList("自動恢復"),delivered);
        q.uploadOne(id,(pcm,sid,rate)->{fail("no second upload");return null;},(t,start)->fail("duplicate"),null);
    }
    @Test public void correctionRejectionRetainsAudioAndAutomaticBackoff()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        for(int n=0;n<12;n++)q.uploadOne(id,(pcm,sid,rate)->new JSONObject(response(pcm,sid,"未經校正原文")).put("ai_corrected",false).put("correction_status","needs_attention").put("retryable",false).toString(),(t,start)->fail("rejected text must never be delivered"),null);
        assertEquals(12,q.attempts(id));assertFalse(q.needsAttention(id));assertFalse(q.delivered(id));
        assertEquals(32000,q.pcmFile(id).length());assertTrue(q.pendingOldestFirst().contains(id));
        assertTrue(queue().retryDelayMs(id)>0);
    }
    @Test public void lateSocketCallbackCannotBypassAutomaticBackoff()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        q.uploadOne(id,(pcm,sid,rate)->{throw new VoicePendingQueue.UploadFailure(503,"busy");},(t,start)->fail(),null);
        assertTrue(q.retryDelayMs(id)>4000);
        q.markPendingAsync(id,"late_socket_close",null);q.runIO(()->null);
        assertTrue("late callbacks must retain retry deadline",q.retryDelayMs(id)>4000);
    }
    @Test public void networkReturnMakesConnectionFailureImmediatelyDue()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        q.uploadOne(id,(pcm,sid,rate)->{throw new IOException("offline");},(t,start)->fail(),null);
        assertFalse(q.due(id,System.currentTimeMillis()));
        q.markPending(id,"network_available");assertTrue(q.due(id,System.currentTimeMillis()));
    }
    @Test public void networkRestorationKeepsCorrectionFailureBackoff()throws Exception {
        for(String reason:new String[]{"audio_correction_unavailable","audio_correction_wrong_model","audio_correction_rejected"}) {
            VoicePendingQueue q=queue();String id=session(q,32000);
            q.uploadOne(id,(pcm,sid,rate)->{throw new VoicePendingQueue.UploadFailure(503,reason);},(t,start)->fail(),null);
            long before=q.retryDelayMs(id);assertTrue(before>4000);
            q.markPending(id,"network_available");
            assertFalse(q.due(id,System.currentTimeMillis()));assertTrue(q.retryDelayMs(id)>4000);
        }
    }
    @Test public void legacyManualQueueMigratesToAutomaticRecovery()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        File m=new File(temp.getRoot(),"voice_pending/"+id+".json");
        JSONObject old=new JSONObject(new String(Files.readAllBytes(m.toPath()),java.nio.charset.StandardCharsets.UTF_8));
        old.put("state","needs_attention").put("next_attempt_at",Long.MAX_VALUE);
        Files.write(m.toPath(),old.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        q=queue();assertFalse(q.needsAttention(id));assertTrue(q.pendingOldestFirst().contains(id));assertTrue(q.due(id,System.currentTimeMillis()));
    }
    @Test public void manualRetryCannotResurrectDiscardedAttentionSession()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,32000);
        for(int n=0;n<3;n++)q.uploadOne(id,(pcm,sid,rate)->{throw new VoicePendingQueue.UploadFailure(503,"{\"detail\":\"audio_correction_rejected\"}");},(t,start)->fail(),null);
        // Hold IO to exercise the synchronous discard marker before asynchronous disk cleanup.
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        q.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}});
        assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        try {q.discardAsync(id);assertFalse(q.needsAttentionSessions().contains(id));}finally{release.countDown();}
        q.retryForUser(id);
        q.runIO(()->null);assertFalse(q.pendingOldestFirst().contains(id));assertFalse(q.pcmFile(id).exists());
    }
    @Test public void emptyPendingIsDeletedAndNeverUploaded()throws Exception {
        VoicePendingQueue q=queue();String id=q.begin();q.markPending(id,"test");
        assertFalse("empty PCM must leave pending queue",q.pendingOldestFirst().contains(id));
        assertFalse("empty PCM deleted",q.pcmFile(id).exists());
        q.uploadOne(id,(pcm,sid,rate)->{fail("zero-byte upload");return null;},(t,start)->fail("empty delivery"),null);
        String events=new String(Files.readAllBytes(new File(temp.getRoot(),"voice_pending/empty-discard-events.jsonl").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(events.contains("pending_discarded_empty"));
    }
    @Test public void discardRetryDiskFailureStillBacksOffInMemory()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.discard(id);
        File marker=new File(temp.getRoot(),"voice_pending/"+id+".discard");assertTrue(marker.delete());assertTrue(marker.mkdir());
        try{q.serverDiscardFailed(id);fail("marker write must fail");}catch(IllegalStateException expected){}
        assertTrue("IO failure must not cause hot-loop retries",q.serverDiscardDelayMs(id)>=4900);
    }
    @Test public void discardBackoffDoublesToOneHourAndSurvivesRecovery()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,4);q.discard(id);
        long[] expected={5000,10000,20000,40000,80000,160000,320000,640000,1280000,2560000,3600000,3600000};
        long lastStarted=0;
        for(long delay:expected){lastStarted=System.currentTimeMillis();q.serverDiscardFailed(id);long observed=q.serverDiscardDelayMs(id);long elapsed=System.currentTimeMillis()-lastStarted;assertTrue("backoff "+observed+" expected "+delay,observed<=delay&&observed>=delay-elapsed);}
        VoicePendingQueue recovered=queue();assertTrue(recovered.pendingServerDiscards().contains(id));assertTrue(recovered.serverDiscardDelayMs(id)>=3600000-(System.currentTimeMillis()-lastStarted));
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
    @Test public void legacyTenMinuteFileIsArchivedInBoundedChunks()throws Exception {
        VoicePendingQueue q=queue();String id=session(q,19_200_002);
        java.util.List<Long> chunks=new java.util.ArrayList<>();
        JSONObject original=receipt(q.pcmFile(id),id);
        q.uploadOne(id,new VoicePendingQueue.Uploader() {
            public String transcribe(File pcm,String sid,int rate)throws Exception {chunks.add(pcm.length());return new JSONObject().put("receipt",receipt(pcm,sid)).toString();}
            public String finalizeParent(JSONObject identity,org.json.JSONArray children,int rate)throws Exception {
                assertEquals(19_200_002L,identity.getLong("byte_count"));assertEquals(6,children.length());
                return new JSONObject().put("text","whole file").put("ai_corrected",true).put("receipt",original).toString();
            }
        },(t,start)->assertEquals("whole file",t),null);
        assertEquals(java.util.Arrays.asList(3_840_000L,3_840_000L,3_840_000L,3_840_000L,3_840_000L,2L),chunks);
        assertTrue(q.receiptConfirmed(id));assertFalse(q.pcmFile(id).exists());
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
    @Test public void correctedOpusOnlyArchivesRawCustodyWithoutSecondTranscription()throws Exception {
        for(int size:new int[]{32,(int)VoicePendingQueue.CHUNK_BYTES+8}) {
            VoicePendingQueue q=queue();String id=session(q,size);JSONObject original=receipt(q.pcmFile(id),id);
            q.markOpusFinalCorrected(id);q.persistResult(id,"完整且已校正的串流文字",true,true);q.acknowledgeDelivery(id,false);q.markPending(id,"custody_needed");
            AtomicInteger children=new AtomicInteger(),parents=new AtomicInteger();
            q.uploadOne(id,new VoicePendingQueue.Uploader() {
                public String transcribe(File pcm,String sid,int rate)throws Exception {fail("already corrected Opus must not transcribe/pay again");return null;}
                public String archiveChunk(File pcm,String sid,int rate)throws Exception {children.incrementAndGet();return new JSONObject().put("receipt",receipt(pcm,sid)).toString();}
                public String finalizeParent(JSONObject identity,org.json.JSONArray parts,int rate)throws Exception {fail("parent correction must not run twice");return null;}
                public String archiveParent(JSONObject identity,org.json.JSONArray parts,int rate)throws Exception {parents.incrementAndGet();assertEquals(original.getString("sha256"),identity.getString("sha256"));return new JSONObject().put("receipt",original).toString();}
            },(text,start)->fail("text already delivered"),null);
            assertEquals(size>VoicePendingQueue.CHUNK_BYTES?2:1,children.get());assertEquals(size>VoicePendingQueue.CHUNK_BYTES?1:0,parents.get());
            assertTrue(q.receiptConfirmed(id));assertFalse(q.pcmFile(id).exists());
        }
    }
}
