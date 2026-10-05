package com.simon.voiceime;
import android.graphics.Rect;
import android.widget.TextView;
import org.json.*;
/** Real keyboard taps and real cursor word selection on the installed APK. */
public class CompleteUntoned670D1AndroidTest extends TextRows670AndroidTest {
 final String sequence="ㄊㄞˊㄍㄨㄤㄉㄧㄢˋㄏㄢˋㄔㄨㄤˋㄧˋㄌㄜ";
 void input(String keys,JSONArray steps,boolean checkFull)throws Exception{
  for(int i=0;i<keys.length();i++){
   char k=keys.charAt(i);tap(k==' '?"空白":String.valueOf(k));
   final int at=i;
   inst.runOnMainSync(()->{try{
    ZhuyinInputController c=actualController();java.util.List<String> readings=c.phoneticSyllables();
    steps.put(new JSONObject().put("i",at).put("key",String.valueOf(k)).put("keys",c.sentenceKeys()).put("reading",new JSONArray(readings)).put("shown",row1.getText()));
    if(checkFull&&at>=5)assertTrue("full ㄍㄨㄤ remains after key "+at+": "+readings,readings.contains("ㄍㄨㄤ"));
   }catch(Exception e){throw new RuntimeException(e);}});
   save("d1-steps.json",steps.toString());
  }
 }
 void chooseTaiguang(int boundary)throws Exception{chooseTaiguang(boundary,boundary-2);}
 void chooseTaiguang(int boundary,int wordStart)throws Exception{
  String[] old={null},prefix={null};inst.runOnMainSync(()->{old[0]=actualController().sentenceKeys();String s=row1.getText().toString();prefix[0]=s.substring(0,s.offsetByCodePoints(0,wordStart));});
  tapBoundary(boundary);int selected=-1;
  final JSONObject[] diagnostic={null};inst.runOnMainSync(()->{try{JSONArray choices=new JSONArray();for(int i=0;i<words.getChildCount();i++)choices.put(((TextView)words.getChildAt(i)).getText());diagnostic[0]=new JSONObject().put("requested_boundary",boundary).put("actual_boundary",actualController().previewBoundary()).put("shown",row1.getText()).put("choices",choices);}catch(Exception e){throw new RuntimeException(e);}});save("d1-cursor-before.json",diagnostic[0].toString());
  for(int retry=0;retry<50;retry++){
   final int[] found={-1};inst.runOnMainSync(()->{for(int i=0;i<words.getChildCount();i++)if(((TextView)words.getChildAt(i)).getText().toString().equals("臺光")){found[0]=i;break;}});
   if(found[0]>=0){selected=found[0];break;}Thread.sleep(100);
  }
  assertTrue("臺光 is offered at cursor",selected>=0);
  final int[] rawSpan={-1,-1};inst.runOnMainSync(()->{ZhuyinInputController c=actualController();for(var x:c.textChoices())if(x.label.equals("臺光")&&x.start==wordStart&&x.end==wordStart+2){int at=0;for(int i=0;i<c.phoneticSyllables().size();i++){if(i==x.start)rawSpan[0]=at;at+=c.phoneticSyllables().get(i).length();if(i+1==x.end){rawSpan[1]=at;break;}}break;}assertTrue("native word span available",rawSpan[0]>=0&&rawSpan[1]>rawSpan[0]);assertEquals("whole physical 臺光 keys consumed","ㄊㄞˊㄍㄨㄤ",old[0].substring(rawSpan[0],rawSpan[1]));});
  revealIndex(words,wordScroll,selected);
  TextView choice=(TextView)words.getChildAt(selected);Rect bounds=new Rect();
  inst.runOnMainSync(()->{int[] at=new int[2];choice.getLocationOnScreen(at);bounds.set(at[0],at[1],at[0]+choice.getWidth(),at[1]+choice.getHeight());});tap(bounds);Thread.sleep(300);
  final JSONObject[] receipt={null};inst.runOnMainSync(()->{try{
   ZhuyinInputController c=actualController();assertEquals("all physical keys retained",old[0],c.sentenceKeys());
   assertTrue("whole ㄍㄨㄤ consumed",c.phoneticSyllables().contains("ㄍㄨㄤ"));assertTrue("selected word in place",row1.getText().toString().startsWith(prefix[0]+"臺光"));
   assertFalse("no residual 網 after 臺光",row1.getText().toString().startsWith(prefix[0]+"臺光網"));
   receipt[0]=new JSONObject().put("boundary",boundary).put("glyph_start",wordStart).put("glyph_end",wordStart+2).put("key_before",old[0]).put("key_after",c.sentenceKeys()).put("readings",new JSONArray(c.phoneticSyllables())).put("shown",row1.getText().toString()).put("selected","臺光").put("full_span_consumed",true).put("physical_key_start",rawSpan[0]).put("physical_key_end",rawSpan[1]).put("consumed_physical_keys",old[0].substring(rawSpan[0],rawSpan[1]));
  }catch(Exception e){throw new RuntimeException(e);}});save("d1-cursor.json",receipt[0].toString());
 }
 void finishCommit()throws Exception{String s=shown();tap("↵");String committed=String.valueOf(await("test_input").getText());assertEquals("commit matches preview",s,committed);for(int cp:committed.codePoints().toArray())assertFalse("commit excludes zhuyin",cp>=0x3105&&cp<=0x312f||"ˉˊˇˋ˙".indexOf(cp)>=0);save("d1-commit.json",new JSONObject().put("shown",s).put("committed",committed).put("zero_zhuyin",true).toString());}
 public void testShortIncrementalAndCursor()throws Exception{begin();JSONArray steps=new JSONArray();input(sequence,steps,true);chooseTaiguang(2);finishCommit();}
 public void testLongIncrementalAndCursor()throws Exception{begin();JSONArray steps=new JSONArray();input("ㄇㄞˋㄔㄨ ㄉㄜ˙ㄧ ㄆㄛ ",steps,false);input(sequence,steps,true);chooseTaiguang(7);finishCommit();}
 public void testShortMiddleCursor()throws Exception{begin();JSONArray steps=new JSONArray();input(sequence,steps,true);chooseTaiguang(1,0);finishCommit();}
 public void testLongMiddleCursor()throws Exception{begin();JSONArray steps=new JSONArray();input("ㄇㄞˋㄔㄨ ㄉㄜ˙ㄧ ㄆㄛ ",steps,false);input(sequence,steps,true);chooseTaiguang(6,5);finishCommit();}
}
