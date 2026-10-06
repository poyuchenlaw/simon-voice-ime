package com.simon.voiceime;
import java.util.*;
public final class Z12HostTest {
 static void keys(ZhuyinInputController c,String s){for(char k:s.toCharArray())c.press(k==' '?"space":String.valueOf(k));}
 static void eq(Object want,Object got,String what){if(!want.equals(got))throw new AssertionError(what+" expected="+want+" actual="+got);}
 public static void main(String[] args)throws Exception{
  try(RimeZhuyinEngine engine=new RimeZhuyinEngine(args[0],args[1])){
   ZhuyinInputController c=new ZhuyinInputController(engine);c.setTextLayout(true);c.setLearningEnabled(false);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],args[1]));
   if(args[2].equals("delete")){
    for(String s:new String[]{"ㄍㄨㄥ","ㄍㄨㄥ ","ㄨㄛˇ","ㄐㄧㄣ ㄊㄧㄢ","ㄐㄧㄣ ㄊㄧㄢ "}){
     c.clear();keys(c,s);System.out.println("BEFORE "+s+" text="+c.textPreview()+" raw="+c.previewText()+" readings="+c.phoneticSyllables());
     c.press("backspace");String want=s.startsWith("ㄐ")?"今":"";eq(want,c.textPreview(),"one key deletes whole character");eq(s.startsWith("ㄐ")?"ㄐㄧㄣ ":"",c.sentenceKeys(),"whole reading removed");
    }
    c.clear();keys(c,"ㄐㄧㄣ ㄊㄧㄢ ");c.moveCursorToPreviewBoundary(1);keys(c,"ㄍㄨㄥ");
    System.out.println("INSERT BEFORE raw="+c.previewText()+" shown="+c.textPreview()+" readings="+c.phoneticSyllables());
    c.press("backspace");eq("今天",c.textPreview(),"inserted provisional character deleted whole");eq(Arrays.asList("ㄐㄧㄣˉ","ㄊㄧㄢˉ"),c.phoneticSyllables(),"inserted reading deleted whole");
    c.press("backspace");eq("天",c.textPreview(),"second deletion removes predecessor only");
    c.clear();keys(c,"ㄐㄧㄣ ㄊㄧㄢ ");c.moveCursorToPreviewBoundary(0);c.press("backspace");eq("今天",c.textPreview(),"delete at start is a no-op");
    c.clear();keys(c,"ㄍㄨㄥ ");c.press("backspace");eq("",c.press("backspace").commitText,"empty composition never emits text");
    c.setTextLayout(false);keys(c,"ㄍㄨㄥ");c.press("backspace");eq("ㄍㄨ",c.sentenceKeys(),"legacy deletes a phonetic key");
   }else if(args[2].equals("characters")){
    keys(c,"ㄐㄧㄣ ㄊㄧㄢ ");c.moveCursorToPreviewBoundary(0);
    for(ZhuyinInputController.TextChoice ch:c.textChoices())if("char".equals(ch.kind))System.out.println("CHAR "+ch.label+" "+ch.start+":"+ch.end);
   }else{
    keys(c,"ㄐㄧㄣ ㄊㄧㄢ ㄨㄛˇㄇㄣ˙ㄧˋㄑㄧˇㄊㄠˇㄌㄨㄣˋㄐㄧˋㄏㄨㄚˋ");
    int wordCount=0,charCount=0;
    for(ZhuyinInputController.TextChoice ch:c.textChoices())if("char".equals(ch.kind))charCount++;else{wordCount++;System.out.println("WORD "+ch.label+" span="+ch.start+":"+ch.end);if(ch.label.equals(c.previewText()))throw new AssertionError("whole original sentence in word row");if(ch.label.codePointCount(0,ch.label.length())<2)throw new AssertionError("character alternative in word row: "+ch.label);}
    if(wordCount==0||charCount==0)throw new AssertionError("word and character alternatives must remain available");
   }
   System.out.println("PASS "+args[2]);
  }
 }
}
