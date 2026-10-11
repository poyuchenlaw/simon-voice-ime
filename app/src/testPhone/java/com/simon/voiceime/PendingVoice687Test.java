package com.simon.voiceime;
import org.junit.Test;import static org.junit.Assert.*;import java.lang.reflect.*;
public class PendingVoice687Test {
 @Test public void fieldRoutingRejectsSwitchedHiddenProtectedAndUnknownFields()throws Exception {
  Class<?> p=Class.forName("com.simon.voiceime.PendingVoiceDelivery");Method route=p.getDeclaredMethod("sameField",String.class,int.class,String.class,int.class,boolean.class,boolean.class);
  assertEquals(true,route.invoke(null,"a",7,"a",7,true,false));
  for(Object[] args:new Object[][]{{"a",7,"b",7,true,false},{"a",7,"a",8,true,false},{"a",7,"a",7,false,false},{"a",7,"a",7,true,true},{"",7,"",7,true,false}})assertEquals(false,route.invoke(null,args));
 }
 @Test public void permissionPromptIsOneShotAndNeverAfterDenial()throws Exception {
  Method allowed=Class.forName("com.simon.voiceime.PendingVoiceDelivery").getDeclaredMethod("permissionAllowed",boolean.class,boolean.class);
  assertEquals(true,allowed.invoke(null,false,false));assertEquals(false,allowed.invoke(null,true,false));assertEquals(false,allowed.invoke(null,false,true));assertEquals(false,allowed.invoke(null,true,true));
  Method offer=Class.forName("com.simon.voiceime.PendingVoiceDelivery").getDeclaredMethod("offerNotification",boolean.class,boolean.class,boolean.class);
  assertEquals(true,offer.invoke(null,false,false,false));assertEquals(false,offer.invoke(null,false,true,false));assertEquals(false,offer.invoke(null,false,false,true));assertEquals(false,offer.invoke(null,true,false,false));
 }
 @Test public void cursorCountIsBoundedCodePointsAndNeverContent()throws Exception {
  Method count=Class.forName("com.simon.voiceime.PendingVoiceDelivery").getDeclaredMethod("charsBeforeCursor",CharSequence.class);
  assertNull(count.invoke(null,new Object[]{null}));assertEquals(0,count.invoke(null,""));assertEquals(2,count.invoke(null,"甲𠀀"));assertEquals(1000,count.invoke(null,"字".repeat(2000)));
 }
 @Test public void noticesAreFixedPublicCopy()throws Exception {
  Class<?> p=Class.forName("com.simon.voiceime.PendingVoiceDelivery");assertEquals("語音文字已放入剪貼簿，請長按貼上",p.getDeclaredField("CLIPBOARD_NOTICE").get(null));assertEquals("開啟通知以接收補送提示",p.getDeclaredField("NOTIFICATION_ENTRY").get(null));
 }
}
