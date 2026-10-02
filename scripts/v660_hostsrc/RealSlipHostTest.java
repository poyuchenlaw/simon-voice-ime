package com.simon.voiceime;
public class RealSlipHostTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void type(ZhuyinInputController c,String keys){for(int i=0;i<keys.length();i++)c.press(keys.charAt(i)==' '?"space":keys.substring(i,i+1));}
 public static void main(String[] a)throws Exception{
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
   ZhuyinInputController c=new ZhuyinInputController(e);type(c,"ㄅㄨˊㄧㄠˋㄩㄢ ㄨㄥˇㄋㄧˇ");
   String wrong=c.previewText();System.out.println("real slip original="+wrong+" keys="+c.sentenceKeys());
   check(!wrong.equals("不要冤枉你"),"slip fixture must actually be wrong");
   c.moveCursorToKey(11);c.press("backspace");var corrected=c.press("ㄤ");
   System.out.println("corrected="+c.previewText()+" keys="+c.sentenceKeys()+" first="+corrected.candidates.subList(0,Math.min(12,corrected.candidates.size())));
   check(c.previewText().equals("不要冤枉你"),"actual different-key fix restores sentence");
   check(!corrected.candidates.isEmpty()&&corrected.candidates.get(0).equals("冤枉"),"real different-key correction first word 冤枉");
   c.chooseCandidate(0);check(c.previewText().contains("你"),"suffix retained after word pin");check(c.press("enter").commitText.equals("不要冤枉你"),"real slip Enter");
   c.clear();type(c,"ㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ");
   var learned=c.moveCursorToPreviewCharacter(2);System.out.println("after slip learned focus="+learned.targetStart+".."+learned.targetEnd+" first="+learned.candidates.get(0));
   check(learned.candidates.get(0).equals("冤枉"),"slip commit then clean focus word");
   c.clear();type(c,"ㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ");int prefix=c.state().candidates.indexOf("不要");check(prefix>=0,"prefix candidate exists");
   var picked=c.chooseCandidate(prefix);check(picked.commitText.equals("不要"),"prefix partial commit");check(c.previewText().equals("冤枉你"),"remaining phrase");
   c.moveCursorToPreviewCharacter(0);check(c.keyCaret()==3,"remaining 冤 linked after prefix");c.moveCursorToPreviewCharacter(1);check(c.keyCaret()==6,"remaining 枉 linked after prefix");
   check(c.state().candidates.get(0).equals("冤枉"),"remaining word first after prefix");
   c.chooseCandidate(0);String pinned=c.previewText(),reading=c.sentenceKeys();
   c.moveCursorToKey(reading.length());c.press("backspace");c.press(reading.substring(reading.length()-1));
   check(c.previewText().equals(pinned)&&c.sentenceKeys().equals(reading),"你 editable after fixed segment");
   check(c.press("enter").commitText.equals("冤枉你"),"remaining Enter");
   c.clear();type(c,"ㄇㄧㄥˊㄒㄧㄢˇ");c.punctuation("，");type(c,"ㄙㄨㄛˇㄧˇ");
   check(c.previewText().equals("明顯，所以"),"punctuation fixture");
   c.moveCursorToPreviewCharacter(3);check(c.keyCaret()==13,"coarse 所 includes punctuation key offset");
   String separated=c.sentenceKeys();c.moveCursorToKey(11);c.press("backspace");c.press("ㄨ");
   check(c.sentenceKeys().equals(separated)&&c.previewText().equals("明顯，所以"),"punctuation-local symbol edit preserves both clauses");
   check(c.press("enter").commitText.equals("明顯，所以"),"punctuation-local Enter");
   System.out.println("PASS actual slip correction and prefix pick then linked remaining phrase");
  }
 }
}
