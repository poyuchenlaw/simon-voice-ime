package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceSessionGuardsTest {
 @Test public void replay1543StallsStopsAtTwentySecondsEvenWhileSending() {
  VoiceSessionGuard g=new VoiceSessionGuard(0,10);
  for(int ms=3000;ms<20000;ms+=3000)assertEquals("",g.check(ms,true,true));
  assertEquals("stall_stop",g.check(20000,true,true));
  assertEquals("",g.check(9902468,true,true));
 }
 @Test public void freshServerChunksResetStallButRepeatedChunksDoNot() {
  VoiceSessionGuard g=new VoiceSessionGuard(0,10);g.serverProgress(19000,0,"第一段");
  assertEquals("",g.check(38000,true,true));g.serverProgress(38500,0,"第一段");
  assertEquals("stall_stop",g.check(39000,true,true));
 }
 @Test public void capStopsEvenWithContinuousSpeechAndServerProgress() {
  VoiceSessionGuard g=new VoiceSessionGuard(0,10);g.speech(599999);g.serverProgress(599999,10,"持續口述");
  assertEquals("",g.check(599999,true,true));assertEquals("cap_stop",g.check(600000,true,true));
 }
 @Test public void screenOffWaitsFullMinuteAndSpeechRestartsIt() {
  VoiceSessionGuard g=new VoiceSessionGuard(0,10);
  assertEquals("",g.check(10000,false,false));assertEquals("",g.check(69999,false,false));
  g.speech(69000);assertEquals("",g.check(128999,false,false));
  assertEquals("screen_off_idle_stop",g.check(129000,false,false));
 }
 @Test public void newTextResetsScreenIdleAndScreenOnCancelsCountdown() {
  VoiceSessionGuard g=new VoiceSessionGuard(0,10);g.check(0,false,false);g.serverProgress(59000,-1,"語音");
  assertEquals("",g.check(60000,false,false));g.check(110000,true,false);g.check(120000,false,false);
  assertEquals("",g.check(179999,false,false));assertEquals("screen_off_idle_stop",g.check(180000,false,false));
 }
 @Test public void screenOnSilentSessionUsesCapAndConfiguredCapIsBounded() {
  VoiceSessionGuard g=new VoiceSessionGuard(0,1);assertEquals("",g.check(59999,true,false));
  assertEquals("cap_stop",g.check(60000,true,false));
  assertEquals("cap_stop",new VoiceSessionGuard(0,Integer.MAX_VALUE).check(1800000,true,false));
 }
 @Test public void previewCannotGrowBeyondLimitAndTracksIncompleteAudio() {
  BoundedPcmBuffer b=new BoundedPcmBuffer(64000);for(int i=0;i<10000;i++)b.write(new byte[32000],0,32000);
  assertEquals(64000,b.size());assertTrue(b.truncated());b.reset();assertFalse(b.truncated());
 }
}
