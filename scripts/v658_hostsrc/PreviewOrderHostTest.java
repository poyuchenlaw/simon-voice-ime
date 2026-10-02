package com.simon.voiceime;
public class PreviewOrderHostTest {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void type(ZhuyinInputController c,String keys){for(int i=0;i<keys.length();i++)c.press(keys.substring(i,i+1));}
 static ZhuyinInputController.State punct(ZhuyinInputController c,String x)throws Exception{return (ZhuyinInputController.State)ZhuyinInputController.class.getDeclaredMethod("punctuation",String.class).invoke(c,x);}
 public static void main(String[] a)throws Exception {try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){
 ZhuyinInputController c=new ZhuyinInputController(e);
 type(c,"ㄎㄜˇㄧˇㄉㄜ˙");String before=c.previewText();
 var p=punct(c,"，");check(p.commitText.isEmpty(),"punctuation must not commit preview");check(c.previewText().equals(before+"，"),"preview punctuation order");
 type(c,"ㄨㄛˇ");String displayed=c.previewText();check(displayed.startsWith(before+"，"),"independent suffix");check(c.press("enter").commitText.equals(displayed),"Enter equals display");
 c.clear();type(c,"ㄎㄜˇㄧˇ");punct(c,"，");type(c,"ㄉㄜ˙");c.cancelSecondPass();
 var first=c.state();int rank=first.candidates.indexOf("可以");check(rank>=0,"first span candidate after punctuation");
 var prefix=c.chooseCandidate(rank);check(prefix.commitText.equals("可以，"),"prefix candidate includes adjacent punctuation");check(c.phoneticText().equals("ㄉㄜ˙"),"suffix retained after first span");
 c.clear();type(c,"ㄎㄜˇㄧˇㄉㄜ˙");rank=c.state().candidates.indexOf("可以");check(rank>=0,"partial exists");
 var part=c.chooseCandidate(rank);check(part.commitText.equals("可以"),"partial commit");check(c.phoneticText().equals("ㄉㄜ˙"),"partial keys preserved");
 c.clear();type(c,"ㄎㄜˇㄧˇ");punct(c,"，");type(c,"ㄉㄜ˙");c.moveCursorToKey(6); // immediately after punctuation (5 phonetic keys + comma)
 String keys=c.sentenceKeys();c.press("backspace");check(c.sentenceKeys().equals(keys.replace("，","")),"backspace only comma");
 c.clear();check(punct(c,"，").commitText.equals("，"),"empty punctuation commits");
 for(String mark:new String[]{",",".",";","/","！","：","？","「","」","…","—"}){
  c.clear();check(punct(c,mark).commitText.equals(mark),"empty literal punctuation "+mark);
  type(c,"ㄇㄚˇ");punct(c,mark);String exact=c.previewText();check(exact.endsWith(mark),"literal mark visible");check(c.press("enter").commitText.equals(exact),"literal punctuation Enter "+mark);
 }
 System.out.println("PASS preview punctuation, independent conversion, Enter, partial pick, punctuation caret deletion");
 }} }
