package com.simon.voiceime;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/** Existing committed Android editor selection, actual candidate view taps. */
public class ExternalSelection675AndroidTest extends TextRows670AndroidTest {
 @Override void awaitCandidateCount(android.widget.LinearLayout items,int count)throws Exception{
  AccessibilityNodeInfo input=await("test_input");String text=String.valueOf(input.getText());int left=Math.min(input.getTextSelectionStart(),input.getTextSelectionEnd()),right=Math.max(input.getTextSelectionStart(),input.getTextSelectionEnd());
  assertTrue("external editor owns a readable selected span",left>=0&&right>left&&right<=text.length());String selected=text.substring(left,right);
  long end=SystemClock.uptimeMillis()+5000;final boolean[] ready={false};
  while(SystemClock.uptimeMillis()<end){inst.runOnMainSync(()->{
   ready[0]=items.getChildCount()>=count;
   for(int n=0;n<items.getChildCount()&&ready[0];n++){
    android.view.View item=items.getChildAt(n);Object tag=item.getTag();ready[0]=item.isEnabled()&&tag instanceof ZhuyinInputController.TextChoice&&selected.equals(((ZhuyinInputController.TextChoice)tag).witness);
   }
  });if(ready[0])return;Thread.sleep(50);}
  fail("external selection candidates must appear within 5 seconds");
 }
 void text(String value)throws Exception{
  Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
  assertTrue(await("test_input").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));inst.waitForIdleSync();
 }
 void select(int start,int end)throws Exception{
  Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end);
  assertTrue(await("test_input").performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args));inst.waitForIdleSync();
 }
 void clickCandidate(TextView candidate)throws Exception{
  final Rect r=new Rect(),window=new Rect();inst.runOnMainSync(()->{
   int[] location=new int[2];candidate.getLocationOnScreen(location);
   assertTrue(candidate.getGlobalVisibleRect(window));
   assertTrue(candidate.getLocalVisibleRect(r));r.offset(location[0],location[1]);
  });
  save("external-tap-coordinates.json",new org.json.JSONObject().put("label",candidate.getText()).put("window_rect",window.toShortString()).put("screen_rect",r.toShortString()).toString());
  screenshot("external-before-screen-tap");tap(r);inst.waitForIdleSync();screenshot("external-after-screen-tap");
 }
 void staleWitness(String filename,TextView item,Object originalTag)throws Exception{
  final org.json.JSONObject witness=new org.json.JSONObject();
  inst.runOnMainSync(()->{try{
   Object tag=item.getTag();
   witness.put("view_identity",System.identityHashCode(item)).put("parent_identity",System.identityHashCode(item.getParent()))
    .put("attached",item.isAttachedToWindow()).put("text",item.getText()).put("enabled",item.isEnabled())
    .put("same_tag",tag==originalTag).put("tag_identity",System.identityHashCode(tag))
    .put("original_tag_identity",System.identityHashCode(originalTag));
   if(tag instanceof ZhuyinInputController.TextChoice){ZhuyinInputController.TextChoice c=(ZhuyinInputController.TextChoice)tag;witness.put("current_label",c.label).put("current_witness",c.witness);}
   if(originalTag instanceof ZhuyinInputController.TextChoice){ZhuyinInputController.TextChoice c=(ZhuyinInputController.TextChoice)originalTag;witness.put("original_label",c.label).put("original_witness",c.witness);}
  }catch(org.json.JSONException error){throw new RuntimeException(error);}});
  AccessibilityNodeInfo input=await("test_input");witness.put("editor_text",input.getText()).put("selection_start",input.getTextSelectionStart()).put("selection_end",input.getTextSelectionEnd());
  save(filename,witness.toString());
 }
 public void testCommittedSelectionUpdatesBothRowsAndReplacesOnlySpan()throws Exception{
  begin();text("前文時間後文");select(2,4);awaitCandidateCount(words,1);awaitCandidateCount(characters,1);
  assertEquals("external selection never enters preview","",shown());
  TextView word=(TextView)words.getChildAt(0);String replacement=word.getText().toString();clickCandidate(word);
  assertEquals("word replacement preserves both neighbours","前文"+replacement+"後文",String.valueOf(await("test_input").getText()));
  text("前文時間後文");select(2,4);awaitCandidateCount(words,1);awaitCandidateCount(characters,1);
  TextView character=(TextView)characters.getChildAt(0);String glyph=character.getText().toString();assertEquals(1,glyph.codePointCount(0,glyph.length()));clickCandidate(character);
  assertEquals("character replacement only touches selected span","前文"+glyph+"後文",String.valueOf(await("test_input").getText()));
 }
 public void testChangedSelectionAndEditDiscardPendingCandidates()throws Exception{
  begin();text("前文時間後文");select(2,4);awaitCandidateCount(words,1);awaitCandidateCount(characters,1);
  TextView stale=(TextView)words.getChildAt(0);
  Object originalTag=stale.getTag();String originalLabel=stale.getText().toString();
  staleWitness("stale-before-edit.json",stale,originalTag);
  text("完全不同內容");select(0,0);Thread.sleep(400);inst.waitForIdleSync();
  AccessibilityNodeInfo changed=await("test_input");assertEquals(0,changed.getTextSelectionStart());assertEquals(0,changed.getTextSelectionEnd());
  inst.runOnMainSync(()->{assertSame("saved old candidate payload is not rebound",originalTag,stale.getTag());assertEquals(originalLabel,((ZhuyinInputController.TextChoice)stale.getTag()).label);assertEquals(originalLabel,stale.getText().toString());assertEquals("時間",((ZhuyinInputController.TextChoice)stale.getTag()).witness);});
  assertEquals("external selection leaves preview empty before stale click","",shown());
  staleWitness("stale-after-edit-before-click-public.json",stale,originalTag);
  screenshot("stale-view-before-click");
  inst.runOnMainSync(()->stale.performClick());inst.waitForIdleSync();
  staleWitness("stale-after-click.json",stale,originalTag);
  assertEquals("old candidate click cannot alter the new collapsed selection","完全不同內容",String.valueOf(await("test_input").getText()));
  assertEquals("old candidate click cannot contaminate preview","",shown());
  text("前文時間後文");select(2,4);select(0,2);text("完全不同內容");select(0,0);Thread.sleep(400);inst.waitForIdleSync();
  assertEquals("typing invalidates pending selection candidates",0,words.getChildCount());assertEquals(0,characters.getChildCount());
  assertEquals("old result cannot rewrite new editor text","完全不同內容",String.valueOf(await("test_input").getText()));
  text("前文時間後文");select(2,4);awaitCandidateCount(words,1);awaitCandidateCount(characters,1);
 }
 public void testUnknownSelectionLeavesEditorUnchanged()throws Exception{
  begin();text("前文🙂後文");select(2,4);Thread.sleep(400);inst.waitForIdleSync();
  assertEquals("unknown spelling cannot fabricate alternatives",0,characters.getChildCount());
  assertEquals("unknown text stays untouched","前文🙂後文",String.valueOf(await("test_input").getText()));
 }
 public void testProtectedFieldOffersNoExternalCandidates()throws Exception{
  begin();
  try{
   String launch=shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity --ei input_type 129");tap("test_input");await("ㄗ");bind();
   final int[] actualInputType={-1};inst.runOnMainSync(()->{
    android.content.Context context=row1.getContext();while(!(context instanceof SimonIMEService)&&context instanceof android.content.ContextWrapper)context=((android.content.ContextWrapper)context).getBaseContext();
    assertTrue(context instanceof SimonIMEService);actualInputType[0]=((SimonIMEService)context).getCurrentInputEditorInfo().inputType;
   });
   AccessibilityNodeInfo passwordNode=await("test_input");save("password-public-metadata.json",new org.json.JSONObject().put("am_launch",launch).put("input_type",actualInputType[0]).put("node_password",passwordNode.isPassword()).put("node_class",passwordNode.getClassName()).toString());
   assertEquals("real EditText supplies text-password EditorInfo",129,actualInputType[0]&(android.text.InputType.TYPE_MASK_CLASS|android.text.InputType.TYPE_MASK_VARIATION));
   assertTrue("real editor AccessibilityNode is password",passwordNode.isPassword());
   text("前文時間後文");select(2,4);Thread.sleep(400);inst.waitForIdleSync();
   assertEquals("password metadata from real EditText prevents external candidates",0,words.getChildCount());assertEquals(0,characters.getChildCount());
  }finally{
   save("password-fixture-restore.txt",shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity"));
  }
 }
}
