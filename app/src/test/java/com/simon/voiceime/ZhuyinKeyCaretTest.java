package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class ZhuyinKeyCaretTest {
 static class Engine implements ZhuyinInputController.Engine {
  StringBuilder keys=new StringBuilder();int at;int chooseCount;int regroupCount;
  public boolean keyCaret(int p){if(p<0||p>keys.length())return false;at=p;return true;}
  public void key(String k){keys.insert(at++,k);}public void backspace(){if(at>0)keys.deleteCharAt(--at);}
  public int cursorPosition(){return at;}public int keyPreviewCaret(){return at;}
  public String sentenceKeys(){return keys.toString();}public String composingText(){return keys.toString();}
  public List<String> candidates(){return Arrays.asList("甲","乙");}public String takeCommit(){return "";}
  public void enter(){keys.setLength(0);at=0;}public void space(){}public void choose(int i){chooseCount++;}
  public void moveCursor(String d){}public void moveCursorToEnd(){at=keys.length();}public void clear(){keys.setLength(0);at=0;}
  public boolean moveCursorToPreviewCharacter(int p){return p>=0&&p<keys.length();}
  public List<String> regroupLabels(){return Arrays.asList("甲","乙");}
  public List<String> optionKinds(){return Arrays.asList("homophone","regroup");}
  public boolean chooseRegroup(int i){regroupCount++;return i>=0&&i<2;}
 }
 @Test public void keyEditUsesNativePositionAndRetainsSuffix(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);c.press("ㄇ");c.press("ㄚ");c.press("ㄌ");
  assertTrue(c.moveCursorToKey(2).accepted);c.press("backspace");assertEquals("ㄇㄌ",c.sentenceKeys());assertEquals(1,c.keyCaret());
  c.press("ㄚ");assertEquals("ㄇㄚㄌ",c.sentenceKeys());assertEquals(2,c.keyCaret());
  c.moveCursorToKey(0);c.press("backspace");assertEquals("ㄇㄚㄌ",c.sentenceKeys());assertEquals(0,c.keyCaret());
 }
 @Test public void endCommitClearsKeyFocus(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);c.press("ㄇ");c.moveCursorToKey(0);c.press("enter");assertEquals(-1,c.keyCaret());assertEquals("",c.sentenceKeys());
 }
 @Test public void wordChoicesRouteToSpanSelection(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);c.press("ㄇ");assertTrue(c.moveCursorToPreviewCharacter(0).accepted);
  assertEquals("homophone",c.candidateOrigin(0));c.chooseCandidate(0);assertEquals(1,e.regroupCount);assertEquals(0,e.chooseCount);assertFalse(c.wordFocused());
 }
}
