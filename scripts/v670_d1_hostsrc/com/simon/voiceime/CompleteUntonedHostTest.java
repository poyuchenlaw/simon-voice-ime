package com.simon.voiceime;
import java.util.*;
/** Specification oracles on the production public key controller. */
public final class CompleteUntonedHostTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void type(ZhuyinInputController c,String keys){for(char k:keys.toCharArray())c.press(k==' '?"space":String.valueOf(k));}
 static void intact(ZhuyinInputController c){List<String> r=c.phoneticSyllables();check(r.contains("ㄍㄨㄤ"),"complete ㄍㄨㄤ missing: "+r+" / "+c.textPreview());}
 public static void main(String[] a)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));
  try{
   c.setTextLayout(true);
   c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
   for(String prefix:new String[]{"","ㄇㄞˋㄔㄨ ㄉㄜ˙ㄧ ㄆㄛ ","ㄇㄞˋㄔㄨ ㄉㄧˋㄧ ㄆㄛ "}){
    c.clear();type(c,prefix);int checked=0;
    String sequence="ㄊㄞˊㄍㄨㄤㄉㄧㄢˋㄏㄢˋㄔㄨㄤˋㄧˋㄌㄜ";
    for(int i=0;i<sequence.length();i++){
     c.press(sequence.substring(i,i+1));
     System.out.println("STEP prefix="+prefix+" i="+i+" reading="+c.phoneticSyllables()+" preview="+c.textPreview());
     if(i>=5){intact(c);checked++;}
    }
    check(c.sentenceKeys().equals(prefix+sequence),"physical keys unchanged");
    int boundary=prefix.isEmpty()?2:7;
    String before=c.textPreview(), oldKeys=c.sentenceKeys();c.moveCursorToPreviewBoundary(boundary);
    long cursorStarted=System.nanoTime();java.util.List<ZhuyinInputController.TextChoice> cursorMenu=c.textChoices();System.out.println("TIMING cursor boundary="+boundary+" ms="+((System.nanoTime()-cursorStarted)/1000000.0));
    ZhuyinInputController.TextChoice selected=null;for(var x:cursorMenu)if(x.label.equals("臺光")){selected=x;break;}
    check(selected!=null,"臺光 cursor available in prefix "+prefix+" choices="+c.textChoices());
    c.chooseTextCandidate(selected);check(c.sentenceKeys().equals(oldKeys),"cursor preserves keys");intact(c);
    check(c.textPreview().startsWith(before.substring(0,before.offsetByCodePoints(0,boundary-2))+"臺光"),"cursor preserves confirmed prefix");
    System.out.println("PASS prefix cursor boundary="+boundary+" full-range="+selected.start+":"+selected.end);
    String committed=c.press("enter").commitText;
    check(!committed.codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||"ˉˊˇˋ˙".indexOf(cp)>=0),"commit leaked phonetics");
    System.out.println("PASS incremental prefix="+prefix+" checked="+checked);
   }
   for(String[] sample:new String[][]{{"ㄎㄨㄛ","ㄉㄜ˙ㄌㄜㄇ"},{"ㄌㄧㄢ","ㄒㄧㄌㄜ"},{"ㄇㄧ","ㄤˊㄒㄧㄦˇㄕˋ"}}){
    c.clear();type(c,sample[0]);
    for(char k:sample[1].toCharArray()){
     c.press(String.valueOf(k));check(c.phoneticSyllables().contains(sample[0]),"dictionary full-span phrase suppressed complete syllable "+sample[0]+": "+c.phoneticSyllables());
    }
    System.out.println("PASS full-span phrase competitor "+sample[0]+" readings="+c.phoneticSyllables());
   }
   for(String[] full:new String[][]{{"ㄍㄥˋㄧㄡˇㄅㄚˇㄨㄛˋㄎㄜˇㄧˇㄕㄨㄖㄨˇ","ㄖㄨˇ"},{"ㄐㄧㄡˋㄎㄜˇㄧˇㄑㄩㄝˋㄉㄧ","ㄉㄧ"},{"ㄗㄞˋㄐㄧˋㄕㄨˋㄕㄣ","ㄕㄣ"}}){
    c.clear();type(c,full[0]);check(c.phoneticSyllables().contains(full[1]),"long dictionary tail lost complete syllable "+full[1]);
    System.out.println("PASS long dictionary tail "+full[1]+" readings="+c.phoneticSyllables());
   }
   c.clear();type(c,"ㄊㄞˊㄍㄨㄤㄉㄧㄢˋㄏㄢˋㄔㄨㄤˋㄧˋㄌㄜ");
   c.moveCursorToPreviewBoundary(2);List<ZhuyinInputController.TextChoice> menu=c.textChoices();
   ZhuyinInputController.TextChoice choice=null;
   for(var x:menu){if(x.label.equals("臺光")){choice=x;break;}}
   check(choice!=null,"臺光 cursor choice available "+menu);
   String old=c.sentenceKeys();c.chooseTextCandidate(choice);
   check(c.sentenceKeys().equals(old),"choice retains all physical keys");intact(c);
   check(c.textPreview().startsWith("臺光")&&!c.textPreview().startsWith("臺光網"),"臺光 must consume full ㄍㄨㄤ: "+c.textPreview());
   System.out.println("PASS cursor 臺光 range="+choice.start+":"+choice.end+" reading="+c.phoneticSyllables()+" preview="+c.textPreview());
   c.clear();type(c,"ㄊㄞˊㄍㄨㄤㄉㄧㄢˋㄏㄢˋㄔㄨㄤˋㄧˋㄌㄜ");
   c.moveCursorToPreviewBoundary(1);
   ZhuyinInputController.TextChoice middle=null;
   for(var x:c.textChoices())if(x.label.equals("臺光")&&x.start==0&&x.end==2){middle=x;break;}
   check(middle!=null,"G1 middle cursor must offer spanning 臺光");
   check(middle.wordFocus,"G2 boundary word menu retains wordFocus context");
   old=c.sentenceKeys();c.chooseTextCandidate(middle);
   check(c.sentenceKeys().equals(old)&&c.textPreview().startsWith("臺光"),"middle choice preserves keys and word");intact(c);
   System.out.println("PASS middle cursor range="+middle.start+":"+middle.end+" wordFocus="+middle.wordFocus);
   c.clear();type(c,"ㄨㄛˇㄖㄨˊㄍㄧㄠˋㄍㄣㄋㄧˇㄕㄨㄛ");
   check(c.phoneticSyllables().contains("ㄍ"),"果=ㄍ before ㄧ remains abbreviation");
   check(!c.textPreview().matches(".*[A-Za-z].*"),"shortcut leaked raw ASCII");
   System.out.println("PASS shortcut 我如果要跟你說 readings="+c.phoneticSyllables()+" preview="+c.textPreview());
   for(String[] shortcut:new String[][]{{"ㄨㄛㄖㄨㄍㄧㄠㄍㄣㄋㄧㄕㄨㄛ","我如果要跟你說"},{"ㄖㄨㄍㄧㄠ","如果要"}}){
    c.clear();type(c,shortcut[0]);check(c.textPreview().contains(shortcut[1]),"9/30 exact shortcut "+shortcut[1]+" -> "+c.textPreview());
    System.out.println("PASS original no-tone shortcut "+shortcut[1]+" preview="+c.textPreview());
   }

  }finally{c.close();}
 }
}
