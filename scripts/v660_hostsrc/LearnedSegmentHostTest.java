package com.simon.voiceime;
public class LearnedSegmentHostTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static void type(ZhuyinInputController c){for(String k:new String[]{"ㄅ","ㄨ","ˊ","ㄧ","ㄠ","ˋ","ㄩ","ㄢ","space","ㄨ","ㄤ","ˇ","ㄋ","ㄧ","ˇ"})c.press(k);}
 public static void main(String[] a)throws Exception {
  java.io.File user=new java.io.File(a[1]);user.mkdirs();
  // Ordinary sentence commit (learning on) must remain a conversion hint, not an explicit word.
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){ZhuyinInputController c=new ZhuyinInputController(e);type(c);check(c.previewText().equals("不要冤枉你"),"fixture");check(c.press("enter").commitText.equals("不要冤枉你"),"ordinary learning Enter");}
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){ZhuyinInputController c=new ZhuyinInputController(e);type(c);
   var word=c.moveCursorToPreviewCharacter(2);System.out.println("learned preview="+c.previewText()+" span="+word.targetStart+".."+word.targetEnd+" first="+word.candidates.subList(0,Math.min(8,word.candidates.size())));
   check(c.keyCaret()==9,"linked 冤 caret");check(word.candidates.get(0).equals("冤枉"),"learned clause must not replace word focus");
   var firstTone=c.moveCursorToKey(9);
   System.out.println("first-tone key focus="+c.wordFocused()+" span="+firstTone.targetStart+".."+firstTone.targetEnd+" candidates="+firstTone.candidates.subList(0,Math.min(8,firstTone.candidates.size())));
   check(c.wordFocused(),"first-tone key caret must retain the focused word");
   check(!firstTone.candidates.isEmpty()&&firstTone.candidates.get(0).equals("冤枉"),"first-tone key caret offers the containing word first");
   var suffix=c.moveCursorToPreviewCharacter(4);
   check(suffix.targetStart==4&&suffix.targetEnd==5&&suffix.candidates.contains("你"),"suffix focus is a one-character word");
   c.moveCursorToPreviewCharacter(2);
   c.chooseCandidate(0);check(c.previewText().equals("不要冤枉你"),"word pin preserves sentence");check(c.press("enter").commitText.equals("不要冤枉你"),"Enter repeat sentence");
   c.clear();type(c);check(c.chooseCandidate(0).commitText.equals("不要冤枉你"),"ordinary full-clause row pick");
   type(c);var pickedClause=c.moveCursorToPreviewCharacter(2);
   System.out.println("single clause pick focus="+pickedClause.candidates.get(0));
   check(pickedClause.candidates.get(0).equals("冤枉"),"single whole-clause pick is not consecutive-pick word teaching");
   c.clear();
   String personal=a.length>2?a[2]:"明明魁",personalReading=a.length>3?a[3]:"ㄇㄧㄥˊㄇㄧㄥˊㄎㄨㄟˊ";
   c.clear();
   // Teach the name through actual consecutive prefix/suffix candidate picks.
   for(int i=0;i<personalReading.length();i++)c.press(personalReading.substring(i,i+1));
   String prefix=personal.substring(0,personal.offsetByCodePoints(0,2));
   int picked=c.state().candidates.indexOf(prefix);check(picked>=0,"name prefix candidate");c.chooseCandidate(picked);
   picked=c.state().candidates.indexOf(personal.substring(personal.offsetByCodePoints(0,2)));
   check(picked>=0,"name suffix candidate");c.chooseCandidate(picked);c.clear();
   for(int i=0;i<personalReading.length();i++)c.press(personalReading.substring(i,i+1));
   check(c.state().candidates.get(0).equals(personal),"learned private full name remains first whole phrase");
   var nameFocus=c.moveCursorToPreviewCharacter(1);
   check(c.previewText().equals(personal),"coarse private name focus preserves preview");
   System.out.println("name focus="+nameFocus.candidates.subList(0,Math.min(8,nameFocus.candidates.size())));
   check(nameFocus.candidates.get(0).equals(personal),"deliberate private phrase must be first focus word");
   check(c.press("enter").commitText.equals(personal),"private full phrase Enter still commits whole");
   c.clear();RimeVocabularyInstaller.install(user,java.util.List.<String[]>of(new String[]{personal,personalReading}));
   // Restore an already displayed private name using the production correction
   // restore seam. Focus scope is tested independently of normal conversion.
   try(SingleRimeZhuyinEngine restored=new SingleRimeZhuyinEngine(a[0],a[1])) {
    java.util.List<String> syllables=new java.util.ArrayList<>(java.util.Arrays.asList("ㄅㄨˊ","ㄧㄠˋ"));
    syllables.addAll(java.util.Arrays.asList(personalReading.replaceAll("([ˉˊˇˋ˙])","$1 ").trim().split(" +")));syllables.add("ㄋㄧˇ");
    restored.nativeEngine.restore(syllables,"不要"+personal+"你");
    check(restored.moveCursorToPreviewCharacter(3),"installed-word focus supported");
    check(restored.regroupLabels().get(0).equals(personal),"installed name inside clause first focus word");
   }
   c.clear();RimeVocabularyInstaller.install(user,java.util.Collections.emptyList());RimeVocabularyInstaller.resetLearned(user);
   for(int i=0;i<personalReading.length();i++)c.press(personalReading.substring(i,i+1));
   check(!c.state().candidates.contains(personal),"reset removes joined phrase conversion");
   check(java.nio.file.Files.readString(new java.io.File(user,"taught_vocab.tsv").toPath()).isEmpty(),"reset clears teaching origins");
   check(java.nio.file.Files.readString(new java.io.File(user,"committed_vocab.tsv").toPath()).isEmpty(),"reset clears automatic commit history");
   System.out.println("PASS ordinary clause, last-character focus, deliberate consecutive-pick word, installed interior word, reset");
  }
 }
}
