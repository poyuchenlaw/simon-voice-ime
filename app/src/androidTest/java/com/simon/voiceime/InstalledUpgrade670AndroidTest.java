package com.simon.voiceime;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.SharedPreferences;
public class InstalledUpgrade670AndroidTest extends UpgradeSafety670AndroidTest {
 @Override void preparePreferences(){inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("server_url","http://127.0.0.1:8181").putString("auth_password","sandbox").putBoolean("ime_auto_upload",false).putString("layout_mode","text_word_char").commit();}
 public void testInstalledUpgradeDisablesInheritedLive()throws Exception {
  inst=InstrumentationRegistry.getInstrumentation();SharedPreferences p=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);
  assertEquals("process startup migrated before opening service",670,p.getInt("ai_auto_apply_migration_version",0));assertFalse(p.getBoolean("auto_correction",true));assertFalse(p.getBoolean("ai_sentence_auto_apply",true));assertEquals("shadow",p.getString("ai_sentence_mode",""));
  upgrade=true;noChange("installed-upgrade");
 }
}
