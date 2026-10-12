package com.simon.voiceime;
import java.util.*;import org.junit.Test;import static org.junit.Assert.*;
import com.simon.voiceime.App688Harness.KeyboardLabels;
import com.simon.voiceime.App688Harness.android;
public class App690AFitTest {
 @Test public void r4c_cacheThirtyReopensAndChangedWidth(){
  KeyboardLabels.Cache cache=new KeyboardLabels.Cache();Object face=new Object();android.graphics.Paint paint=new android.graphics.Paint();List<KeyboardLabels.Label> labels=List.of(new KeyboardLabels.Label(64,72,0,"ㄅ","ㄆ","ㄇ","ㄈ",""));
  float first=cache.fit(400,1,1,face,paint,labels);assertTrue(first>=12&&first<=24);
  for(int i=0;i<30;i++)assertEquals(first,cache.fit(400,1,1,face,paint,labels),0);assertEquals(1,cache.count());
  cache.fit(401,1,1,face,paint,labels);assertEquals(2,cache.count());cache.fit(401,1,1.3f,face,paint,labels);assertEquals(3,cache.count());cache.fit(401,1,1.3f,new Object(),paint,labels);assertEquals(4,cache.count());
 }
 @Test public void r4a_allSuppliedCharacterLabelsParticipate(){
  android.graphics.Paint paint=new android.graphics.Paint();List<KeyboardLabels.Label> shortOnly=List.of(new KeyboardLabels.Label(32,72,0,"ㄅ","","","",""));
  float first=KeyboardLabels.fit(paint,1,shortOnly);assertEquals(24,first,0);
  List<KeyboardLabels.Label> all=List.of(shortOnly.get(0),new KeyboardLabels.Label(32,72,0,"ABC","","","",""));assertTrue(KeyboardLabels.fit(paint,1,all)<first);
 }
 @Test public void a2_5_contextualClassificationAndCentralAbbreviations(){
  for(String punctuation:List.of("，","。","."))assertEquals("function",KeyboardLabels.labelClass(punctuation,false));
  for(String symbols:List.of("、","？","！","★"))assertEquals("character",KeyboardLabels.labelClass(symbols,true));
  for(String icon:List.of("⬆","␣","⌫","⚙","🎤","⏹"))assertEquals("icon",KeyboardLabels.labelClass(icon,false));
  assertEquals("41",KeyboardLabels.functionLabel("41鍵","tap",false));assertEquals("送",KeyboardLabels.functionLabel("送出","right",false));assertEquals("刪\n字",KeyboardLabels.functionLabel("刪字","left",false));assertEquals("數",KeyboardLabels.fallbackFunctionLabel("123"));assertEquals("123",KeyboardLabels.functionLabel("123","tap",false));
 }
}
