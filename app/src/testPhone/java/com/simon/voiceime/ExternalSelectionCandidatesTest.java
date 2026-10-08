package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class ExternalSelectionCandidatesTest {
 @Test public void committedWordOffersBothRowsWithoutChangingPreview() {
  ZhuyinWordIndex index=ZhuyinWordIndex.forTesting(
   new ZhuyinWordIndex.Entry("ㄕㄐ","時間","ㄕˊ ㄐㄧㄢ",100,false),
   new ZhuyinWordIndex.Entry("ㄕㄐ","實踐","ㄕˊ ㄐㄧㄢˋ",90,false),
   new ZhuyinWordIndex.Entry("ㄕ","時","ㄕˊ",100,false),
   new ZhuyinWordIndex.Entry("ㄕ","實","ㄕˊ",90,false));
  List<ZhuyinWordIndex.Entry> choices=index.selectionCandidates("時間");
  assertTrue(choices.stream().anyMatch(e->e.word.equals("實踐")));
  assertTrue(choices.stream().anyMatch(e->e.word.equals("實")));
  assertTrue(index.selectionCandidates("未知詞").isEmpty());
  ZhuyinInputController.Engine engine=new ZhuyinInputController.Engine(){
   public void key(String key){} public void backspace(){} public void space(){} public void enter(){} public void choose(int index){} public void moveCursor(String direction){} public int cursorPosition(){return 0;}
   public String composingText(){return "既有預覽";} public List<String> candidates(){return Collections.emptyList();} public String takeCommit(){return "";} public void clear(){} public String sentenceKeys(){return "ㄐㄧˋ";}
  };
  ZhuyinInputController controller=new ZhuyinInputController(engine,index);
  List<ZhuyinInputController.TextChoice> menu=controller.externalTextChoices("時間");
  assertTrue(menu.stream().anyMatch(c->c.kind.equals("word")&&c.label.equals("實踐")));
  assertTrue(menu.stream().anyMatch(c->c.kind.equals("char")&&c.label.equals("實")));
  assertEquals("既有預覽",controller.previewText());assertEquals("ㄐㄧˋ",controller.sentenceKeys());

 }
}
