package com.simon.voiceime;
import org.json.*;import android.content.SharedPreferences;
/** Public preference migration, actual physical keys and visible suggestion row. */
public class Mode671AndroidTest extends LivePause670AndroidTest {
 @Override void ready()throws Exception{super.ready();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().remove("ai_sentence_mode").putBoolean("auto_correction",false).putBoolean("ai_sentence_auto_apply",false).commit();}
 public void testSuggestionsAreVisible()throws Exception{
  String target="多餘";begin();typeWrong();String before=shown();Thread.sleep(2300);
  JSONObject d=new JSONObject(diagnostic());save("mode-result.json",d.put("preview",before).put("target",target).toString());
  assertTrue("real loopback request sent",requestCount>0);assertEquals("fresh default mode","suggestions",d.getString("mode"));assertEquals("suggestions never replace preview",before,shown());assertFalse(highlighted());assertFalse("fixture changes preview",before.equals(target));
  final boolean[] visible={false};inst.runOnMainSync(()->{for(int i=0;i<words.getChildCount();i++){android.view.View v=words.getChildAt(i);if(v instanceof android.widget.TextView && ((android.widget.TextView)v).getText().toString().startsWith("AI · "))visible[0]=v.isShown();}});
  assertTrue("AI proposal actually displayed in word row",visible[0]);
  java.util.concurrent.CountDownLatch frame=new java.util.concurrent.CountDownLatch(1);
  inst.runOnMainSync(()->{root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){root.getViewTreeObserver().removeOnPreDrawListener(this);root.getViewTreeObserver().registerFrameCommitCallback(frame::countDown);return true;}});root.invalidate();});
  assertTrue("suggestion frame committed",frame.await(4,java.util.concurrent.TimeUnit.SECONDS));
  JSONObject after=new JSONObject(diagnostic());save("mode-after-frame.json",after.toString());assertTrue("shown telemetry follows actual word-row draw",after.getLong("phoneRender")>=0);tap("↵");assertEquals("Enter keeps original",before,String.valueOf(await("test_input").getText()));
 }
 public void testShadowMigrationAndIdempotence()throws Exception{
  ready();SharedPreferences p=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);p.edit().putString("ai_sentence_mode","shadow").putInt("ai_auto_apply_migration_version",670).putBoolean("ai_sentence_auto_apply",true).commit();
  AiSentencePhone.migrateAutoApply(inst.getTargetContext());assertEquals("stored shadow upgrades once","suggestions",p.getString("ai_sentence_mode","missing"));assertFalse(p.getBoolean("ai_sentence_auto_apply",true));
  p.edit().putString("ai_sentence_mode","shadow").commit();AiSentencePhone.migrateAutoApply(inst.getTargetContext());assertEquals("manual shadow survives later launch","shadow",p.getString("ai_sentence_mode","missing"));
  out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();save("migration-result.json",new JSONObject().put("shadow_to_suggestions",true).put("idempotent",true).toString());
 }
 public void testLiveSelectionIsPreserved()throws Exception{
  ready();SharedPreferences p=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);p.edit().putString("ai_sentence_mode","live").putInt("ai_auto_apply_migration_version",0).commit();AiSentencePhone.migrateAutoApply(inst.getTargetContext());assertEquals("live retained","live",p.getString("ai_sentence_mode","missing"));assertFalse("auto off on upgrade",p.getBoolean("ai_sentence_auto_apply",true));
  out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();save("live-selection.json",new JSONObject().put("live_preserved",true).put("auto_off",true).toString());
 }
}
