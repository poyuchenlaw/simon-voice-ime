package com.simon.voiceime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;
public class VoiceChunkQueueTest {
 @Rule public TemporaryFolder temp = new TemporaryFolder();
 static final int LIMIT = 3_840_000;
 static String response(File f,String id,String text)throws Exception {
  MessageDigest digest=MessageDigest.getInstance("SHA-256");
  try(InputStream in=new FileInputStream(f)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
  StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format("%02x",b&255));
  return new JSONObject().put("text",text).put("receipt",new JSONObject().put("client_session_id",id).put("byte_count",f.length()).put("sha256",h.toString())).toString();
 }
 String recording(VoicePendingQueue q,int size)throws Exception {
  String id=q.begin();byte[] block=new byte[64000];Arrays.fill(block,(byte)7);
  for(int left=size;left>0;){int n=Math.min(left,block.length);q.append(id,block,n);left-=n;}
  q.markPending(id,"test");return id;
 }
 @Test public void newLongRecordingArchivesBoundedChunksAndWaitsForWholeParent()throws Exception {
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=recording(q,7_680_020);
  List<Long> sizes=new ArrayList<>();List<String> texts=new ArrayList<>();List<String> ids=new ArrayList<>();
  q.uploadOne(id,(f,cid,rate)->{sizes.add(f.length());ids.add(cid);return response(f,cid,"段"+sizes.size());},(text,start)->texts.add(text),null);
  assertEquals("three chunks, no whole-file upload",Arrays.asList(3_840_000L,3_840_000L,20L),sizes);
  assertTrue(texts.isEmpty());assertEquals(3,new HashSet<>(ids).size());
  assertFalse(q.receiptConfirmed(id));assertTrue(q.pcmFile(id).exists());
 }


 @Test public void http413SplitsSmallerAndNeverDeletesBeforeReceipt()throws Exception {
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=recording(q,128000);
  List<Long> sizes=new ArrayList<>();List<String> texts=new ArrayList<>();
  q.uploadOne(id,(f,cid,rate)->{sizes.add(f.length());if(f.length()>32000)throw new VoicePendingQueue.PayloadTooLargeException();return response(f,cid,"甲");},(t,a)->texts.add(t),null);
  assertEquals(Arrays.asList(128000L,64000L,32000L,32000L,32000L,32000L),sizes);
  assertTrue(texts.isEmpty());assertFalse(q.receiptConfirmed(id));assertTrue(q.pcmFile(id).exists());
 }

 @Test public void restarted413SplitNeverRetriesKnownRejectedParent()throws Exception {
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=recording(q,128000);
  final int[] calls={0};
  q.uploadOne(id,(f,cid,r)->{
   if(f.length()==128000)throw new VoicePendingQueue.PayloadTooLargeException();
   if(++calls[0]==2)throw new IOException("disconnect after first receipt");
   return response(f,cid,"第一");
  },(t,a)->fail(),null);
  assertTrue(q.pcmFile(id).exists());VoicePendingQueue restarted=new VoicePendingQueue(temp.getRoot(),16000);
  List<Long> sizes=new ArrayList<>();List<String> text=new ArrayList<>();
  restarted.uploadOne(id,(f,cid,r)->{sizes.add(f.length());if(f.length()==128000)throw new VoicePendingQueue.PayloadTooLargeException();return response(f,cid,"第二");},(t,a)->text.add(t),null);
  assertEquals("use saved splitting decision after restart",Arrays.asList(64000L),sizes);
  assertTrue(text.isEmpty());assertTrue(restarted.pcmFile(id).exists());assertFalse(restarted.receiptConfirmed(id));
 }
 @Test public void firstVersionStartDiscardsOnlyAboveSixtyMinutes()throws Exception {
  File dir=new File(temp.getRoot(),"voice_pending");assertTrue(dir.mkdir());
  for(String id:Arrays.asList("giant","border","normal"))try(RandomAccessFile out=new RandomAccessFile(new File(dir,id+".pcm"),"rw")){
   out.setLength(id.equals("giant")?316_867_840L:id.equals("border")?115_200_000L:64000L);
  }
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);
  assertFalse("approved pre-cap giant discarded at first start",new File(dir,"giant.pcm").exists());
  assertTrue(new File(dir,"border.pcm").exists());assertTrue(new File(dir,"normal.pcm").exists());
  List<String> uploads=new ArrayList<>();q.uploadOne("giant",(f,id,r)->{uploads.add(id);return response(f,id,"不可上傳");},(t,a)->fail(),null);
  assertTrue(uploads.isEmpty());
 }

 @Test public void oversizeAuditHasOnlyApprovedMetadataAndRunsOnce()throws Exception {
  File dir=new File(temp.getRoot(),"voice_pending");assertTrue(dir.mkdir());
  try(RandomAccessFile out=new RandomAccessFile(new File(dir,"giant.pcm"),"rw")){out.setLength(316_867_840);}
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);
  File audit=new File(dir,"oversize-discard-events.jsonl");
  JSONObject event=new JSONObject(new String(Files.readAllBytes(audit.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim());
  assertEquals(3,event.length());assertEquals("pending_discarded_oversize",event.getString("phase"));
  assertEquals(9_902_120,event.getLong("audio_ms"));assertEquals(316_867_840,event.getLong("bytes"));
  long before=audit.length();new VoicePendingQueue(temp.getRoot(),16000);assertEquals(before,audit.length());
 }
 @Test public void childSilenceWaitsForWholeParentWithoutReuploadingStoredChunks()throws Exception {
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=recording(q,3_840_002);
  q.uploadOne(id,(f,cid,r)->response(f,cid,""),(t,a)->fail("silence must not deliver"),null);
  assertFalse(q.receiptConfirmed(id));assertTrue(q.pcmFile(id).exists());
  VoicePendingQueue restarted=new VoicePendingQueue(temp.getRoot(),16000);List<String> calls=new ArrayList<>();
  restarted.uploadOne(id,(f,cid,r)->{calls.add(cid);throw new IOException("no parent archive");},(t,a)->fail(),null);
  assertTrue(calls.isEmpty());
 }
 @Test public void badChunkReceiptKeepsOriginalAndNoDelivery()throws Exception {
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=recording(q,7_680_020);
  List<String> delivered=new ArrayList<>();
  q.uploadOne(id,(f,cid,rate)->new JSONObject(response(f,cid,"甲")).put("receipt",new JSONObject().put("client_session_id",cid).put("byte_count",f.length()).put("sha256","bad")).toString(),(t,a)->delivered.add(t),null);
  assertTrue(q.pcmFile(id).exists());assertEquals(7_680_020L,q.pcmFile(id).length());assertFalse(q.receiptConfirmed(id));assertTrue(delivered.isEmpty());
 }
 @Test public void restartResumesReceiptedChunksInOrder()throws Exception {
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=recording(q,7_680_020);final int[] calls={0};
  q.uploadOne(id,(f,cid,r)->{if(++calls[0]==2)throw new IOException("offline");return response(f,cid,"第一");},(t,a)->fail(),null);
  assertTrue(q.pcmFile(id).exists());VoicePendingQueue restarted=new VoicePendingQueue(temp.getRoot(),16000);
  List<Long> sizes=new ArrayList<>();List<String> texts=new ArrayList<>();
  restarted.uploadOne(id,(f,cid,r)->{sizes.add(f.length());return response(f,cid,sizes.size()==1?"第二":"第三");},(t,a)->texts.add(t),null);
  assertEquals(Arrays.asList(3_840_000L,20L),sizes);assertTrue(texts.isEmpty());assertFalse(restarted.receiptConfirmed(id));assertTrue(restarted.pcmFile(id).exists());
 }
}
