package com.simon.voiceime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;
public class Voice678SafetyTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 private String hash(File f)throws Exception{try(InputStream in=new FileInputStream(f)){MessageDigest d=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n);return HexFormat.of().formatHex(d.digest());}}
 private void legacy(boolean partial,boolean tombstone)throws Exception{
  File dir=new File(temp.getRoot(),"voice_pending");dir.mkdirs();File pcm=new File(dir,"legacy.pcm");
  try(RandomAccessFile f=new RandomAccessFile(pcm,"rw")){f.setLength(115200002);f.seek(0);f.write(new byte[]{1,2,3,4});}
  JSONObject m=new JSONObject().put("schema_version",6).put("sample_rate",16000).put("state","pending");
  if(partial)m.put("audio_receipt",new JSONObject().put("client_session_id","legacy").put("byte_count",4).put("sha256","0".repeat(64)));
  Files.write(new File(dir,"legacy.json").toPath(),m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  if(tombstone)Files.write(new File(dir,"legacy.oversize").toPath(),new JSONObject().put("audio_ms",3600001).put("bytes",pcm.length()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  String before=hash(pcm);VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);
  assertTrue("unverified oversize source must survive",pcm.isFile());assertEquals(before,hash(pcm));assertTrue(q.needsAttention("legacy"));
  q=new VoicePendingQueue(temp.getRoot(),16000);assertEquals(before,hash(pcm));assertTrue("manual attention must survive restart",q.needsAttention("legacy"));
 }
 @Test public void oversizedSourceSurvives()throws Exception{legacy(false,false);}
 @Test public void missingReceiptSurvives()throws Exception{legacy(false,true);}
 @Test public void partialReceiptSurvives()throws Exception{legacy(true,false);}
 @Test public void crashTombstoneSurvivesRestart()throws Exception{legacy(true,true);}
 @Test public void nonretryableDetailRetainsAudioAndStopsAcrossRestart()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=q.begin();q.append(id,new byte[]{1,2,3,4},4);q.markPending(id,"test");String before=hash(q.pcmFile(id));List<String> errors=new ArrayList<>();
  q.uploadOne(id,(pcm,sid,rate)->{throw new VoicePendingQueue.UploadFailure(503,"{\"detail\":{\"error_code\":\"asr_empty_retained\",\"retryable\":false}}");},(t,s)->fail("no text"),(p,s,a,b,n,e)->errors.add(e));
  assertTrue(q.needsAttention(id));assertEquals(before,hash(q.pcmFile(id)));assertTrue(errors.get(0).contains("asr_empty_retained"));
  q=new VoicePendingQueue(temp.getRoot(),16000);assertTrue(q.needsAttention(id));assertFalse(q.pendingOldestFirst().contains(id));assertEquals(before,hash(q.pcmFile(id)));
 }
 @Test public void temporaryFailureStillBacksOff()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=q.begin();q.append(id,new byte[]{1,2},2);q.markPending(id,"test");
  q.uploadOne(id,(p,s,r)->{throw new VoicePendingQueue.UploadFailure(503,"{\"detail\":{\"code\":\"asr_busy\",\"retryable\":true}}");},(t,s)->fail(),null);
  assertFalse(q.needsAttention(id));assertTrue(q.pcmFile(id).exists());assertTrue(q.retryDelayMs(id)>0);
 }
 @Test public void recordingDefersOldUploadWithoutLosingWork()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String old=q.begin();q.append(old,new byte[]{1,2},2);q.markPending(old,"test");
  q.beginAsync();q.runIO(()->null);final int[] calls={0};
  q.uploadOne(old,(p,s,r)->{calls[0]++;throw new IOException("network");},(t,s)->fail(),null);
  assertEquals("old job must yield to capture",0,calls[0]);assertTrue(q.pendingOldestFirst().contains(old));assertTrue(q.pcmFile(old).exists());
 }
 @Test public void heldServerAudioKeepsCustodyWhenRetriesStop()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=q.begin();q.append(id,new byte[]{1,2,3,4},4);
  JSONObject identity=q.recordingIdentity(id);assertTrue(q.acceptReceipt(id,identity));assertTrue(q.receiptConfirmed(id));q.markPending(id,"need_text");
  q.uploadOne(id,(p,sid,r)->{throw new VoicePendingQueue.UploadFailure(503,"{\"detail\":{\"error_code\":\"asr_empty_retained\",\"retryable\":false}}");},(t,sid)->fail(),null);
  assertTrue(q.needsAttention(id));assertTrue(q.receiptConfirmed(id));
  q.markPending(id,"network_available");assertTrue(q.needsAttention(id));
  q=new VoicePendingQueue(temp.getRoot(),16000);assertTrue(q.needsAttention(id));assertTrue(q.receiptConfirmed(id));
  JSONObject meta=new JSONObject(new String(Files.readAllBytes(new File(temp.getRoot(),"voice_pending/"+id+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(identity.getString("sha256"),meta.getJSONObject("audio_receipt").getString("sha256"));
 }

 @Test public void retryableTypesAndRetainedCodeFallback()throws Exception{
  for(String detail:new String[]{"{\"code\":\"busy\",\"retryable\":false}","{\"code\":\"busy\",\"retryable\":\"false\"}","{\"code\":\"busy\",\"retryable\":\"False\"}","{\"error_code\":\"asr_empty_retained\"}","{\"error_code\":\"asr_failed_retained\"}"}){
   VoicePendingQueue q=new VoicePendingQueue(temp.newFolder(),16000);String id=q.begin();q.append(id,new byte[]{1,2,3,4},4);q.markPending(id,"test");String before=hash(q.pcmFile(id));
   VoicePendingQueue.UploadFailure failure=new VoicePendingQueue.UploadFailure(503,"{\"detail\":"+detail+"}");
   q.uploadOne(id,(p,sid,r)->{throw failure;},(t,sid)->fail(),null);
   assertTrue("manual hold: "+detail,q.needsAttention(id));assertEquals(before,hash(q.pcmFile(id)));
  }
  for(String detail:new String[]{"{}","{\"retryable\":true,\"error_code\":\"asr_empty_retained\"}","{\"retryable\":\"true\"}","{\"retryable\":1}","{\"retryable\":null}"})assertTrue(new VoicePendingQueue.UploadFailure(503,detail).retryable);
 }
 @Test public void metadataFreeOversizeIsCompleteAndLegacyMarkerRetired()throws Exception{
  File root=temp.newFolder(),dir=new File(root,"voice_pending");dir.mkdirs();File pcm=new File(dir,"legacy.pcm");
  try(RandomAccessFile f=new RandomAccessFile(pcm,"rw")){f.setLength(115200002);f.write(new byte[]{1,2});}
  File marker=new File(dir,"legacy.oversize");Files.write(marker.toPath(),"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));String before=hash(pcm);
  VoicePendingQueue q=new VoicePendingQueue(root,16000);
  JSONObject m=new JSONObject(new String(Files.readAllBytes(new File(dir,"legacy.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
  assertEquals(3600001,m.getLong("audio_ms"));assertEquals(115200002,m.getLong("bytes"));assertTrue(m.getBoolean("automatic_retry_stopped"));assertFalse("tombstone must no longer imply discarded audio",marker.exists());assertEquals(before,hash(pcm));
  q=new VoicePendingQueue(root,16000);assertTrue(q.needsAttention("legacy"));assertEquals(before,hash(pcm));
 }
 @Test public void legacyManualReasonsSurviveWithoutStopFlag()throws Exception{
  for(String reason:new String[]{"UploadFailure: HTTP 503 asr_busy retryable=false","UploadFailure: HTTP 503 asr_busy retryable=False","legacy_oversize_retained_manual_review","asr_empty_retained","asr_failed_retained"}){
   File root=temp.newFolder();VoicePendingQueue q=new VoicePendingQueue(root,16000);String id=q.begin();q.append(id,new byte[]{1,2},2);q.markPending(id,"test");String before=hash(q.pcmFile(id));File f=new File(root,"voice_pending/"+id+".json");JSONObject m=new JSONObject(new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8));
   m.put("state","needs_attention").put("last_error",reason);m.remove("automatic_retry_stopped");Files.write(f.toPath(),m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
   q=new VoicePendingQueue(root,16000);assertTrue("hold survives "+reason,q.needsAttention(id));assertFalse(q.pendingOldestFirst().contains(id));assertEquals(before,hash(q.pcmFile(id)));
  }
 }
}
