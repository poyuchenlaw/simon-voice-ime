package com.simon.voiceime;
import java.util.*;
public class EditedWordHostTest {
 static void type(ZhuyinInputController c,String k){for(int i=0;i<k.length();i++)c.press(k.charAt(i)==' '?"space":k.substring(i,i+1));}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[] a)throws Exception {
  int failed=0;
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
   ZhuyinInputController c=new ZhuyinInputController(e);c.setLearningEnabled(false);
   c.clear();type(c,"ㄊ˙ㄅㄚˇ");for(int i=0;i<4;i++)c.press("backspace");type(c,"ㄚㄅㄚˇㄨㄛˇㄔㄞㄔㄥˊㄧˇㄒㄧㄚˋㄘˊㄏ");
   int pick=c.state().candidates.indexOf("他把");check(pick>=0,"recorded 他把 pick missing");c.chooseCandidate(pick);type(c,"ㄨㄟˋ");
   System.out.println("REAL PATH 詞彙 initial=ㄊ˙ㄅㄚˇ backspace=4 append=ㄚㄅㄚˇㄨㄛˇㄔㄞㄔㄥˊㄧˇㄒㄧㄚˋㄘˊㄏ pick=他把 append=ㄨㄟˋ");
   System.out.println("REAL 詞彙 11:47 keys="+c.sentenceKeys()+" preview="+c.previewText()+" top="+c.state().candidates.subList(0,Math.min(10,c.state().candidates.size())));
   if(!c.previewText().contains("詞彙")&&!c.previewText().contains("詞匯")){failed++;System.out.println("FAIL real 詞彙 abbreviation");}
   String ciKeys=c.sentenceKeys();check(e.focusAfterKeyEdit(ciKeys.length()),"real ci edited focus");List<String> ciEdited=new ArrayList<>(e.regroupLabels());
   c.clear();type(c,ciKeys);check(e.focusAfterKeyEdit(ciKeys.length()),"real ci fresh focus");List<String> ciFresh=new ArrayList<>(e.regroupLabels());
   System.out.println("REAL EQUALITY 詞彙 keys="+ciKeys+" fresh="+ciFresh+" edited="+ciEdited);
   if(!ciFresh.equals(ciEdited)||ciEdited.isEmpty()||!Set.of("詞彙","詞匯").contains(ciEdited.get(0))){failed++;System.out.println("FAIL actual ci word equality");}
   c.clear();type(c,"ㄐㄧ ㄏㄨㄧㄓˊㄉㄚˇㄐㄧㄡˋㄎㄜˇㄧˇㄑㄩㄝˋㄉㄧㄤˋㄔㄤˊㄨㄣˊㄒㄧㄚˋ");
   System.out.println("REAL 幾乎 before="+c.previewText());c.moveCursorToKey(28);c.press("backspace");c.press("backspace");c.press("ㄥ");
   System.out.println("REAL 幾乎 11:46 keys="+c.sentenceKeys()+" preview="+c.previewText()+" top="+c.state().candidates.subList(0,Math.min(10,c.state().candidates.size())));
   if(!c.previewText().startsWith("幾乎")||!c.state().candidates.contains("確定")||c.state().candidates.stream().anyMatch(x->x.codePointCount(0,x.length())>2)){failed++;System.out.println("FAIL real edited 幾乎 focus");}
   String jiKeys=c.sentenceKeys();List<String> jiEdited=new ArrayList<>(c.state().candidates);check(e.focusAfterKeyEdit(5),"real ji prefix edited focus");List<String> jiPrefixEdited=new ArrayList<>(e.regroupLabels());
   c.clear();type(c,jiKeys);check(e.focusAfterKeyEdit(27),"real ji fresh edited-position focus");List<String> jiFresh=new ArrayList<>(e.regroupLabels());check(e.focusAfterKeyEdit(5),"real ji prefix fresh focus");List<String> jiPrefixFresh=new ArrayList<>(e.regroupLabels());
   System.out.println("REAL EQUALITY 幾乎 keys="+jiKeys+" fresh="+jiFresh+" edited="+jiEdited+" prefixFresh="+jiPrefixFresh+" prefixEdited="+jiPrefixEdited);
   if(!jiFresh.equals(jiEdited)||!jiPrefixFresh.equals(jiPrefixEdited)||jiPrefixEdited.isEmpty()||!jiPrefixEdited.get(0).equals("幾乎")){failed++;System.out.println("FAIL actual ji word equality");}
   c.clear();type(c,"ㄧㄡㄍㄞㄧㄝˇㄕˋㄧㄠㄓㄉㄨㄡˋㄋㄞ");
   c.moveCursorToKey(12);c.press("backspace");c.press("ˋ");
   c.moveCursorToKey(15);c.press("backspace");c.press("ㄥ");
   c.moveCursorToKey(17);c.press("backspace");c.press("ㄊ");
   c.moveCursorToKey(18);c.press("ˋ");
   List<String> dynamicEdited=new ArrayList<>(c.state().candidates);String dynamicKeys=c.sentenceKeys();String dynamicPreview=c.previewText();
   c.clear();type(c,dynamicKeys);check(e.focusAfterKeyEdit(19),"11:55 fresh 動態 focus");List<String> dynamicFresh=new ArrayList<>(e.regroupLabels());
   System.out.println("REAL SCREENSHOT 11:55:54 keys="+dynamicKeys+" preview="+dynamicPreview+" fresh="+dynamicFresh+" edited="+dynamicEdited);
   if(!dynamicKeys.equals("ㄧㄡㄍㄞㄧㄝˇㄕˋㄧㄠˋㄉㄨㄥˋㄊㄞˋ")||!dynamicPreview.endsWith("動態")||!dynamicEdited.contains("動態")||!dynamicFresh.equals(dynamicEdited)){failed++;System.out.println("FAIL exact 11:55 動態 screenshot edits");}
   c.clear();type(c,"ㄉㄨㄕㄨ");c.moveCursorToKey(2);c.press("ˋ");List<String> degreeEdited=new ArrayList<>(c.state().candidates);
   c.clear();type(c,"ㄉㄨˋㄕㄨ");check(e.focusAfterKeyEdit(3),"12:15 fresh 度數 focus");List<String> degreeFresh=new ArrayList<>(e.regroupLabels());
   System.out.println("REAL EDIT 12:15:17 度數 initial=ㄉㄨㄕㄨ caret=2 insert=ˋ fresh="+degreeFresh+" edited="+degreeEdited);
   if(!degreeFresh.equals(degreeEdited)||degreeEdited.isEmpty()||!degreeEdited.get(0).equals("度數")){failed++;System.out.println("FAIL real 度數 word equality");}
   for(String[] inserted:new String[][]{{"12:49 actual bu2","ㄏㄡˋㄨˊㄩㄥˋㄑㄧㄤˊㄉㄧㄠˋ","ㄏㄡˋㄅㄨˊㄩㄥˋㄑㄧㄤˊㄉㄧㄠˋ","3"},{"canonical bu4","ㄨˋㄩㄥˋ","ㄅㄨˋㄩㄥˋ","0"}}){
    int caret=Integer.parseInt(inserted[3]);c.clear();type(c,inserted[2]);c.moveCursorToKey(caret+1);check(e.focusAfterKeyEdit(caret+1),"fresh inserted initial focus");List<String> fresh=new ArrayList<>(e.regroupLabels());String freshPreview=c.previewText();
    c.clear();type(c,inserted[1]);c.moveCursorToKey(caret);c.press("ㄅ");List<String> after=new ArrayList<>(c.state().candidates);
    System.out.println("INITIAL INSERT "+inserted[0]+" keys="+c.sentenceKeys()+" freshPreview="+freshPreview+" editedPreview="+c.previewText()+" fresh="+fresh+" edited="+after);
    if(!c.sentenceKeys().equals(inserted[2])||!c.previewText().equals(freshPreview)||!fresh.equals(after)||!after.contains("不用")){failed++;System.out.println("FAIL initial insertion resyllabification "+inserted[0]);}
   }
   for(String[] sandhi:new String[][]{{"不用","ㄅㄨˊㄩㄥˋ"},{"不要","ㄅㄨˊㄧㄠˋ"},{"一定","ㄧˊㄉㄧㄥˋ"},{"一起","ㄧˋㄑㄧˇ"},{"一般","ㄧˋㄅㄢ "},{"一同","ㄧˋㄊㄨㄥˊ"},{"不用","ㄅㄨˋㄩㄥˋ"}}){
    c.clear();type(c,sandhi[1]);List<String> typed=new ArrayList<>(c.state().candidates);
    check(typed.contains(sandhi[0]),"fresh sandhi word missing "+sandhi[0]+" "+typed);
    if(sandhi[1].equals("ㄅㄨˋㄩㄥˋ"))check(typed.get(0).equals("不用"),"canonical as-typed word must stay first");
    c.moveCursorToKey(1);check(e.focusAfterKeyEdit(1),"sandhi fresh focused");List<String> fresh=new ArrayList<>(e.regroupLabels());
    c.clear();type(c,sandhi[1]);c.moveCursorToKey(1);c.press("backspace");c.press(sandhi[1].substring(0,1));List<String> edited=new ArrayList<>(c.state().candidates);
    System.out.println("SANDHI "+sandhi[1]+" typed="+typed+" focused="+fresh+" edited="+edited);
    check(fresh.contains(sandhi[0])&&fresh.equals(edited),"sandhi fresh/edited mismatch "+sandhi[0]);
    int selected=edited.indexOf(sandhi[0]);check(c.chooseCandidate(selected).accepted,"sandhi focused pick "+sandhi[0]);c.press("enter");
    c.clear();type(c,sandhi[1]);int ix=c.state().candidates.indexOf(sandhi[0]);var choice=c.chooseCandidate(ix);check(choice.accepted&&choice.commitText.equals(sandhi[0]),"fresh sandhi pick "+sandhi[0]+" got "+choice.commitText);
   }
   c.clear();type(c,"ㄅㄨˊ");check(c.state().candidates.contains("醭"),"literal bu2 醭 must remain reachable");
   c.clear();type(c,"ㄅㄨˊㄩㄥˋ");check(c.state().candidates.contains("醭"),"sandhi must retain literal bu2 character");
   for(String[] test:new String[][]{{"詞彙","ㄘˊㄏㄨㄟˋ","4"},{"幾乎","ㄐㄧ ㄏㄨ ","5"},{"遊標","ㄧㄡˊㄅㄧㄠ ","3"},{"所以","ㄙㄨㄛˇㄧˇ","3"},{"明顯","ㄇㄧㄥˊㄒㄧㄢˇ","7"}}){
    c.clear();type(c,test[1]);List<String> fresh=new ArrayList<>(c.state().candidates);
    System.out.println("FRESH "+test[0]+" keys="+c.sentenceKeys()+" preview="+c.previewText()+" top="+fresh.subList(0,Math.min(12,fresh.size())));
    int at=Integer.parseInt(test[2]);c.moveCursorToKey(at);c.press("backspace");var edited=c.press(test[1].substring(at-1,at).equals(" ")?"space":test[1].substring(at-1,at));
    System.out.println("EDITED "+test[0]+" keys="+c.sentenceKeys()+" preview="+c.previewText()+" top="+edited.candidates.subList(0,Math.min(12,edited.candidates.size())));
    if(fresh.isEmpty()||!(fresh.get(0).equals(test[0])||test[0].equals("詞彙")&&fresh.get(0).equals("詞匯"))||!fresh.equals(edited.candidates)){failed++;System.out.println("FAIL fresh/edited candidate equality "+test[0]);}
   }
   String[][] real={{"11:33:52 return-to-reading","ㄋㄧˇㄧㄠˋㄐㄧㄝ","ㄋㄧˇㄧㄠˋㄐㄧㄝ","9"},{"11:48:22 replace","ㄨㄛˇㄧㄠˋㄍㄥ","ㄨㄧˇㄧㄠˋㄍㄥ","2"},{"11:49:04 tone-insert","ㄗˋㄏㄨㄟˋㄅㄚˇㄧㄡˊㄅㄧㄠㄨㄤˇㄑㄧㄢˊㄧˊ","ㄗˋㄏㄨㄟˋㄅㄚˇㄧㄡㄅㄧㄠㄨㄤˇㄑㄧㄢˊㄧˊ","12"}};
   for(int i=0;i<real.length;i++){
    String[] r=real[i];int finalCaret=Integer.parseInt(r[3]);
    c.clear();type(c,r[1]);c.moveCursorToKey(finalCaret);check(e.focusAfterKeyEdit(finalCaret),"fresh lexical focus "+r[0]);List<String> fresh=new ArrayList<>(e.regroupLabels());
    c.clear();type(c,r[2]);
    if(i==0){c.moveCursorToKey(9);type(c,"ㄐㄧㄝ");c.press("backspace");c.press("backspace");c.press("backspace");}
    if(i==1){c.moveCursorToKey(2);c.press("backspace");c.press("ㄛ");c.press("backspace");c.press("ㄛ");}
    if(i==2){c.moveCursorToPreviewCharacter(0);int ix=c.state().candidates.indexOf("字");check(ix>=0,"字 fixture");c.chooseCandidate(ix);c.moveCursorToPreviewCharacter(1);ix=c.state().candidates.indexOf("彙");check(ix>=0,"彙 fixture");c.chooseCandidate(ix);c.moveCursorToKey(11);c.press("ˊ");}
    List<String> after=c.state().candidates;System.out.println("REAL EDIT "+r[0]+" keys="+c.sentenceKeys()+" preview="+c.previewText()+" fresh="+fresh.subList(0,Math.min(10,fresh.size()))+" edited="+after.subList(0,Math.min(10,after.size())));
    if(!c.sentenceKeys().equals(r[1])||!fresh.equals(after)){failed++;System.out.println("FAIL real edit equality "+r[0]+" expected="+fresh.size()+" actual="+after.size());}
   }
  }
  check(failed==0,"failed="+failed);
 }
}
