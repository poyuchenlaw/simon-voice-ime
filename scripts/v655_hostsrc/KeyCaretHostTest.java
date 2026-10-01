package com.simon.voiceime;
public class KeyCaretHostTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] a)throws Exception{
 RimeVocabularyInstaller.install(new java.io.File(a[1]),java.util.Collections.singletonList(new String[]{"甲乙","ㄅㄛ2 ㄇㄧㄣ2"}));
 try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
  ZhuyinInputController c=new ZhuyinInputController(e);
  c.press("ㄇ");c.press("ㄚ");c.press("ˇ");c.press("ˊ");
  check(c.sentenceKeys().equals("ㄇㄚˊ"),"second tone must replace first: "+c.sentenceKeys());
  check(!c.state().candidates.isEmpty(),"second tone has native menu");
  System.out.println("PASS second tone replacement");
  c.clear();for(String k:new String[]{"ㄇ","ㄚ","ˇ","ㄏ","ㄌ"})c.press(k);
  java.lang.reflect.Method move;
  try{move=ZhuyinInputController.class.getDeclaredMethod("moveCursorToKey",int.class);}catch(NoSuchMethodException missing){throw new AssertionError("key-level caret missing",missing);}
  move.invoke(c,4);c.press("backspace");
  check(c.sentenceKeys().equals("ㄇㄚˇㄌ"),"middle backspace removes one key, keeps suffix: "+c.sentenceKeys());
  c.press("ㄏ");check(c.sentenceKeys().equals("ㄇㄚˇㄏㄌ"),"inserts at caret, keeps suffix");
  check(!c.state().composingText.matches(".*[a-zA-Z0-9].*"),"safe re-decoded preedit");
  System.out.println("PASS key-level insertion and deletion");
  c.clear();for(String k:new String[]{"ㄉ","ㄠ","ˋ"})c.press(k);
  c.chooseCandidate(c.state().candidates.indexOf("到"));
  for(String k:new String[]{"ㄇ","ㄚ","ˇ"})c.press(k);
  String original=c.previewText();var focused=c.moveCursorToPreviewCharacter(0);
  check(c.candidateOrigin(0).equals("homophone"),"homophones first");
  int rank=focused.candidates.indexOf("道");check(rank>=0&&c.candidateOrigin(rank).equals("homophone"),"exact-reading native alternative");
  c.chooseCandidate(rank);check(c.previewText().equals("道"+original.substring(1)),"local span replacement preserves suffix");
  String chosen=c.previewText();c.moveCursorToPreviewBoundary(1);c.cancelSecondPass();
  check(c.previewText().equals(chosen),"boundary cancel cannot retranslate suffix");
  System.out.println("PASS homophones first, local span replacement and exact cancel");
  c.clear();for(String k:new String[]{"ㄅ","ㄛ","ˊ","ㄇ","ㄧ","ㄣ","ˊ"})c.press(k);
  c.chooseCandidate(c.state().candidates.indexOf("甲乙"));var installed=c.moveCursorToPreviewCharacter(0);
  check(installed.accepted&&c.candidateOrigin(0).equals("homophone"),"installed word has exact-reading alternatives");
  int literal=installed.candidates.indexOf("甲乙");check(literal>=0,"installed word retained");c.chooseCandidate(literal);
  check(c.previewText().equals("甲乙"),"installed word local choice");check(c.moveCursorToKey(3).accepted,"installed reading remains editable");
  System.out.println("PASS installed-word homophones and key alignment");
 }
 }
}
