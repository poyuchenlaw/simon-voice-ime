package com.simon.voiceime;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class TextRows670ProtectionTest {
 @Test public void newTextTelemetryContainsCountsAndNoComposedText()throws Exception {
  org.json.JSONObject fields=new org.json.JSONObject().put("text","private sentence").put("engine_top1","private prediction").put("shown",new org.json.JSONArray().put("private candidate")).put("ai_shown",true).put("reverted_spans",2).put("candidate_taps",3);
  org.json.JSONObject clean=ImeTelemetry.textOnlyMetadata(fields);
  assertEquals("",clean.getString("text"));assertEquals("",clean.getString("engine_top1"));assertEquals(0,clean.getJSONArray("shown").length());assertTrue(clean.getBoolean("ai_shown"));assertEquals(2,clean.getInt("reverted_spans"));assertEquals(3,clean.getInt("candidate_taps"));assertEquals("private sentence",fields.getString("text"));
 }
 @Test public void explicitLatinDigitsPunctuationAndDictionaryAreProtected(){
  assertTrue(AiComposition.protectedChange("甲A乙","甲B乙",Collections.emptyList()));
  assertTrue(AiComposition.protectedChange("甲7乙","甲8乙",Collections.emptyList()));
  assertTrue(AiComposition.protectedChange("甲，乙","甲。乙",Collections.emptyList()));
  assertTrue(AiComposition.protectedChange("核心詞語","核心辭語",Arrays.asList("詞語")));
  assertFalse(AiComposition.protectedChange("舵餘","多餘",Collections.emptyList()));
 }
}
