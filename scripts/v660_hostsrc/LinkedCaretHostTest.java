package com.simon.voiceime;
public class LinkedCaretHostTest {
 static void check(boolean v,String m){if(!v)throw new AssertionError(m);}
 static void type(ZhuyinInputController c,String k){for(int i=0;i<k.length();i++)c.press(k.charAt(i)==' '?"space":k.substring(i,i+1));}
 public static void main(String[] a)throws Exception{
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
   ZhuyinInputController c=new ZhuyinInputController(e);
   String keys="ㄇㄧㄥˊㄒㄧㄢˇㄙㄨㄛˇㄧˇ";
   type(c,keys);check(c.previewText().equals("明顯所以"),"fixture "+c.previewText());
   c.moveCursorToPreviewCharacter(0);c.moveCursorToPreviewCharacter(1);check(c.keyCaret()==8,"coarse caret must link to after 顯 syllable, got "+c.keyCaret());
   for(int at:new int[]{7,10,8,12}){
    var menu=c.moveCursorToKey(at);String old=c.sentenceKeys();
    check(!menu.candidates.isEmpty(),"clean symbol options at "+at);
    // ㄨ in ㄙㄨㄛˇ has no normal-toned spelling after a physical neighbour substitution.
    // The existing dictionary-valid neighbour rule must not invent one.
    if(at==7||at==8)check(c.candidateOrigin(0).equals("neighbour"),"clean symbol neighbour first at "+at);
    if(c.candidateOrigin(0).equals("neighbour")){c.chooseCandidate(0);String neighbour=c.sentenceKeys();
    check(neighbour.length()==old.length(),"neighbour replace length");
    int changed=0;for(int i=0;i<old.length();i++)if(old.charAt(i)!=neighbour.charAt(i)){changed++;check(i==at-1,"neighbour changed wrong slot "+i+" vs "+(at-1));}
    check(changed==1,"one selected neighbour symbol");}c.clear();type(c,keys);c.moveCursorToKey(at);c.press("backspace");
    check(c.sentenceKeys().equals(old.substring(0,at-1)+old.substring(at)),"exact delete slot "+at+": "+c.sentenceKeys());
    check(c.keyCaret()==at-1,"delete caret");c.press(old.substring(at-1,at));
    check(c.sentenceKeys().equals(old),"insert restores keys");check(c.previewText().equals("明顯所以"),"insert restores conversion "+c.previewText());
   }
   check(c.press("enter").commitText.equals("明顯所以"),"Enter payload");
   c.clear();type(c,"ㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ");
   System.out.println("phrase fixture="+c.previewText());
   var clean=c.moveCursorToPreviewCharacter(2);
   check(!clean.candidates.isEmpty()&&clean.candidates.get(0).equals("冤枉"),"clean confirmation first word "+clean.candidates);
   c.moveCursorToPreviewCharacter(3);check(c.keyCaret()==12,"adjacent coarse 枉 links slot12, got "+c.keyCaret());
   c.moveCursorToPreviewCharacter(2);int at=c.keyCaret();check(at==9,"adjacent coarse 冤 links slot 9");
   c.moveCursorToKey(at-1);String old=c.sentenceKeys();String symbol=old.substring(at-2,at-1);
   c.press("backspace");var s=c.press(symbol);
   System.out.println("edited phrase="+c.previewText()+" options="+s.candidates.subList(0,Math.min(10,s.candidates.size())));
   check(!s.candidates.isEmpty()&&s.candidates.get(0).equals("冤枉"),"edited segment first 冤枉, got "+s.candidates);
   c.chooseCandidate(0);check(c.previewText().contains("你"),"suffix 你 remains");
   check(c.press("enter").commitText.equals("不要冤枉你"),"full sentence fixed then Enter");
   c.clear();type(c,"ㄩㄢ ㄨㄤˇ");c.moveCursorToKey(3);String toned=c.sentenceKeys();
   c.press("backspace");check(c.keyCaret()==2,"first tone delete caret");
   var tone=c.press("space");check(tone.commitText.isEmpty()&&c.sentenceKeys().equals(toned)&&c.keyCaret()==3,"first tone inserts instead of commits");
   c.moveCursorToKey(0);String zeroKeys=c.sentenceKeys();var zero=c.press("backspace");
   check(zero.commitText.isEmpty()&&c.sentenceKeys().equals(zeroKeys)&&c.keyCaret()==0,"zero caret Backspace no-op");
   System.out.println("PASS linked caret, middle symbol/tone delete insert, segment candidate pick, zero no-op");
  }
 }
}
