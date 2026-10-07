package com.simon.voiceime;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
/** Public native editor caret sibling of the hide/reopen case; no state injection. */
public class VisibleCaret673AndroidTest extends TextRows670AndroidTest {
 private void recordPublicState(JSONArray trace,String stage)throws Exception {
  AccessibilityNodeInfo editor=await("test_input");editor.refresh();
  JSONObject state=new JSONObject().put("stage",stage).put("uptime_ms",SystemClock.uptimeMillis())
   .put("editor_text",editor.getText()==null?JSONObject.NULL:editor.getText().toString())
   .put("selection_start",editor.getTextSelectionStart()).put("selection_end",editor.getTextSelectionEnd());
  inst.runOnMainSync(()->{try{ZhuyinInputController controller=actualController();
   state.put("controller_keys",controller.sentenceKeys()).put("controller_preview",controller.previewText())
    .put("controller_boundary",controller.previewBoundary()).put("controller_key_caret",controller.keyCaret())
    .put("displayed_preview",row1.getText().toString());
  }catch(Exception error){throw new RuntimeException(error);}});
  trace.put(state);save("673-visible-caret-state.json",trace.toString());
 }
 public void testVisibleEditorCaretPreservesOldTextAndInsertsFreshKeysAtCaret()throws Exception {
  JSONArray trace=new JSONArray();
  try {
   begin();typeToday();assertEquals("independently known old composition","今天",shown());
   recordPublicState(trace,"before_visible_caret_action");
   AccessibilityNodeInfo editor=await("test_input");Bundle selection=new Bundle();
   selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);
   selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,0);
   assertTrue("public visible editor cursor action",editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection));
   editor=await("test_input");editor.refresh();
   assertEquals("actual visible editor caret start",0,editor.getTextSelectionStart());
   assertEquals("actual visible editor caret end",0,editor.getTextSelectionEnd());
   assertEquals("moving caret preserves old editor text","今天",String.valueOf(editor.getText()));
   await("ㄐ");recordPublicState(trace,"after_visible_caret_action");
   for(String key:new String[]{"ㄨ","ㄛ","ˇ"})tap(key);
   recordPublicState(trace,"after_fresh_physical_keys");tap("↵");
   recordPublicState(trace,"after_enter");
   assertEquals("fresh keys insert at current visible editor caret without replaying old text","我今天",String.valueOf(await("test_input").getText()));
   save("673-visible-caret-result.json",new JSONObject().put("physical_keys",true).put("expected","我今天").put("old_text_preserved",true).put("fresh_at_caret",true).toString());
  } finally {
   save("673-visible-caret-state.json",trace.toString());screenshot("673-visible-caret-state-screen");
  }
 }
 private void runOutsideOwnedSpanCaret(boolean after)throws Exception {
  JSONArray trace=new JSONArray();
  try {
   begin();AccessibilityNodeInfo editor=await("test_input");Bundle text=new Bundle();
   text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"前後");
   assertTrue("public initial prefix and suffix",editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text));
   editor=await("test_input");editor.refresh();assertEquals("known initial editor context","前後",String.valueOf(editor.getText()));
   Bundle selection=new Bundle();selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,1);
   selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,1);
   assertTrue("public initial caret between context",editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection));
   editor=await("test_input");editor.refresh();assertEquals(1,editor.getTextSelectionStart());assertEquals(1,editor.getTextSelectionEnd());
   await("ㄐ");typeToday();assertEquals("independently known owned composition","今天",shown());
   editor=await("test_input");editor.refresh();assertEquals("original text with owned span and both contexts","前今天後",String.valueOf(editor.getText()));
   recordPublicState(trace,"before_outside_caret_action");int caret=after?4:0;
   selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,caret);
   selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,caret);
   assertTrue("public outside-owned-span caret action",editor.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection));
   editor=await("test_input");editor.refresh();assertEquals("actual outside caret start",caret,editor.getTextSelectionStart());
   assertEquals("actual outside caret end",caret,editor.getTextSelectionEnd());
   assertEquals("outside caret movement preserves all original editor text","前今天後",String.valueOf(editor.getText()));
   await("ㄐ");recordPublicState(trace,"after_outside_caret_action");
   for(String key:new String[]{"ㄨ","ㄛ","ˇ"})tap(key);
   recordPublicState(trace,"after_fresh_outside_physical_keys");tap("↵");recordPublicState(trace,"after_outside_enter");
   String expected=after?"前今天後我":"我前今天後";
   assertEquals("fresh keys at actual outside caret preserve prefix old span and suffix",expected,String.valueOf(await("test_input").getText()));
   save("673-visible-outside-"+(after?"end":"start")+"-result.json",new JSONObject().put("physical_keys",true).put("expected",expected).put("caret",caret).put("prefix_old_suffix_preserved",true).toString());
  } finally {
   save("673-visible-outside-"+(after?"end":"start")+"-state.json",trace.toString());
   screenshot("673-visible-outside-"+(after?"end":"start")+"-state-screen");
  }
 }
 public void testVisibleEditorCaretBeforeOwnedSpanPreservesPrefixOldTextAndSuffix()throws Exception {runOutsideOwnedSpanCaret(false);}
 public void testVisibleEditorCaretAfterOwnedSpanPreservesPrefixOldTextAndSuffix()throws Exception {runOutsideOwnedSpanCaret(true);}
}
