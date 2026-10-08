package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
public class TapCursor677Test {
 private ZhuyinWordIndex index(){return ZhuyinWordIndex.forTesting(
  new ZhuyinWordIndex.Entry("ㄈㄩ","法院","ㄈㄚˇ ㄩㄢˋ",100,false),
  new ZhuyinWordIndex.Entry("ㄈㄩ","發言","ㄈㄚ ㄧㄢˊ",90,false),
  new ZhuyinWordIndex.Entry("ㄩ","院","ㄩㄢˋ",100,false),
  new ZhuyinWordIndex.Entry("ㄩ","願","ㄩㄢˋ",90,false),
  new ZhuyinWordIndex.Entry("ㄩ","遠","ㄩㄢˇ",80,false));}
 @Test public void tapWordsPreferExactTonesWithoutChangingSelection(){
  ZhuyinWordIndex words=ZhuyinWordIndex.forTesting(
   new ZhuyinWordIndex.Entry("ㄈㄩ","法院","ㄈㄚˇ ㄩㄢˋ",100,false),
   new ZhuyinWordIndex.Entry("ㄈㄩ","復於","ㄈㄨˋ ㄩˊ",999,false),
   new ZhuyinWordIndex.Entry("ㄈㄩ","法願","ㄈㄚˇ ㄩㄢˋ",1,false),
   new ZhuyinWordIndex.Entry("ㄈㄩ","法遠","ㄈㄚˇ ㄩㄢˇ",90,false));
  ZhuyinInputController c=new ZhuyinInputController(new EmptyEngine(),words);
  java.util.List<ZhuyinInputController.TextChoice> choices=c.cursorTextChoices("前文法院後文",4);
  assertEquals("法願",choices.get(0).label);
  assertEquals("復於",choices.get(1).label);
  assertFalse(choices.stream().anyMatch(x->x.label.equals("法院")));
  assertEquals(1,choices.stream().filter(x->x.label.equals("法願")).count());
  assertEquals("復於",c.externalTextChoices("法院").get(0).label);
 }
 @Test public void endingWordAndCharacterHaveSeparateTargets(){
  assertArrayEquals(new int[]{2,4},index().cursorWordRange("前文法院後文",4));
 }
 @Test public void containingFallbackAndUnicodeBoundaries(){
  assertArrayEquals(new int[]{2,4},index().cursorWordRange("前文法院後文",3));
  assertArrayEquals(new int[]{1,3},index().cursorWordRange("甲乙丙丁",3));
  assertNull(index().cursorWordRange("法院",0));
  assertArrayEquals(new int[]{0,2},index().cursorWordRange("法院",2));
  assertArrayEquals(new int[]{2,4},index().cursorWordRange("🙂法院🙂",4));
  assertArrayEquals(new int[]{0,3},index().cursorWordRange("🙂甲",3));
  assertNull(index().cursorWordRange("🙂甲",1));
  ZhuyinWordIndex longer=ZhuyinWordIndex.forTesting(
   new ZhuyinWordIndex.Entry("ㄈㄩ","法院","ㄈㄚˇ ㄩㄢˋ",100,false),
   new ZhuyinWordIndex.Entry("ㄈㄩㄐㄇ","法院見面","ㄈㄚˇ ㄩㄢˋ ㄐㄧㄢˋ ㄇㄧㄢˋ",80,false));
  assertArrayEquals(new int[]{2,6},longer.cursorWordRange("前文法院見面後文",4));
 }
 @Test public void cursorChoicesUseLastCharacterExactReading(){
  ZhuyinInputController c=new ZhuyinInputController(new EmptyEngine(),index());
  java.util.List<ZhuyinInputController.TextChoice> menu=c.cursorTextChoices("前文法院後文",4);
  assertTrue(menu.stream().anyMatch(x->x.kind.equals("word")&&x.label.equals("發言")&&x.start==2&&x.end==4));
  assertTrue(menu.stream().anyMatch(x->x.kind.equals("char")&&x.label.equals("願")&&x.start==3&&x.end==4));
  assertFalse(menu.stream().anyMatch(x->x.kind.equals("char")&&x.label.equals("遠")));
  assertEquals("",c.previewText());
 }
 @Test public void selfUpdatesCompositionProtectedAndNoMoveDoNotTrigger(){
  CursorTapGuard gate=new CursorTapGuard();
  gate.selfSelection(4,4);gate.selfSelection(5,5);
  assertFalse(gate.allow(3,3,4,4,false,false));
  assertFalse(gate.allow(4,4,5,5,false,false));
  assertTrue(gate.allow(5,5,4,4,false,false));
  assertFalse(gate.allow(4,4,3,3,true,false));
  assertFalse(gate.allow(4,4,3,3,false,true));
  assertFalse(gate.allow(4,4,4,4,false,false));
  assertFalse(gate.allow(4,4,2,4,false,false));
  gate.selfSelection(7,7);gate.reset();assertTrue(gate.allow(4,4,7,7,false,false));
 }
 @Test public void intermediateCallbackKeepsPendingSelfMoves(){
  CursorTapGuard guard=new CursorTapGuard();guard.selfSelection(10,10);guard.selfSelection(11,11);
  assertTrue(guard.allow(9,9,8,8,false,false));
  assertFalse(guard.allow(8,8,10,10,false,false));
  assertFalse(guard.allow(10,10,11,11,false,false));
 }
 @Test public void queuedCommitDeleteAndCompositionUsePredictedUtf16Cursor(){
  CursorTapGuard guard=new CursorTapGuard();guard.observe(9,9,-1,-1);
  for(int n=0;n<20;n++)guard.replace(1,1,false);
  for(int n=0;n<10;n++)guard.delete(1,0);
  for(int n=10;n<=29;n++)assertFalse(guard.allow(n-1,n-1,n,n,false,false));
  for(int n=28;n>=19;n--)assertFalse(guard.allow(n+1,n+1,n,n,false,false));
  guard.observe(19,19,-1,-1);guard.replace(2,1,true);guard.replace(3,1,true);guard.replace(2,1,false);
  assertFalse(guard.allow(19,19,21,21,false,false));assertFalse(guard.allow(21,21,22,22,false,false));assertFalse(guard.allow(22,22,21,21,false,false));
 }
 @Test public void coalescedCallbacksReleaseEarlierPredictionsForImmediateUserTap(){
  CursorTapGuard guard=new CursorTapGuard();guard.observe(3,3,-1,-1);guard.replace(1,1,false);guard.replace(1,1,false);
  assertFalse(guard.allow(3,3,5,5,false,false));
  assertTrue(guard.allow(5,5,4,4,false,false));
 }
 @Test public void coalescedTypingAndDeletingReleaseOldPositionsForUserTap(){
  CursorTapGuard guard=new CursorTapGuard();guard.observe(9,9,-1,-1);
  for(int n=0;n<20;n++)guard.replace(1,1,false);for(int n=0;n<10;n++)guard.delete(1,0);
  assertFalse(guard.allow(9,9,19,19,false,false));assertTrue(guard.allow(19,19,20,20,false,false));
 }
 @Test public void expiredSelfMoveDoesNotHideUserTap()throws Exception{
  CursorTapGuard guard=new CursorTapGuard();guard.selfSelection(3,3);Thread.sleep(2100);
  assertTrue(guard.allow(4,4,3,3,false,false));
 }
 static class EmptyEngine implements ZhuyinInputController.Engine {
  public void key(String k){}public void backspace(){}public void space(){}public void enter(){}public void choose(int i){}public void moveCursor(String d){}public int cursorPosition(){return 0;}
  public String composingText(){return "";}public java.util.List<String> candidates(){return java.util.Collections.emptyList();}public String takeCommit(){return "";}public void clear(){}
 }
}
