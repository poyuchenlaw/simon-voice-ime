package com.simon.voiceime;
import java.util.*;
public class TextCaretHostTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 public static void main(String[] a)throws Exception{
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
   ZhuyinInputController c=new ZhuyinInputController(e);c.setLearningEnabled(false);
   c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
   check(e.prepareSentence("ㄖㄢˊㄏㄡˋㄩㄥˋ","然後用"),"fixture");
   List<String> before=new ArrayList<>(c.phoneticSyllables());
   check(c.moveCursorToPreviewBoundary(2).accepted,"text tap");
   c.press("ㄅ");c.press("ㄨ");c.press("ˋ");
   check(c.previewText().equals("然後不用"),"insert preview "+c.previewText());
   check(c.previewBoundary()==3,"text caret follows inserted character: "+c.previewBoundary());
   c.chooseCandidate(c.state().candidates.indexOf("不"));
   check(c.previewText().equals("然後不用"),"insert selected "+c.previewText());
   List<String> after=c.phoneticSyllables();
   check(after.equals(Arrays.asList(before.get(0),before.get(1),"ㄅㄨˋ",before.get(2))),"unchanged readings "+after);
   c.moveCursorToPreviewBoundary(3);c.press("backspace");
   check(c.previewText().equals("然後用"),"one character delete "+c.previewText());
   check(c.phoneticSyllables().equals(before),"deleted reading "+c.phoneticSyllables());
   c.moveCursorToPreviewBoundary(0);c.press("backspace");check(c.previewText().equals("然後用"),"start delete");
   c.moveCursorToPreviewBoundary(2);c.press("ㄅ");c.press("ㄨ");c.press("ˋ");
   c.moveCursorToPreviewBoundary(3);
   check(c.previewText().equals("然後不用"),"text caret move retains new character "+c.previewText());
   c.press("backspace");check(c.previewText().equals("然後用"),"delete after moving");
   c.moveCursorToKey(2);c.press("backspace");check(!c.sentenceKeys().equals("ㄖㄢˊㄏㄡˋㄩㄥˋ"),"row 2 still edits reading");
   c.clear();check(e.prepareSentence("ㄖㄢˊㄏㄡˋㄩㄥˋ","然後用"),"second fixture");
   c.moveCursorToPreviewBoundary(2);c.press("ㄅ");c.press("backspace");c.press("backspace");
   check(c.previewText().equals("然用"),"empty insertion backspace deletes prior character "+c.previewText());
   c.clear();check(e.prepareSentence("ㄖㄢˊㄏㄡˋ，ㄩㄥˋ","然後，用"),"punctuation fixture");
   check(c.moveCursorToPreviewBoundary(1).accepted,"text caret before punctuation");
   c.press("backspace");check(c.previewText().equals("後，用"),"delete before punctuation "+c.previewText());
   check(c.phoneticSyllables().equals(Arrays.asList("ㄏㄡˋ","，","ㄩㄥˋ")),"punctuation readings "+c.phoneticSyllables());
   c.clear();check(e.prepareSentence("ㄖㄢˊㄏㄡˋㄩㄥˋ","然後用"),"multi fixture");c.moveCursorToPreviewBoundary(2);
   for(String glyph:Arrays.asList("ㄅ","ㄨ","ˋ","ㄕ","ˋ"))c.press(glyph);
   int prefix=c.state().candidates.indexOf("不");check(prefix>=0,"prefix candidate present "+c.state().candidates);
   c.chooseCandidate(prefix);check(!c.isSecondPassActive(),"prefix pick finalizes inserted fragment without dropping suffix");
   check(c.phoneticSyllables().equals(Arrays.asList("ㄖㄢˊ","ㄏㄡˋ","ㄅㄨˋ","ㄕˋ","ㄩㄥˋ")),"multi readings");
   System.out.println("PASS text insertion/delete/readings and row2 routing");
  }
 }
}
