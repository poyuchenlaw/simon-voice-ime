package com.simon.voiceime;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;
public class Voice679SafetyTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void custodyConflictStopsAutomaticRetriesAndKeepsOriginalAcrossRestart()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=q.begin();byte[] original={1,2,3,4};q.append(id,original,4);q.markPending(id,"test");
  q.uploadOne(id,(p,s,r)->{throw new IOException("audio custody conflict");},(t,s)->fail(),null);
  assertTrue("custody conflict must require manual recovery",q.needsAttention(id));assertFalse(q.pendingOldestFirst().contains(id));assertArrayEquals(original,Files.readAllBytes(q.pcmFile(id).toPath()));
  q.markPending(id,"network_available");assertTrue(q.needsAttention(id));
  q=new VoicePendingQueue(temp.getRoot(),16000);assertTrue(q.needsAttention(id));assertArrayEquals(original,Files.readAllBytes(q.pcmFile(id).toPath()));
 }
 @Test public void recoveryExportsAllOriginalBytesWithoutChangingQueue()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String id=q.begin();byte[] original={1,2,3,4,5,6};q.append(id,original,6);q.markPending(id,"audio custody conflict");
  ByteArrayOutputStream exported=new ByteArrayOutputStream();
  try{q.getClass().getDeclaredMethod("exportPcm",String.class,OutputStream.class).invoke(q,id,exported);}catch(NoSuchMethodException missing){/* Baseline has no recovery export. */}
  assertArrayEquals("export must include original PCM",original,exported.toByteArray());assertArrayEquals(original,Files.readAllBytes(q.pcmFile(id).toPath()));
 }
 @Test public void onlyVerifiedCustodyScratchMayBeCleaned()throws Exception{
  VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);String raw=q.begin();q.append(raw,new byte[]{1,2},2);q.markPending(raw,"test");
  File unverified=new File(temp.getRoot(),"voice_pending/"+raw+".upload");Files.write(unverified.toPath(),new byte[]{1,2});
  String held=q.begin();q.append(held,new byte[]{3,4},2);q.markPending(held,"test");org.json.JSONObject identity=q.recordingIdentity(held);assertTrue(q.acceptReceipt(held,identity));File verified=new File(temp.getRoot(),"voice_pending/"+held+".upload");Files.write(verified.toPath(),new byte[]{3,4});
  try{q.getClass().getDeclaredMethod("clearArchivedScratch").invoke(q);}catch(NoSuchMethodException missing){/* Baseline offers no safe cleanup. */}
  assertFalse("verified scratch can be cleared",verified.exists());assertTrue(unverified.exists());assertTrue(q.pcmFile(raw).exists());
 }
}
