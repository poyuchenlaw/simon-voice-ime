package com.simon.voiceime;
import android.widget.TextView;
import android.os.SystemClock;
import org.json.JSONObject;
public class Cursor679AndroidTest extends TapCursor677AndroidTest {
 public void testReportedZhuyinWordTap()throws Exception {
  begin();assertEquals(1.3f,words.getResources().getConfiguration().fontScale,.01f);assertEquals(420,words.getResources().getDisplayMetrics().densityDpi);
  text("註音");tapCaret(1);awaitTapRows(1);screenshot("zhuyin-cursor-before");rowTags("zhuyin-cursor-tags.json");
  TextView item=firstWordCandidate();String expected=item.getText().toString();clickCandidate(item);
  assertEquals("word replaces whole target",expected,String.valueOf(await("test_input").getText()));readback("zhuyin-word-replaced-text.json");screenshot("zhuyin-word-after");
 }
 public void testReportedZhuyinCharacterTap()throws Exception {
  begin();text("註音");tapCaret(1);awaitTapRows(1);TextView item=(TextView)characters.getChildAt(0);String expected=item.getText().toString()+"音";clickCandidate(item);
  assertEquals(expected,String.valueOf(await("test_input").getText()));readback("zhuyin-char-replaced-text.json");screenshot("zhuyin-char-after");
 }
 public void testStorageDiagAndSettingsDetail()throws Exception {
  begin();long deadline=SystemClock.uptimeMillis()+20000;java.io.File spool=new java.io.File(words.getContext().getFilesDir(),"ime-diagnostics.jsonl");String data="";
  while(SystemClock.uptimeMillis()<deadline){if(spool.exists())data=new String(java.nio.file.Files.readAllBytes(spool.toPath()),java.nio.charset.StandardCharsets.UTF_8);if(data.contains("storage_diag"))break;Thread.sleep(100);}
  assertTrue("startup storage_diag reaches actual telemetry spool",data.contains("storage_diag"));
  long total=0;for(String line:data.split("\n")){if(line.isEmpty())continue;JSONObject e=new JSONObject(line);if("storage_diag".equals(e.optString("type")))total=e.getLong("total_bytes");}
  assertTrue("Rime assets are included after initialization",total>1000000);

  save("storage-spool-readback.json",data);readback("storage-editor-text.json");
  android.content.Intent intent=new android.content.Intent(words.getContext(),SettingsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);words.getContext().startActivity(intent);Thread.sleep(1500);
  android.view.accessibility.AccessibilityNodeInfo root=ui.getRootInActiveWindow();java.util.List<android.view.accessibility.AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText("空間明細");assertFalse(nodes.isEmpty());assertTrue(nodes.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(1500);screenshot("storage-detail");
  final org.json.JSONArray layout=new org.json.JSONArray();inst.runOnMainSync(()->{try{Class<?> cls=Class.forName("android.view.WindowManagerGlobal");Object wm=cls.getDeclaredMethod("getInstance").invoke(null);java.lang.reflect.Field views=cls.getDeclaredField("mViews");views.setAccessible(true);for(Object view:(java.util.List<?>)views.get(wm))storageLabels((android.view.View)view,layout);}catch(Exception e){throw new RuntimeException(e);}});
  assertTrue("storage labels measured",layout.length()>=10);save("storage-label-layout.json",layout.toString());clickText("關閉");returnToEditor();

 }

 void storageLabels(android.view.View view,org.json.JSONArray results)throws Exception{
  if(view instanceof TextView){TextView v=(TextView)view;String label=v.getText().toString();if(label.contains(" MB")&&v.getLayout()!=null){int content=v.getHeight()-v.getTotalPaddingTop()-v.getTotalPaddingBottom();int required=v.getLayout().getHeight();assertTrue("storage label untruncated: "+label,content>=required);results.put(new JSONObject().put("label",label).put("content_height",content).put("required_height",required));}}
  if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int n=0;n<g.getChildCount();n++)storageLabels(g.getChildAt(n),results);}
 }
 void clickText(String label)throws Exception{
  for(int n=0;n<12;n++){
   android.view.accessibility.AccessibilityNodeInfo v=node(label);
   if(v!=null&&v.isVisibleToUser()){assertTrue(v.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(600);return;}
   ui.executeShellCommand("input swipe 700 1750 700 550 300").close();Thread.sleep(400);
  }
  fail("UI action unavailable: "+label);
 }
 public void testExportWavComplete()throws Exception{
  begin();android.content.Context c=inst.getTargetContext();VoicePendingQueue q=VoicePendingQueue.getInstance(c.getFilesDir(),16000);
  byte[] original=new byte[2455680];for(int n=0;n<original.length;n++)original[n]=(byte)(n*31+7);
  String id=q.runIO(()->{String created=q.begin();q.append(created,original,original.length);q.markPending(created,"fixture");q.uploadOne(created,(pcm,session,rate)->{throw new java.io.IOException("audio custody conflict");},(text,started)->fail("no transcript delivery on conflict"),null);return created;});
  assertTrue(q.needsAttention(id));assertFalse(q.pendingOldestFirst().contains(id));
  c.startActivity(new android.content.Intent(c,SettingsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
  clickText("匯出需處理的保留錄音");clickText("錄音 1（76 秒）");
  long end=SystemClock.uptimeMillis()+10000;android.view.accessibility.AccessibilityNodeInfo save=null;
  while(SystemClock.uptimeMillis()<end){for(String s:new String[]{"SAVE","Save","儲存"}){save=node(s);if(save!=null&&save.isVisibleToUser())break;}if(save!=null&&save.isVisibleToUser())break;Thread.sleep(100);}
  assertNotNull("Android create-document SAVE UI",save);assertTrue(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(2500);
  String path=shell("find /storage/emulated/0 -type f -name '*.wav' 2>/dev/null").trim();assertFalse("picker creates WAV",path.isEmpty());String[] paths=path.split("\n");byte[] wav=null;
  for(String candidate:paths){byte[] bytes=android.util.Base64.decode(shell("base64 "+candidate),android.util.Base64.DEFAULT);if(bytes.length==original.length+44)wav=bytes;}
  assertNotNull("full WAV exists",wav);assertEquals(original.length+44,wav.length);
  assertEquals("RIFF",new String(wav,0,4,java.nio.charset.StandardCharsets.US_ASCII));assertEquals("WAVE",new String(wav,8,4,java.nio.charset.StandardCharsets.US_ASCII));
  java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN);assertEquals(original.length+36,header.getInt(4));assertEquals(original.length,header.getInt(40));assertEquals(16000,header.getInt(24));
  assertTrue("WAV payload byte-for-byte",java.util.Arrays.equals(original,java.util.Arrays.copyOfRange(wav,44,wav.length)));
  assertTrue("original PCM byte-for-byte",java.util.Arrays.equals(original,java.nio.file.Files.readAllBytes(q.pcmFile(id).toPath())));assertTrue(q.needsAttention(id));
  save("export-byte-comparison.json",new JSONObject().put("pcm_bytes",original.length).put("wav_bytes",wav.length).put("payload_equal",true).put("original_retained",true).put("needs_attention",true).put("path",path).toString());
  java.nio.file.Files.write(new java.io.File(out,"exported.wav").toPath(),wav);java.nio.file.Files.write(new java.io.File(out,"original.pcm").toPath(),original);save("export-result-text.json",new JSONObject().put("payload_equal",true).put("original_retained",true).toString());screenshot("export-wav-after");returnToEditor();
 }
 public void testCandidateRouteDiagnostics()throws Exception{
  begin();typeToday();Thread.sleep(500);inst.waitForIdleSync();bind();TextView item=(TextView)characters.getChildAt(0);clickCandidate(item);
  // Exercise native preview DOWN/UP and word-row DOWN/CANCEL, preserving normal dispatch.
  android.graphics.Rect r=new android.graphics.Rect();inst.runOnMainSync(()->screenVisibleRect(row1,r));tap(r);
  inst.runOnMainSync(()->screenVisibleRect(words.getChildAt(0),r));long at=SystemClock.uptimeMillis();
  android.view.MotionEvent d=android.view.MotionEvent.obtain(at,at,0,r.centerX(),r.centerY(),0),cancel=android.view.MotionEvent.obtain(at,at+20,3,r.centerX(),r.centerY(),0);assertTrue(ui.injectInputEvent(d,true));assertTrue(ui.injectInputEvent(cancel,true));d.recycle();cancel.recycle();Thread.sleep(500);
  java.io.File spool=new java.io.File(canonicalFiles(),"ime-diagnostics.jsonl");int count=0;java.util.Set<Integer> rows=new java.util.HashSet<>();boolean click=false,cancelled=false;
  for(String line:java.nio.file.Files.readAllLines(spool.toPath())){JSONObject e=new JSONObject(line);if(!"candidate_route".equals(e.optString("type")))continue;count++;rows.add(e.getInt("row"));click|=e.optBoolean("click_listener");cancelled|=e.getString("phase").contains("cancel");
   for(String key:new String[]{"text","candidate","label","shown","package","reading"})assertFalse("no plaintext field "+key,e.has(key));
   assertTrue(e.has("x")&&e.has("y")&&e.has("route")&&e.has("receiver_class")&&e.has("receiver_id")&&e.has("consumed")&&e.has("preedit")&&e.has("cursor_index"));
  }
  assertTrue("all three visible rows observed",rows.containsAll(java.util.Arrays.asList(1,2,3)));assertTrue("actual click observed",click);assertTrue("cancel observed",cancelled);assertTrue(count>0&&count<=120);
  save("touch-route-text-free.json",new JSONObject().put("events",count).put("all_three_rows",true).put("click",click).put("cancel",cancelled).put("no_plaintext",true).toString());readback("diagnostic-editor-text.json");screenshot("touch-diagnostic-after");
 }
 void screenVisibleRect(android.view.View view,android.graphics.Rect rect){assertTrue(view.getLocalVisibleRect(rect));int[] at=new int[2];view.getLocationOnScreen(at);rect.offset(at[0],at[1]);}
 void returnToEditor()throws Exception{shell("am start -W -n com.ime.sandbox.testpad/.MainActivity");tap("test_input");inst.waitForIdleSync();bind();}
 java.io.File canonicalFiles(){return inst.getTargetContext().getFilesDir();}
}
