package com.simon.voiceime;
import android.os.Bundle;
import android.os.SystemClock;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/** Existing committed Android editor selection, actual candidate view taps. */
public class ExternalSelection675AndroidTest extends TextRows670AndroidTest {

 static boolean isExternalCandidate(Object tag){return tag instanceof ZhuyinInputController.TextChoice&&((ZhuyinInputController.TextChoice)tag).boundary==-1;}
 boolean externalCandidate(Object tag){return isExternalCandidate(tag);}
 int externalCount(android.widget.LinearLayout row){int count=0;for(int n=0;n<row.getChildCount();n++)if(externalCandidate(row.getChildAt(n).getTag()))count++;return count;}
 org.json.JSONArray rowSnapshot(){
  org.json.JSONArray rows=new org.json.JSONArray();
  try{for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters}){
   org.json.JSONObject entry=new org.json.JSONObject().put("row",row==words?"words":"characters");org.json.JSONArray children=new org.json.JSONArray();
   if(row==null){rows.put(entry.put("unbound",true));continue;}
   entry.put("child_count",row.getChildCount()).put("external_count",externalCount(row)).put("attached",row.isAttachedToWindow());
   for(int n=0;n<row.getChildCount();n++){
    android.view.View view=row.getChildAt(n);Object tag=view.getTag();org.json.JSONObject child=new org.json.JSONObject().put("index",n).put("tag",String.valueOf(tag)).put("tag_class",tag==null?"null":tag.getClass().getName()).put("text",view instanceof TextView?((TextView)view).getText():"").put("enabled",view.isEnabled()).put("external",externalCandidate(tag));
    if(tag instanceof ZhuyinInputController.TextChoice){var c=(ZhuyinInputController.TextChoice)tag;child.put("label",c.label).put("witness",c.witness).put("keys",c.keys).put("boundary",c.boundary).put("start",c.start).put("end",c.end);}
    children.put(child);
   }rows.put(entry.put("children",children));
  }}catch(org.json.JSONException e){throw new RuntimeException(e);}return rows;
 }
 void rowTags(String name)throws Exception{final String[] data={null};inst.runOnMainSync(()->data[0]=rowSnapshot().toString());save(name,data[0]);}
 void assertNoExternalCandidates(){
  Runnable check=()->{for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})if(externalCount(row)!=0)fail("external transaction survived: "+rowSnapshot());};
  if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())check.run();else inst.runOnMainSync(check);
 }
 @Override protected void runTest()throws Throwable{
  Throwable failure=null;
  try{super.runTest();}catch(Throwable e){failure=e;throw e;}
  finally{if(out!=null){
   try{rowTags("final-row-tags.json");readback("final-editor-text.json");screenshot("final-observer");}
   catch(Throwable e){if(failure!=null)failure.addSuppressed(e);else throw e;}
  }}
 }
 @Override void awaitCandidateCount(android.widget.LinearLayout items,int count)throws Exception{
  AccessibilityNodeInfo input=await("test_input");String text=String.valueOf(input.getText());int left=Math.min(input.getTextSelectionStart(),input.getTextSelectionEnd()),right=Math.max(input.getTextSelectionStart(),input.getTextSelectionEnd());
  assertTrue("external editor owns a readable selected span",left>=0&&right>left&&right<=text.length());String selected=text.substring(left,right);
  long end=SystemClock.uptimeMillis()+5000;final boolean[] ready={false};
  while(SystemClock.uptimeMillis()<end){inst.runOnMainSync(()->{
   int matches=0;for(int n=0;n<items.getChildCount();n++){Object tag=items.getChildAt(n).getTag();if(isExternalCandidate(tag)&&selected.equals(((ZhuyinInputController.TextChoice)tag).witness))matches++;}ready[0]=matches>=count;
   for(int n=0;n<items.getChildCount()&&ready[0];n++){
    android.view.View item=items.getChildAt(n);Object tag=item.getTag();ready[0]=item.isEnabled()&&isExternalCandidate(tag)&&selected.equals(((ZhuyinInputController.TextChoice)tag).witness);
   }
  });if(ready[0])return;Thread.sleep(50);}
  fail("external selection candidates must appear within 5 seconds");
 }
 static String editorText(AccessibilityNodeInfo input){if(input.isPassword()){String actual=input.getExtras().getString("fixture_text");assertNotNull("password fixture must report actual text",actual);return actual;}return input.isShowingHintText()||input.getText()==null?"":input.getText().toString();}
 void text(String value)throws Exception{
  Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
  assertTrue(await("test_input").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));inst.waitForIdleSync();
  long deadline=SystemClock.uptimeMillis()+3000;while(!value.equals(editorText(await("test_input")))&&SystemClock.uptimeMillis()<deadline)Thread.sleep(20);
  assertEquals("fixture acknowledges exact text",value,editorText(await("test_input")));
 }
 void select(int start,int end)throws Exception{
  Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end);
  assertTrue(await("test_input").performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args));inst.waitForIdleSync();
 }
 int externalClicks;
 void readback(String name)throws Exception{
  AccessibilityNodeInfo input=await("test_input");save(name,new org.json.JSONObject().put("text",editorText(input)).put("showing_hint",input.isShowingHintText()).put("focused",input.isFocused()).put("start",input.getTextSelectionStart()).put("end",input.getTextSelectionEnd()).put("apk_sha256",androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("apk_sha256")).toString());
 }
 void clickCandidate(TextView candidate)throws Exception{
  String prefix="external-"+(++externalClicks);
  final Rect r=new Rect(),window=new Rect();inst.runOnMainSync(()->{
   int[] location=new int[2];candidate.getLocationOnScreen(location);
   assertTrue(candidate.getGlobalVisibleRect(window));
   assertTrue(candidate.getLocalVisibleRect(r));r.offset(location[0],location[1]);
  });
  save(prefix+"-tap-coordinates.json",new org.json.JSONObject().put("label",candidate.getText()).put("window_rect",window.toShortString()).put("screen_rect",r.toShortString()).toString());
  screenshot(prefix+"-before-screen-tap");readback(prefix+"-before-text.json");tap(r);inst.waitForIdleSync();screenshot(prefix+"-after-screen-tap");readback(prefix+"-after-text.json");
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
  assertNoExternalCandidates();
  assertEquals("old result cannot rewrite new editor text","完全不同內容",String.valueOf(await("test_input").getText()));
  text("前文時間後文");select(2,4);awaitCandidateCount(words,1);awaitCandidateCount(characters,1);
 }
 public void testUnknownSelectionLeavesEditorUnchanged()throws Exception{
  begin();text("前文🙂後文");select(2,4);Thread.sleep(400);inst.waitForIdleSync();
  assertNoExternalCandidates();
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
   assertNoExternalCandidates();
  }finally{
   save("password-fixture-restore.txt",shell("am start -S -W -n com.ime.sandbox.testpad/.MainActivity"));
  }
 }
}
