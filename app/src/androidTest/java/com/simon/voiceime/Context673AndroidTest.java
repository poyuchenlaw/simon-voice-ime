package com.simon.voiceime;
import junit.framework.TestCase;import androidx.test.platform.app.InstrumentationRegistry;import android.app.UiAutomation;import android.os.*;import java.io.*;
/** Actual enabled accessibility service, synthetic fixture and real window switches. */
public class Context673AndroidTest extends TestCase {
 private UiAutomation ui;
 private String shell(String command)throws Exception{try(ParcelFileDescriptor descriptor=ui.executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();}}
 private String hint(){return LocalConversationContext.text("com.simon.voiceime.test",SystemClock.elapsedRealtime());}
 private void awaitHint(String expected)throws Exception{long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end&&!expected.equals(hint()))Thread.sleep(50);assertEquals(expected,hint());}
 public void testEnabledServiceIncomingDisablePasswordAndAppSwitch()throws Exception{
  ui=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  android.content.SharedPreferences p=InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("simon_ime_prefs",0);
  String services=shell("settings get secure enabled_accessibility_services"),enabled=shell("settings get secure accessibility_enabled");
  try{
   p.edit().putBoolean("local_screen_context",true).commit();shell("settings put secure enabled_accessibility_services com.simon.voiceime/.ConversationContextService");shell("settings put secure accessibility_enabled 1");Thread.sleep(1000);
   shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity --ez password false");awaitHint("請確認損害賠償");
   p.edit().putBoolean("local_screen_context",false).commit();awaitHint("");
   shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity");Thread.sleep(300);awaitHint("");
   p.edit().putBoolean("local_screen_context",true).commit();shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity --ez password true");Thread.sleep(600);awaitHint("");
   shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity --ez password false");awaitHint("請確認損害賠償");
   shell("settings delete secure enabled_accessibility_services");awaitHint("");
   shell("settings put secure enabled_accessibility_services com.simon.voiceime/.ConversationContextService");Thread.sleep(1000);
   shell("am start -W -f 0x10008000 -n com.simon.voiceime.test/com.simon.voiceime.ContextSandboxActivity");awaitHint("請確認損害賠償");
   shell("am start -W -a android.settings.SETTINGS");awaitHint("");
  }finally{
   p.edit().putBoolean("local_screen_context",false).commit();LocalConversationContext.clear();
   if("null".equals(services))shell("settings delete secure enabled_accessibility_services");else shell("settings put secure enabled_accessibility_services "+services);
   if("null".equals(enabled))shell("settings delete secure accessibility_enabled");else shell("settings put secure accessibility_enabled "+enabled);
  }
 }
}
