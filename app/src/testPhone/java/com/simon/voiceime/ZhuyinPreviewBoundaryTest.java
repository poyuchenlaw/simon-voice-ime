package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ZhuyinPreviewBoundaryTest {
 static class Engine extends ZhuyinSecondPassTest.Engine {
  int boundary=-1,chosen=-1;boolean ended;
  List<String> readings=new ArrayList<>(Arrays.asList("ㄐㄧㄚˇ","ㄧˇ","ㄅㄧㄥˇ"));
  public List<String> phoneticSyllables(){return readings;}
  public boolean prepareSentence(String keys,String value){text=value;readings=value.equals("甲丙")?new ArrayList<>(Arrays.asList("ㄐㄧㄚˇ","ㄅㄧㄥˇ")):readings;return true;}
  Engine(){super(false);text="甲乙丙";range=new int[]{1,2};}
  public boolean regroup(int b){if(b<0||b>3)return false;boundary=b;return true;}
  public List<String> regroupLabels(){return Arrays.asList("甲乙｜丙","甲｜乙丙");}
  public boolean chooseRegroup(int i){if(i<0||i>1)return false;chosen=i;return true;}
  public void moveCursorToEnd(){ended=true;boundary=-1;}
 }
 @Test public void boundaryCandidatesUseTheirOwnIndexAndReturnToEnd(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);
  assertEquals("regroup",c.moveCursorToPreviewBoundary(2).candidateKind);
  assertEquals(Arrays.asList("甲乙｜丙","甲｜乙丙"),c.state().candidates);
  assertEquals("甲乙丙",c.chooseCandidate(1).composingText);assertEquals(1,e.chosen);assertEquals(-1,c.previewBoundary());
 }
 @Test public void boundaryBackspaceDeletesOneCharacterAndItsReading(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);c.setRetypeEngineFactory(()->new ZhuyinSecondPassTest.Engine(true));
  c.moveCursorToPreviewBoundary(2);assertEquals("甲丙",c.press("backspace").composingText);
  assertEquals(Arrays.asList("ㄐㄧㄚˇ","ㄅㄧㄥˇ"),c.phoneticSyllables());assertEquals(1,c.previewBoundary());
  assertEquals("甲丙",c.cancelSecondPass().composingText);assertTrue(e.ended);assertEquals(-1,c.previewBoundary());
 }
 @Test public void zeroBoundaryDoesNotDeletePreviousSyllable(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);c.setRetypeEngineFactory(()->new ZhuyinSecondPassTest.Engine(true));
  c.moveCursorToPreviewBoundary(0);c.press("backspace");assertFalse(c.isSecondPassActive());
 }
 @Test public void clearDiscardsBoundaryAndReturnsNoRegroupLabels(){
  Engine e=new Engine();ZhuyinInputController c=new ZhuyinInputController(e);c.moveCursorToPreviewBoundary(1);c.clear();assertEquals(-1,c.previewBoundary());
 }
 @Test public void sentenceCadenceRequiresCompleteEngineMapping(){
  Engine e=new Engine(){public String sentenceKeys(){return String.join("",readings).replace('ˉ',' ');}};
  ZhuyinInputController c=new ZhuyinInputController(e);assertTrue(c.sentenceBoundary());
  e.readings.set(2,"ㄅㄧㄥ");assertFalse(c.sentenceBoundary());
  e.readings.set(2,"ㄅㄧㄥˇ");e.readings.remove(2);assertFalse(c.sentenceBoundary());
 }

}
