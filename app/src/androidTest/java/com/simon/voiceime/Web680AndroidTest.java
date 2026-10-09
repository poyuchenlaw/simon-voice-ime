package com.simon.voiceime;
import org.json.JSONObject;import android.graphics.Rect;import android.os.SystemClock;import android.widget.TextView;import android.view.accessibility.AccessibilityEvent;
public class Web680AndroidTest extends TapCursor677AndroidTest {
 @Override void readback(String name)throws Exception{if(node("web_state")!=null)save(name,webState().toString());else super.readback(name);}
 JSONObject webState()throws Exception{return new JSONObject(String.valueOf(await("web_state").getText()));}
 public void testWebTapReplacement()throws Exception{
  begin();java.util.List<String> notices=new java.util.concurrent.CopyOnWriteArrayList<>();ui.setOnAccessibilityEventListener(e->{if(e.getEventType()==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)notices.add(e.getText().toString());});
  shell("am start -W -n com.ime.sandbox.testpad/.WebActivity");await("web_view");
  long readyDeadline=SystemClock.uptimeMillis()+5000;JSONObject before;
  do{before=webState();if(before.optInt("start",-1)>=0&&before.optInt("end",-1)>=0)break;Thread.sleep(40);}while(SystemClock.uptimeMillis()<readyDeadline);
  save("web-initial-state.json",before.toString());assertTrue("DOM caret ready",before.optInt("start",-1)>=0&&before.optInt("end",-1)>=0);
  try{await("ㄗ");}finally{save("web-input-method.txt",shell("dumpsys input_method"));save("web-keyboard-window.txt",shell("dumpsys window windows"));screenshot("web-keyboard-check");}
  bind();awaitStableWindow();screenshot("web-keyboard-ready");before=webState();save("web-before-tap.json",before.toString());
  int x=before.getInt("sx"),y=before.getInt("sy");
  tap(new Rect(x,y,x+1,y+1));Thread.sleep(600);bind();JSONObject caret=webState();save("web-caret.json",caret.toString());
  assertEquals(9,caret.getInt("start"));assertEquals(9,caret.getInt("end"));
  boolean baseline="6.79".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("version"));
  if(baseline){assertNoExternalCandidates();screenshot("web-baseline");save("final-editor-text.json",caret.toString());save("web-notices.json",new org.json.JSONArray(notices).toString());return;}
  long deadline=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<deadline&&externalCount(characters)==0)Thread.sleep(40);
  rowTags("web-candidate-tags.json");assertTrue("fixture intercepted null extracted text",caret.getInt("extracted_requests")>0);assertTrue("WebView null extracted text gives candidates",externalCount(characters)>0);
  TextView candidate=(TextView)characters.getChildAt(0);String glyph=candidate.getText().toString();assertEquals(1,glyph.codePointCount(0,glyph.length()));save("web-first-candidate.json",new JSONObject().put("glyph",glyph).toString());clickCandidate(candidate);Thread.sleep(300);JSONObject after=webState();
  assertEquals("我們明天十點在法"+glyph+"見面",after.getString("text"));assertTrue("no Toast",notices.isEmpty());
  save("final-editor-text.json",after.toString());save("web-notices.json",new org.json.JSONArray(notices).toString());screenshot("web-replaced");
 }
}
