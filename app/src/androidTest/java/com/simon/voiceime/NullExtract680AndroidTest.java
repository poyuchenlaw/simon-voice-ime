package com.simon.voiceime;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import org.json.JSONObject;
public class NullExtract680AndroidTest extends TapCursor677AndroidTest {
 @Override void readback(String name)throws Exception{
  AccessibilityNodeInfo input=await("null_extract_input");
  save(name,new JSONObject().put("text",editorText(input)).put("start",input.getTextSelectionStart()).put("end",input.getTextSelectionEnd()).put("extracted_requests",Integer.parseInt(await("null_extract_state").getText().toString())).toString());
 }
 public void testNullExtractTapReplacement()throws Exception{
  java.util.List<String> notices=new java.util.concurrent.CopyOnWriteArrayList<>();
  ui=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation();
  ui.setOnAccessibilityEventListener(e->{if(e.getEventType()==AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)notices.add(e.getText().toString());});
  try{
   begin();shell("am start -S -W -n com.ime.sandbox.testpad/.NullExtractActivity");tap("null_extract_input");await("ㄗ");bind();awaitStableWindow();
   screenshot("null-extract-keyboard-ready");readback("null-extract-before-text.json");
   AccessibilityNodeInfo input=await("null_extract_input");RectF glyph=glyphAt(input.getExtras().getParcelableArray("fixture_glyphs"),8);
   int x=Math.round(glyph.right-1),y=Math.round(glyph.centerY());
   save("null-extract-caret-tap.json",new JSONObject().put("offset",9).put("source","fixture-Layout").put("glyph",glyph.toString()).put("x",x).put("y",y).toString());
   tap(new Rect(x,y,x+1,y+1));inst.waitForIdleSync();Thread.sleep(600);bind();
   readback("null-extract-caret-text.json");
   boolean baseline="6.79".equals(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("version"));
   if(baseline){assertNoExternalCandidates();screenshot("null-extract-baseline");return;}
   long deadline=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<deadline&&externalCount(characters)==0)Thread.sleep(40);
   rowTags("null-extract-candidate-tags.json");readback("null-extract-candidate-text.json");
   assertTrue("getExtractedText called and always returned null",Integer.parseInt(await("null_extract_state").getText().toString())>0);
   assertTrue("external character candidates appear",externalCount(characters)>0);
   TextView candidate=(TextView)characters.getChildAt(0);String chosen=candidate.getText().toString();
   save("null-extract-first-candidate.json",new JSONObject().put("candidate",chosen).put("row","characters").toString());
   clickCandidate(candidate);Thread.sleep(300);inst.waitForIdleSync();readback("null-extract-replaced-text.json");screenshot("null-extract-replaced");
   assertEquals("我們明天十點在法"+chosen+"見面",editorText(await("null_extract_input")));
   assertTrue("no notification throughout test",notices.isEmpty());
  }finally{save("null-extract-notices.json",new org.json.JSONArray(notices).toString());ui.setOnAccessibilityEventListener(null);}
 }
}
