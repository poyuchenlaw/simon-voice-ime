package com.simon.voiceime;
import org.json.JSONObject;
/** Fresh-install preferences with a real loopback proposal and physical keys. */
public class DefaultShadow670AndroidTest extends ProtectedRouteAndroidTest {
 @Override void ready()throws Exception {
  super.ready();
  inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().remove("ai_sentence_mode").remove("auto_correction").remove("ai_sentence_auto_apply").commit();
 }
 public void testDefaultInstallNeverAppliesAi()throws Exception {
  assertRejected("default_unregistered_name","ㄨㄤˊㄒㄧㄠˇㄇㄧㄥˊ","");
  assertEquals("fresh install sentence mode", "shadow",new JSONObject(diagnostic()).getString("mode"));
  assertFalse(inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).contains("ai_sentence_mode"));
  assertFalse("automatic mutation disabled for every proposed text",inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).getBoolean("auto_correction",false));
  tap("↵");assertEquals("Enter sends original shown text",String.valueOf(await("test_input").getText()),target.contains("小明")?target.replace("小明","曉明"):target.replace("曉明","小明"));
  shell("am start -W -n com.simon.voiceime/.SettingsActivity");
  String note="AI 整句改正尚在測試，預設只背景比對，不自動改字。顯示選項須點選才套用，可立即復原。";
  for(int i=0;i<12;i++){android.view.accessibility.AccessibilityNodeInfo n=node(note);if(n!=null&&n.isVisibleToUser())break;shell("input swipe 500 1800 500 400 250");Thread.sleep(150);}
  await(note);
  save("default-settings-result.json",new JSONObject().put("disclosure",note).put("default","shadow").put("preview_editor_enter_unchanged",true).toString());
  assertEquals("settings selects background by default","shadow",inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).getString("ai_sentence_mode","missing"));
 }
}
