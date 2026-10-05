package com.simon.voiceime;
import android.widget.TextView;import org.json.JSONObject;
/** A detached old menu's delayed click must not replace a newer composition. */
public class AsyncCandidates670AndroidTest extends TextRows670AndroidTest {
 public void testStaleViewAfterRapidEditingCannotMutate()throws Exception {
  begin();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白"})tap(k);assertEquals("金",shown());awaitCandidateCount(characters,2);
  TextView stale=(TextView)characters.getChildAt(1);
  tap("ㄊ");Thread.sleep(700);for(String k:new String[]{"ㄧ","ㄢ","空白"})tap(k);assertEquals("今天",shown());
  inst.runOnMainSync(()->stale.performClick());assertEquals("delayed old view cannot mutate new input","今天",shown());
  awaitCandidateCount(characters,2);tap("↵");assertEquals("今天",String.valueOf(await("test_input").getText()));
  save("stale-candidate-result.json",new JSONObject().put("initial","金").put("new_input","今天").put("stale_click_ignored",true).put("committed","今天").toString());
 }
}
