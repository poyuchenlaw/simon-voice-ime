package com.simon.voiceime;
import org.json.*;
/** Actual recorded key episode; accepted spelling limitation is recorded, not hidden. */
public class Release671AndroidTest extends TextRows670AndroidTest {
 public void testSimonRecordedKeysAndEnter()throws Exception{
  begin();String sequence="ㄊㄞˊㄍㄨㄤㄉㄧㄢˋㄏㄢˋㄔㄨㄤˋㄧˋㄌㄜ";JSONArray taps=new JSONArray();
  for(char key:sequence.toCharArray()){String label=key==' '?"空白":String.valueOf(key);android.graphics.Rect r=new android.graphics.Rect();await(label).getBoundsInScreen(r);taps.put(new JSONObject().put("key",label).put("x",r.centerX()).put("y",r.centerY()));tap(label);Thread.sleep(197);}
  Thread.sleep(400);inst.waitForIdleSync();final String[] actual={null},preview={null};inst.runOnMainSync(()->{actual[0]=actualController().sentenceKeys();preview[0]=row1.getText().toString();});
  assertEquals("recorded physical keys survive exactly",sequence,actual[0]);screenshot("simon-recorded-before-enter");tap("↵");String committed=String.valueOf(await("test_input").getText());
  assertEquals("Enter equals the visible preview",preview[0],committed);assertFalse("no phonetic glyph in committed text",committed.codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||"ˊˇˋ˙ˉ".indexOf(cp)>=0));
  save("simon-replay-result.json",new JSONObject().put("source_lines","4462–4502").put("expected_keys",sequence).put("actual_keys",actual[0]).put("preview",preview[0]).put("committed",committed).put("zero_zhuyin",true).put("physical_taps",taps).toString());screenshot("simon-recorded-committed");
 }
 public void testCandidateChoiceThenEnter()throws Exception{
  testTypingWordAndCharacter();String before=shown();screenshot("chosen-before-enter");tap("↵");String after=String.valueOf(await("test_input").getText());assertEquals("selected candidate survives through Enter",before,after);assertFalse("chosen commit contains no phonetic",after.codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||"ˊˇˋ˙ˉ".indexOf(cp)>=0));save("choice-enter-result.json",new JSONObject().put("preview",before).put("committed",after).put("zero_zhuyin",true).toString());screenshot("chosen-after-enter");
 }
}
