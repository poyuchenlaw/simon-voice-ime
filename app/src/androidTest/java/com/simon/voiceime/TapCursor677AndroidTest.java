package com.simon.voiceime;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.JSONObject;

/** Real committed EditText, screen taps and exact neighbour preservation. */
public class TapCursor677AndroidTest extends ExternalSelection675AndroidTest {
 private static final String SENTENCE="我們明天十點在法院見面";
 static boolean isTapCandidate(Object tag){return tag instanceof ZhuyinInputController.TextChoice&&((ZhuyinInputController.TextChoice)tag).start>=0&&((ZhuyinInputController.TextChoice)tag).boundary==-1;}
 @Override boolean externalCandidate(Object tag){return isTapCandidate(tag);}
 long lastCaretTapUptime;
 static RectF glyphAt(Parcelable[] values,int index){
  if(values==null||index<0||index>=values.length||!(values[index] instanceof RectF))return null;
  RectF glyph=(RectF)values[index];return glyph.isEmpty()||!Float.isFinite(glyph.left)||!Float.isFinite(glyph.top)||!Float.isFinite(glyph.right)||!Float.isFinite(glyph.bottom)?null:glyph;
 }
 void tapCaret(int offset)throws Exception{
  awaitStableWindow();Bundle args=new Bundle();
  args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,offset-1);
  args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,1);
  long deadline=SystemClock.uptimeMillis()+2000;RectF glyph=null;String source="accessibility-extra-data";
  AccessibilityNodeInfo input=null;
  while(SystemClock.uptimeMillis()<deadline){
   input=await("test_input");input.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,args);
   glyph=glyphAt(input.getExtras().getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY),0);
   if(glyph!=null)break;Thread.sleep(40);
  }
  if(glyph==null){
   source="fixture-Layout";deadline=SystemClock.uptimeMillis()+3000;
   while(SystemClock.uptimeMillis()<deadline){
    input=await("test_input");input.refresh();
    if(editorText(input).equals(input.getExtras().getString("fixture_text")))glyph=glyphAt(input.getExtras().getParcelableArray("fixture_glyphs"),offset-1);
    if(glyph!=null)break;Thread.sleep(40);
   }
  }
  assertNotNull("layout glyph ready within bounded standard/fixture polling",glyph);
  assertTrue("editor focused before caret tap",input.isFocused());
  int x=Math.round(glyph.right-1),y=Math.round(glyph.centerY());Rect visible=new Rect();input.getBoundsInScreen(visible);
  assertTrue("actual glyph tap inside editor",visible.contains(x,y));
  save("caret-tap.json",new JSONObject().put("offset",offset).put("character",editorText(input).substring(offset-1,offset)).put("source",source).put("glyph",glyph.toString()).put("x",x).put("y",y).toString());
  lastCaretTapUptime=SystemClock.uptimeMillis();tap(new Rect(x,y,x+1,y+1));inst.waitForIdleSync();
  long caretDeadline=SystemClock.uptimeMillis()+3000;AccessibilityNodeInfo after;
  do{after=await("test_input");if(after.getTextSelectionStart()==offset&&after.getTextSelectionEnd()==offset)break;Thread.sleep(20);}while(SystemClock.uptimeMillis()<caretDeadline);
  assertEquals(offset,after.getTextSelectionStart());assertEquals(offset,after.getTextSelectionEnd());
 }
 void focusEmptyEditor()throws Exception{
  tap("test_input");inst.waitForIdleSync();await("ㄗ");bind();awaitStableWindow();
  long deadline=SystemClock.uptimeMillis()+5000;AccessibilityNodeInfo input;
  do{input=await("test_input");input.refresh();if(RapidKeyboard676AndroidTest.emptyEditorReady(input))break;Thread.sleep(40);}while(SystemClock.uptimeMillis()<deadline);
  save("empty-editor-readiness.json",new JSONObject().put("focused",input.isFocused()).put("accessibility_start",input.getTextSelectionStart()).put("accessibility_end",input.getTextSelectionEnd()).put("fixture_text_length",input.getExtras().getInt("fixture_text_length",-1)).put("fixture_start",input.getExtras().getInt("fixture_selection_start",-1)).put("fixture_end",input.getExtras().getInt("fixture_selection_end",-1)).put("ready",RapidKeyboard676AndroidTest.emptyEditorReady(input)).toString());
  assertTrue("focused fixture empty text and actual caret 0 within deadline",RapidKeyboard676AndroidTest.emptyEditorReady(input));
 }
 void awaitTapRows(int caret)throws Exception{
  long deadline=SystemClock.uptimeMillis()+5000;final boolean[] ready={false};
  while(SystemClock.uptimeMillis()<deadline){inst.runOnMainSync(()->{
   ready[0]=externalCount(words)>0&&externalCount(characters)>0;
   for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})for(int n=0;n<row.getChildCount()&&ready[0];n++){
    Object tag=row.getChildAt(n).getTag();ready[0]=isTapCandidate(tag)||!row.getChildAt(n).isEnabled()&&row.getChildAt(n) instanceof TextView&&((TextView)row.getChildAt(n)).getText().toString().contains("無同音詞");
   }
  });if(ready[0]){AccessibilityNodeInfo node=await("test_input");assertEquals(caret,node.getTextSelectionStart());assertEquals(caret,node.getTextSelectionEnd());save("tap-row-latency.json",new JSONObject().put("caret",caret).put("tap_to_rows_observed_ms",SystemClock.uptimeMillis()-lastCaretTapUptime).put("includes","physical injection, editor callback, debounce, row refresh and observer poll").put("apk_sha256",androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("apk_sha256")).toString());return;}Thread.sleep(20);}
  fail("tap must produce word and homophone rows without selecting editor text");
 }
 void observeWordReading()throws Exception{
  android.content.Context context=words.getContext();
  ZhuyinWordIndex dictionary=ZhuyinWordIndex.open(context);
  try{
   java.util.List<ZhuyinWordIndex.Entry> exact=dictionary.homophoneCandidates("法院",false);
   String first=firstWordCandidate().getText().toString();
   boolean status=false;for(int n=0;n<words.getChildCount();n++)if(words.getChildAt(n) instanceof TextView&&((TextView)words.getChildAt(n)).getText().toString().contains("無同音詞"))status=true;
   if(exact.isEmpty())assertTrue("no homophone must be explicitly visible",status);
   else assertTrue("first candidate must have all syllables and tones equal",exact.stream().anyMatch(e->e.word.equals(first)));
   save("court-reading-observation.json",new JSONObject().put("target","法院").put("target_reading","ㄈㄚˇ ㄩㄢˋ").put("first_candidate",first).put("exact_homophone_count",exact.size()).put("no_homophone_status_visible",status).toString());
   screenshot("court-second-row-reading");readback("court-second-row-text.json");
  }finally{dictionary.close();}
 }
 TextView firstWordCandidate(){for(int n=0;n<words.getChildCount();n++)if(isTapCandidate(words.getChildAt(n).getTag()))return (TextView)words.getChildAt(n);throw new AssertionError("word candidate required");}
 public void testTapRowsAndCharacterReplacement()throws Exception{
  begin();text(SENTENCE);tapCaret(9);awaitTapRows(9);screenshot("tap-rows");readback("tap-rows-text.json");
  TextView item=(TextView)characters.getChildAt(0);String glyph=item.getText().toString();assertEquals(1,glyph.codePointCount(0,glyph.length()));
  clickCandidate(item);assertEquals("only previous character replaced",SENTENCE.substring(0,8)+glyph+SENTENCE.substring(9),String.valueOf(await("test_input").getText()));
  readback("character-replaced.json");Thread.sleep(400);inst.waitForIdleSync();assertNoExternalCandidates();
 }
 public void testTapWordReplacementAndTypingClear()throws Exception{
  begin();text(SENTENCE);tapCaret(9);awaitTapRows(9);observeWordReading();TextView item=firstWordCandidate();String word=item.getText().toString();
  clickCandidate(item);assertEquals("only dictionary target replaced",SENTENCE.substring(0,7)+word+SENTENCE.substring(9),String.valueOf(await("test_input").getText()));readback("word-replaced.json");
  text(SENTENCE);tapCaret(9);awaitTapRows(9);rowTags("typing-before-tags.json");
  java.util.concurrent.atomic.AtomicBoolean oldTapDrawn=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.concurrent.atomic.AtomicReference<String> oldDrawTags=new java.util.concurrent.atomic.AtomicReference<>();
  java.util.concurrent.atomic.AtomicBoolean keyDownSeen=new java.util.concurrent.atomic.AtomicBoolean();
  android.view.View ancestor=words;while(!(ancestor instanceof KeyboardInputLayout)&&ancestor.getParent() instanceof android.view.View)ancestor=(android.view.View)ancestor.getParent();
  assertTrue("keyboard touch owner found",ancestor instanceof KeyboardInputLayout);
  KeyboardInputLayout keyboard=(KeyboardInputLayout)ancestor;
  java.util.function.Consumer<android.view.MotionEvent> before=keyboard.beforeTouch;
  inst.runOnMainSync(()->keyboard.beforeTouch=event->{before.accept(event);keyDownSeen.set(true);assertNoExternalCandidates();});
  android.view.ViewTreeObserver.OnDrawListener clearedDraw=()->{if(!keyDownSeen.get())return;for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})for(int n=0;n<row.getChildCount();n++)if(isTapCandidate(row.getChildAt(n).getTag())){oldTapDrawn.set(true);oldDrawTags.compareAndSet(null,rowSnapshot().toString());}};
  inst.runOnMainSync(()->root.getViewTreeObserver().addOnDrawListener(clearedDraw));
  try{tap("ㄅ");inst.waitForIdleSync();assertTrue("real key down observed",keyDownSeen.get());assertFalse("no external tap candidate survives a draw after key",oldTapDrawn.get());}
  finally{inst.runOnMainSync(()->{keyboard.beforeTouch=before;root.getViewTreeObserver().removeOnDrawListener(clearedDraw);});save("typing-transient-tags.json",String.valueOf(oldDrawTags.get()));}
  rowTags("typing-after-tags.json");
  inst.runOnMainSync(()->{for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})for(int n=0;n<row.getChildCount();n++){
   Object tag=row.getChildAt(n).getTag();assertFalse("committed tap candidates removed on first key: "+rowSnapshot(),isTapCandidate(tag));
  }});screenshot("zhuyin-cleared-tap-rows");readback("zhuyin-cleared-text.json");
 }
 public void testScrolledCandidateReplacement()throws Exception{
  begin();text(SENTENCE);tapCaret(9);awaitTapRows(9);
  android.widget.HorizontalScrollView scroll=(android.widget.HorizontalScrollView)characters.getParent();
  final Rect viewport=new Rect();final int[] before={0};
  inst.runOnMainSync(()->{int[] at=new int[2];scroll.getLocationOnScreen(at);viewport.set(at[0],at[1],at[0]+scroll.getWidth(),at[1]+scroll.getHeight());before[0]=scroll.getScrollX();});
  shell("input swipe "+(viewport.right-20)+" "+viewport.centerY()+" "+(viewport.left+20)+" "+viewport.centerY()+" 300");
  Thread.sleep(400);inst.waitForIdleSync();
  final TextView[] chosen={null};final Rect hit=new Rect();final int[] after={0};
  inst.runOnMainSync(()->{
   after[0]=scroll.getScrollX();assertTrue("physical swipe moved to later candidates",after[0]>before[0]);
   assertTrue("scroll preserves external candidates: "+rowSnapshot(),externalCount(characters)>0);
   for(int n=characters.getChildCount()-1;n>=0;n--){if(!isTapCandidate(characters.getChildAt(n).getTag()))continue;TextView item=(TextView)characters.getChildAt(n);int[] at=new int[2];item.getLocationOnScreen(at);Rect r=new Rect(at[0],at[1],at[0]+item.getWidth(),at[1]+item.getHeight());if(r.intersect(viewport)&&r.width()>20){chosen[0]=item;hit.set(r);break;}}
  });
  assertNotNull("later visible candidate",chosen[0]);String glyph=chosen[0].getText().toString();
  save("scrolled-target.json",new JSONObject().put("scroll_before",before[0]).put("scroll_after",after[0]).put("label",glyph).put("screen_rect",hit.toShortString()).toString());
  screenshot("scrolled-before-tap");readback("scrolled-before-text.json");tap(hit);inst.waitForIdleSync();
  assertEquals("scrolled candidate replaces only target",SENTENCE.substring(0,8)+glyph+SENTENCE.substring(9),String.valueOf(await("test_input").getText()));
  screenshot("scrolled-after-tap");readback("scrolled-after-text.json");
 }
 public void testKeyboardCommitAndDeleteDoNotTriggerTapRows()throws Exception{
  begin();text(SENTENCE);tap("空白");Thread.sleep(400);inst.waitForIdleSync();rowTags("space-after-tags.json");readback("space-after-text.json");assertNoExternalCandidates();
  tap("⌫");Thread.sleep(400);inst.waitForIdleSync();rowTags("delete-after-tags.json");assertNoExternalCandidates();readback("keyboard-self-moves.json");screenshot("keyboard-self-moves");
 }
 public void testPasswordCaretOffersNoTapRows()throws Exception{
  begin();try{
   shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity --ei input_type 129");tap("test_input");await("ㄗ");bind();
   assertTrue(await("test_input").isPassword());text(SENTENCE);select(9,9);Thread.sleep(400);inst.waitForIdleSync();
   assertNoExternalCandidates();screenshot("password-no-tap-rows");readback("password-text.json");
  }finally{shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity");}
 }

 public void testRapidTwentyCharactersAndTenDeletes()throws Exception{
  begin();text("");focusEmptyEditor();java.util.Map<String,Rect> keys=new java.util.HashMap<>();
  for(String key:new String[]{"ㄊ","ㄧ","ㄢ","空白","↵","⌫"}){Rect r=new Rect();await(key).getBoundsInScreen(r);assertFalse("key has visible bounds: "+key,r.isEmpty());keys.put(key,r);}
  java.util.concurrent.atomic.AtomicBoolean externalSeen=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.concurrent.atomic.AtomicReference<String> transientTags=new java.util.concurrent.atomic.AtomicReference<>();
  android.view.ViewTreeObserver.OnDrawListener listener=()->{
   for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})for(int n=0;n<row.getChildCount();n++){
    Object tag=row.getChildAt(n).getTag();if(isTapCandidate(tag)){externalSeen.set(true);transientTags.compareAndSet(null,rowSnapshot().toString());}
   }
  };
  inst.runOnMainSync(()->root.getViewTreeObserver().addOnDrawListener(listener));
  org.json.JSONArray latencies=new org.json.JSONArray();
  try{
   for(int n=0;n<20;n++)for(String key:new String[]{"ㄊ","ㄧ","ㄢ","空白","↵"}){
    long start=SystemClock.uptimeMillis();tap(keys.get(key));inst.waitForIdleSync();latencies.put(SystemClock.uptimeMillis()-start);assertNoExternalCandidates();
   }
   assertEquals("twenty real keyboard characters committed","天".repeat(20),String.valueOf(await("test_input").getText()));
   screenshot("rapid-twenty-characters");readback("rapid-twenty-text.json");
   for(int n=0;n<10;n++){long start=SystemClock.uptimeMillis();tap(keys.get("⌫"));inst.waitForIdleSync();latencies.put(SystemClock.uptimeMillis()-start);assertNoExternalCandidates();}
   Thread.sleep(500);inst.waitForIdleSync();assertNoExternalCandidates();assertFalse("no transient external candidates in any observed draw",externalSeen.get());
   assertEquals("ten deletes preserve first ten characters","天".repeat(10),String.valueOf(await("test_input").getText()));
   screenshot("rapid-after-ten-deletes");readback("rapid-after-delete-text.json");
  }finally{inst.runOnMainSync(()->root.getViewTreeObserver().removeOnDrawListener(listener));save("rapid-transient-tags.json",String.valueOf(transientTags.get()));save("rapid-latency.json",new JSONObject().put("apk_sha256",androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("apk_sha256")).put("measurement","physical down/up injection through main-thread idle, same keys for 6.76 and 6.77").put("per_key_ms",latencies).put("external_seen",externalSeen.get()).toString());}
 }
 public void testHideReopenAndFieldSwitchClearRows()throws Exception{
  begin();shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity --ez second_field true");tap("test_input");await("ㄗ");bind();
  text(SENTENCE);tapCaret(9);awaitTapRows(9);shell("input keyevent BACK");Thread.sleep(400);inst.waitForIdleSync();
  assertNoExternalCandidates();screenshot("hidden-cleared");readback("hidden-text.json");
  tap("test_input");await("ㄗ");bind();Thread.sleep(300);assertNoExternalCandidates();screenshot("reopened-cleared");readback("reopened-text.json");
  select(11,11);tapCaret(9);awaitTapRows(9);tap("test_input_second");Thread.sleep(400);inst.waitForIdleSync();bind();
  assertNoExternalCandidates();screenshot("field-switched-cleared");
  AccessibilityNodeInfo second=await("test_input_second");assertTrue("second field focused",second.isFocused());assertEquals("",editorText(second));assertEquals(SENTENCE,editorText(await("test_input")));readback("field-switch-original-text.json");
  save("second-field.json",new JSONObject().put("text",editorText(second)).put("showing_hint",second.isShowingHintText()).put("start",second.getTextSelectionStart()).put("end",second.getTextSelectionEnd()).put("apk_sha256",androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("apk_sha256")).toString());
 }
 public void testUnreadableTapIsSilent()throws Exception{
  begin();shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity --ez unreadable true");tap("test_input");await("ㄗ");bind();text(SENTENCE);
  java.util.List<String> notifications=new java.util.concurrent.CopyOnWriteArrayList<>();
  ui.setOnAccessibilityEventListener(event->{if(event.getEventType()==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)notifications.add(event.getText().toString());});
  try{tapCaret(9);Thread.sleep(700);inst.waitForIdleSync();assertNoExternalCandidates();
   for(String notification:notifications)assertFalse("single tap must not show unreadable-selection hint",notification.contains("未提供可讀取"));
   assertEquals(SENTENCE,String.valueOf(await("test_input").getText()));screenshot("unreadable-silent");readback("unreadable-text.json");save("unreadable-notifications.json",new org.json.JSONArray(notifications).toString());
  }finally{ui.setOnAccessibilityEventListener(null);}
 }
 public void testFailedReplacementRestoresOriginalCaret()throws Exception{
  begin();shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity --ez fail_selection_validation true");tap("test_input");await("ㄗ");bind();
  text(SENTENCE);tapCaret(9);awaitTapRows(9);clickCandidate(firstWordCandidate());Thread.sleep(300);inst.waitForIdleSync();
  AccessibilityNodeInfo input=await("test_input");assertEquals(SENTENCE,String.valueOf(input.getText()));assertEquals(9,input.getTextSelectionStart());assertEquals(9,input.getTextSelectionEnd());
  assertNoExternalCandidates();screenshot("failed-replacement-restored");readback("failed-replacement-text.json");
 }
}
