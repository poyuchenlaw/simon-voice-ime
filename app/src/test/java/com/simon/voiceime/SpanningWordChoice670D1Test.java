package com.simon.voiceime;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Controller/engine contract: word windows may span a tapped boundary. */
public class SpanningWordChoice670D1Test {
 static final class NativeMenu implements ZhuyinInputController.Engine {
  boolean menu;String text="抬光電";
  public String sentenceKeys(){return "ㄊㄞˊㄍㄨㄤㄉㄧㄢˋ";}
  public String composingText(){return text;}
  public List<String> phoneticSyllables(){return Arrays.asList("ㄊㄞˊ","ㄍㄨㄤ","ㄉㄧㄢˋ");}
  public boolean prepareSentence(String k,String t){text=t;return true;}
  public boolean moveCursorToPreviewCharacter(int i){menu=false;return true;}
  public int[] previewEditRange(){return new int[]{0,1};}
  public boolean regroup(int boundary){menu=true;return true;}
  public List<String> regroupLabels(){return menu?Arrays.asList("臺光","光電"):Arrays.asList("臺");}
  public List<String> optionGroups(){return Arrays.asList("char");}
  public List<int[]> optionRanges(){return menu?Arrays.asList(new int[]{0,2},new int[]{1,3}):Arrays.asList(new int[]{0,1});}
  public boolean chooseRegroup(int i){if(!menu||i!=0)return false;text="臺光電";return true;}
  public void key(String s){}public void space(){}public void enter(){}public void backspace(){}public void choose(int i){}public void moveCursor(String s){}public void clear(){}
  public int cursorPosition(){return 0;}public List<String> candidates(){return Collections.emptyList();}public String takeCommit(){return "";}
 }
 private ZhuyinInputController controller(int boundary){ZhuyinInputController c=new ZhuyinInputController(new NativeMenu());c.setRetypeEngineFactory(NativeMenu::new);c.moveCursorToPreviewBoundary(boundary);return c;}
 @Test public void middleBoundaryOffersWholeWordAndKeepsFullKeys(){
  ZhuyinInputController c=controller(1);ZhuyinInputController.TextChoice selected=null;
  for(var x:c.textChoices())if(x.label.equals("臺光"))selected=x;
  assertNotNull("word spanning middle caret",selected);assertEquals(0,selected.start);assertEquals(2,selected.end);assertTrue(selected.wordFocus);
  assertTrue(c.chooseTextCandidate(selected).accepted);assertEquals("臺光電",c.textPreview());assertEquals("ㄊㄞˊㄍㄨㄤㄉㄧㄢˋ",c.sentenceKeys());
 }
 @Test public void wordBeyondTappedCharacterIsExcluded(){
  for(var x:controller(1).textChoices())assertNotEquals("光電",x.label);
 }
 @Test public void tailBoundaryRetainsWholeWordMode(){
  ZhuyinInputController.TextChoice selected=null;for(var x:controller(2).textChoices())if(x.label.equals("臺光"))selected=x;
  assertNotNull(selected);assertTrue(selected.wordFocus);assertFalse(selected.characterFocus);
 }
}
