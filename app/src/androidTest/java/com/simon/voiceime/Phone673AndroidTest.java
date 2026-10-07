package com.simon.voiceime;
import android.os.SystemClock;import android.graphics.Rect;import android.widget.TextView;import org.json.*;
/** Physical keys / sandbox HTTP only. This is not provider quality evidence. */
public class Phone673AndroidTest extends LivePause670AndroidTest {
 private String contextFieldValue(String label)throws Exception {
  android.view.accessibility.AccessibilityNodeInfo field=await(label);
  return field.isShowingHintText()||field.getText()==null?"":field.getText().toString();
 }
 public void testCompositionDoesNotFollowAppFieldOrPassword()throws Exception {
  begin();typeToday();String first=shown();assertFalse(first.isEmpty());
  shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity --ez password false");
  tap("context_editor");await("ㄐ");bind();assertEquals("new App clears old preview","",shown());
  assertEquals("new App receives no previous composition","",contextFieldValue("context_editor"));
  typeToday();String second=shown();assertFalse(second.isEmpty());tap("context_editor_second");await("ㄐ");bind();
  assertEquals("new field clears old preview","",shown());tap("↵");
  assertEquals("Enter cannot send prior field text","",contextFieldValue("context_editor_second").trim());
  typeToday();assertFalse(shown().isEmpty());
  shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity --ez password true");
  tap("context_editor");await("ㄐ");bind();assertEquals("password hides previous composition","",shown());tap("↵");
  assertEquals("password receives no former field text","",contextFieldValue("context_editor").trim());
  save("673-field-privacy.json",new JSONObject().put("physical_keys",true).put("app_switch",true).put("field_switch",true).put("password_switch",true).toString());
 }
 private void recordSameFieldState(JSONArray trace,String stage)throws Exception {
  android.view.accessibility.AccessibilityNodeInfo editor=await("test_input");editor.refresh();
  JSONObject state=new JSONObject().put("stage",stage).put("uptime_ms",SystemClock.uptimeMillis())
   .put("editor_selection_start",editor.getTextSelectionStart()).put("editor_selection_end",editor.getTextSelectionEnd())
   .put("editor_text",editor.getText()==null?JSONObject.NULL:editor.getText().toString());
  inst.runOnMainSync(()->{try{
   android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();
   SimonIMEService service=(SimonIMEService)c;ZhuyinInputController controller=actualController();
   state.put("main_snapshot_uptime_ms",SystemClock.uptimeMillis()).put("controller_keys",controller.sentenceKeys())
    .put("controller_preview",controller.previewText()).put("displayed_preview",row1.getText().toString());
   for(String name:new String[]{"retainedTextNeedsReclaim","retainedTextEnd","retainedTextField","retainedTextBefore","retainedText"}){
    java.lang.reflect.Field field=SimonIMEService.class.getDeclaredField(name);field.setAccessible(true);state.put(name,field.get(service));
   }
   android.view.inputmethod.EditorInfo info=service.getCurrentInputEditorInfo();
   if(info!=null)state.put("current_editor_package",info.packageName).put("current_editor_field",info.fieldId)
    .put("editor_info_initial_selection_start",info.initialSelStart).put("editor_info_initial_selection_end",info.initialSelEnd);
   android.view.inputmethod.InputConnection ic=service.getCurrentInputConnection();
   java.lang.reflect.Field owner=SimonIMEService.class.getDeclaredField("zhuyinComposingConnection");owner.setAccessible(true);
   state.put("current_ic_class",ic==null?JSONObject.NULL:ic.getClass().getName()).put("current_ic_identity",System.identityHashCode(ic))
    .put("composing_owner_matches_current_ic",ic!=null&&owner.get(service)==ic);
   // Actual current selection is the public editor node above; no extra remote IC query alters lifecycle timing.
  }catch(Exception error){throw new RuntimeException(error);}});
  trace.put(state);save("673-same-field-state.json",trace.toString());
 }
 public void testSameFieldMovedCaretAllowsFreshTypingWithoutMovingOldText()throws Exception {
  JSONArray trace=new JSONArray();
  try {
   begin();typeToday();String old=shown();assertFalse(old.isEmpty());
   shell("input keyevent BACK");Thread.sleep(500);recordSameFieldState(trace,"after_back_hidden");
   android.view.accessibility.AccessibilityNodeInfo editor=await("test_input");
   android.os.Bundle selection=new android.os.Bundle();selection.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,0);selection.putInt(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,0);
   assertTrue("public editor cursor action",editor.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_SELECTION,selection));
   recordSameFieldState(trace,"after_set_selection");
   Rect bounds=new Rect();editor.getBoundsInScreen(bounds);tap(new Rect(bounds.left+2,bounds.centerY(),bounds.left+4,bounds.centerY()+2));
   recordSameFieldState(trace,"after_physical_reopen_tap");await("ㄐ");bind();recordSameFieldState(trace,"after_keyboard_bind");
   assertEquals("existing editor text remains at its original position",old,String.valueOf(await("test_input").getText()));assertEquals("old controller snapshot cannot block fresh input","",shown());
   typeToday();String fresh=shown();assertFalse("new physical keys produce new composition",fresh.isEmpty());tap("↵");
   assertEquals("new keys insert at current caret without replaying old text",fresh+old,String.valueOf(await("test_input").getText()));
   save("673-same-field-fresh.json",new JSONObject().put("physical_keys",true).put("old",old).put("fresh",fresh).put("old_text_preserved",true).toString());
  } finally {
   save("673-same-field-state.json",trace.toString());screenshot("673-same-field-state-screen");
  }
 }
 public void testShortWrongKeysDoNotCallAuto()throws Exception {
  begin();typeWrong();String original=shown();Thread.sleep(1600);
  assertEquals("short clauses keep local candidates",0,requestCount);assertEquals(original,shown());assertFalse(highlighted());
  tap("↵");assertEquals(original,String.valueOf(await("test_input").getText()));
  save("673-short.json",new JSONObject().put("physical_keys",true).put("auto_requests",requestCount).put("enter_matches_preview",true).toString());
 }
 public void testTenCharacterPauseThenKeepOnRejectedGate()throws Exception {
  begin();for(int n=0;n<5;n++)typeWrong();String original=shown();assertTrue("wrong physical keys produce long clause",original.codePointCount(0,original.length())>=10);
  inst.runOnMainSync(()->{TextView count=keyboard.findViewWithTag("composition-count");assertNotNull("separate visible first-row count",count);assertTrue(count.isShown());assertTrue(count.getText().toString().startsWith(original.codePointCount(0,original.length())+" 字"));});
  Thread.sleep(2600);assertTrue("actual sandbox endpoint requested",requestCount>0);JSONObject d=new JSONObject(diagnostic());
  assertTrue("one-second trailing pause",d.getLong("debounceEnd")-d.getLong("lastEdit")>=1000);
  assertEquals("semantic gate not_called preserves original",original,shown());assertFalse(highlighted());
  tap("↵");assertEquals(original,String.valueOf(await("test_input").getText()));save("673-ten-keep.json",d.put("original",original).put("provider_quality","UNVERIFIED").toString());
 }
 public void testManualWordThenContinueRetainsAuthority()throws Exception {
  begin();typeToday();awaitCandidateCount(words,1);awaitCandidateCount(characters,1);TextView choice=null;
  for(int i=0;i<words.getChildCount();i++){TextView item=(TextView)words.getChildAt(i);String t=item.getText().toString();if(t.codePointCount(0,t.length())==2&&!t.equals(shown())){choice=item;break;}}
  assertNotNull(choice);revealIndex(words,wordScroll,words.indexOfChild(choice));final TextView picked=choice;String word=choice.getText().toString();Rect r=new Rect();
  inst.runOnMainSync(()->{int[] at=new int[2];picked.getLocationOnScreen(at);r.set(at[0],at[1],at[0]+picked.getWidth(),at[1]+picked.getHeight());});tap(r);assertEquals(word,shown());
  for(int n=0;n<5;n++)typeToday();String original=shown();Thread.sleep(1600);
  assertEquals("manual choice prevents later auto request",0,requestCount);assertTrue("chosen word survives continuation",shown().startsWith(word));assertEquals(original,shown());
  tap("↵");assertEquals(original,String.valueOf(await("test_input").getText()));save("673-manual.json",new JSONObject().put("physical_second_row",true).put("continued_characters",10).put("auto_requests",requestCount).put("committed_preview",true).toString());
 }
 public void testNewKeyInvalidatesDelayedLongClause()throws Exception {
  delay=1800;begin();for(int n=0;n<5;n++)typeWrong();long deadline=SystemClock.uptimeMillis()+2300;
  while(requestCount==0&&SystemClock.uptimeMillis()<deadline)Thread.sleep(10);assertTrue("first generation really sent",requestCount>0);
  tap("ㄨ");tap("ㄛ");tap("ˇ");String latest=shown();Thread.sleep(3200);assertEquals("old async generation cannot overwrite new keys",latest,shown());assertFalse(highlighted());
  tap("↵");assertEquals(latest,String.valueOf(await("test_input").getText()));save("673-stale.json",new JSONObject().put("late_reply_preserves_new_keys",true).toString());
 }
}
