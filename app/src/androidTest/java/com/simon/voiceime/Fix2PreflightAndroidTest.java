package com.simon.voiceime;
public class Fix2PreflightAndroidTest extends ExternalSelection675AndroidTest {
 public void testSmallEditor() throws Exception {
  begin();text("X1");select(1,1);readback("preflight-text.json");screenshot("preflight");
  assertEquals("X1",editorText(await("test_input")));assertEquals(1,await("test_input").getTextSelectionStart());assertEquals(1,await("test_input").getTextSelectionEnd());
 }
}
