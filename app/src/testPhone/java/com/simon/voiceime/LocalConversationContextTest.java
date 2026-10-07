package com.simon.voiceime;
import org.junit.Test;import static org.junit.Assert.*;
public class LocalConversationContextTest {
 @Test public void localOnlyHintExpiresAndCannotCrossApps(){
  LocalConversationContext.clear();LocalConversationContext.update("chat.fixture","請確認損害賠償",100);
  assertEquals("請確認損害賠償",LocalConversationContext.text("chat.fixture",101));
  assertEquals("",LocalConversationContext.text("other.fixture",101));
  assertEquals("",LocalConversationContext.text("chat.fixture",30100));
 }
 @Test public void disableRestartAndMonotonicResetDiscardHint(){
  LocalConversationContext.update("chat.fixture","甲乙",100);LocalConversationContext.clear();assertEquals("",LocalConversationContext.text("chat.fixture",101));
  LocalConversationContext.update("chat.fixture","甲乙",100);assertEquals("",LocalConversationContext.text("chat.fixture",99));
 }
 @Test public void hintIsScalarBounded(){
  LocalConversationContext.update("chat.fixture","😀".repeat(200),100);String value=LocalConversationContext.text("chat.fixture",101);
  assertEquals(160,value.codePointCount(0,value.length()));assertEquals(320,value.length());
 }
}
