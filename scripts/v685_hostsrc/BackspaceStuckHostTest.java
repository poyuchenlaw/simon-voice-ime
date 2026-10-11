package com.simon.voiceime;
import java.util.*;
public class BackspaceStuckHostTest {
 static void keys(ZhuyinInputController c,String s){for(char k:s.toCharArray())if(k=='，')c.punctuation("，");else c.press(k==' '?"space":String.valueOf(k));}
 public static void main(String[] a)throws Exception {
  int failures=0;
  for(String keys:new String[]{"ㄐㄧㄣ ㄊㄧㄢ ㄎㄞ ㄏㄨㄟˋㄨㄛˇㄘㄜˋ", "ㄐㄧㄣ ㄊㄧㄢ ，ㄎㄞ ㄏㄨㄟˋㄨㄛˇㄘㄜˋ", "ㄐㄧㄣ ㄊㄧㄢ ㄅㄧㄢㄓㄕˋ", "ㄐㄧㄣ ㄊㄧㄢ ，ㄍㄜ"}){
   try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
    ZhuyinInputController c=new ZhuyinInputController(e);c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
    keys(c,keys);
    for(int n=0;n<64;n++){
     String before=c.textPreview(),beforeKeys=c.sentenceKeys();if(before.isEmpty())break;
     ZhuyinInputController.State after=c.press("backspace");String text=c.textPreview();
     System.out.println("CASE="+keys+" delete="+n+" before="+before+" after="+text+" accepted="+after.accepted+" boundary="+c.previewBoundary()+" keycaret="+c.keyCaret());
     if(text.equals(before)&&c.sentenceKeys().equals(beforeKeys)){
      System.out.println("STUCK after="+n+" keys="+c.sentenceKeys());
      for(int tap=0;tap<5;tap++)if(!c.press("backspace").composingText.equals(after.composingText))throw new AssertionError("unstable stuck replay");
      c.moveCursorToPreviewBoundary(2);System.out.println("PREVIEW_TAP_RECOVERY "+c.press("backspace").composingText);
      failures++;break;}
    }
   }
  }
  String mixed="今天，A1",reading="ㄐㄧㄣ ㄊㄧㄢ ，A1";
  for(int boundary=0;boundary<=mixed.length();boundary++){
   try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
    ZhuyinInputController c=new ZhuyinInputController(e);c.setTextLayout(true);
    if(!e.prepareSentence(reading,mixed))throw new AssertionError("known mixed literal mapping");
    c.moveCursorToPreviewBoundary(boundary);
    String expected=boundary==0?mixed:mixed.substring(0,boundary-1)+mixed.substring(boundary);
    c.press("backspace");if(!expected.equals(c.textPreview()))throw new AssertionError("mixed boundary "+boundary);
   }
  }
  System.out.println("PASS six mixed punctuation/ASCII/digit boundaries; four tail scenarios");
  if(failures>0)throw new AssertionError("nonempty tail stalled in "+failures+" cases");
 }
}
