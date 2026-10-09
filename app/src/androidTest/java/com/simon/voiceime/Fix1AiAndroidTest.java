package com.simon.voiceime;
import org.json.JSONObject;
public class Fix1AiAndroidTest extends TrustedChip673AndroidTest {
 public void testSentenceCandidateThenDelete()throws Exception{
  begin();for(int i=0;i<4;i++)typeToday();typeWrong();
  long deadline=android.os.SystemClock.uptimeMillis()+5000;while(android.os.SystemClock.uptimeMillis()<deadline&&!shown().equals(prefix+"多餘"))Thread.sleep(50);
  save("ai-before-delete-text.json",new JSONObject().put("preview",shown()).put("request_count",requestCount).toString());screenshot("ai-before-delete");
  assertEquals("real fixture sentence correction displayed",prefix+"多餘",shown());
  tap("⌫");Thread.sleep(300);save("ai-after-delete-text.json",new JSONObject().put("preview",shown()).toString());screenshot("ai-after-delete");assertFalse("delete changes corrected sentence",shown().equals(prefix+"多餘"));
 }
}
