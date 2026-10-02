package com.simon.voiceime;
public class NeighbourHostTest {
 public static void main(String[] args)throws Exception {

  RimeVocabularyInstaller.install(new java.io.File(args[1]),java.util.Collections.singletonList(new String[]{"所","ㄙㄨㄛˇ"}));
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(args[0],args[1])) {
   ZhuyinInputController c=new ZhuyinInputController(e);
   for(String k:new String[]{"ㄙ","ㄨ","ㄟ","ˇ","ㄧ","ˇ"})c.press(k);
   var s=c.moveCursorToKey(3);
   System.out.println("typed="+c.previewText()+" options="+s.candidates.subList(0,Math.min(8,s.candidates.size())));
   int found=-1;
   for(int i=0;i<Math.min(8,s.candidates.size());i++)if(s.candidates.get(i).equals("所")||s.candidates.get(i).equals("所以"))found=i;
   if(found<0)throw new AssertionError("ㄛ repair must occur in first eight key-caret options");
   c.chooseCandidate(found);
   if(!c.previewText().equals("所以"))throw new AssertionError("context conversion must be 所以; got "+c.previewText());
   System.out.println("PASS nearby-key repair and preserved suffix");
   c.clear();for(String k:new String[]{"ㄒ","ㄧ","ㄦ","ˇ"})c.press(k);
   java.lang.reflect.Method repair;
   try{repair=RimeZhuyinEngine.class.getDeclaredMethod("localRepair");}
   catch(NoSuchMethodException missing){throw new AssertionError("invalid-syllable repair suggestion missing",missing);}
   String[] suggestion=(String[])repair.invoke(e);
   if(suggestion.length!=2||!suggestion[1].equals("顯"))throw new AssertionError("local suggestion 顯; got "+java.util.Arrays.toString(suggestion));
   c.clear();for(String k:new String[]{"ㄇ","ㄧ","ㄥ","ˊ","ㄒ","ㄧ","ㄦ","ˇ"})c.press(k);
   suggestion=(String[])repair.invoke(e);if(suggestion.length!=2||!suggestion[1].equals("明顯"))throw new AssertionError("context repair 明顯: "+java.util.Arrays.toString(suggestion));
   c.clear();c.press("ㄍ");if(((String[])repair.invoke(e)).length!=0)throw new AssertionError("valid prefix must remain untouched");
   System.out.println("PASS local invalid syllable and abbreviation prefix");
   java.lang.reflect.Method auto;
   try{auto=AiComposition.class.getDeclaredMethod("applyAuto",String.class,String.class,long.class,boolean.class,boolean.class);}
   catch(NoSuchMethodException missing){throw new AssertionError("automatic correction with OFF/protected gate missing",missing);}
   c.clear();for(String k:new String[]{"ㄉ","ㄨ","ㄛ","ˋ","ㄩ","ˊ"})c.press(k);
   c.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],args[1]));
   String typed=c.previewText();System.out.println("auto fixture actual typed="+typed);
   AiComposition tx=new AiComposition(c);
   if(auto.invoke(tx,"ㄉㄨㄛ ㄩˊ","多餘",100L,false,false)!=null)throw new AssertionError("OFF must not apply");
   if(auto.invoke(tx,"ㄉㄨㄛ ㄩˊ","多餘",100L,true,true)!=null)throw new AssertionError("protected must not apply");
   ZhuyinInputController applied=(ZhuyinInputController)auto.invoke(tx,"ㄉㄨㄛ ㄩˊ","多餘",100L,true,false);
   if(applied==null||!applied.previewText().equals("多餘"))throw new AssertionError("auto display");
   var enter=AiComposition.class.getDeclaredMethod("beforeEnter",ZhuyinInputController.class,long.class);
   var restored=(ZhuyinInputController)enter.invoke(tx,applied,899L);
   if(restored!=c||!restored.press("enter").commitText.equals(typed))throw new AssertionError("early Enter atomic typed commit");
   System.out.println("PASS OFF, protection, auto apply, enter dwell rollback");
   c.clear();
   java.io.File user=new java.io.File(args[1]);

   for(int round=0;round<2;round++){
    for(String k:new String[]{"ㄇ","ㄧ","ㄥ","ˊ","ㄇ","ㄧ","ㄥ","ˊ","ㄎ","ㄨ","ㄟ","ˊ"})c.press(k);
    int pick=c.state().candidates.indexOf("明明");if(pick<0)throw new AssertionError("synthetic prefix absent: "+c.state().candidates);
    if(!c.chooseCandidate(pick).commitText.equals("明明"))throw new AssertionError("prefix pick");
    pick=c.state().candidates.indexOf("魁");if(pick<0)throw new AssertionError("synthetic suffix absent");
    if(!c.chooseCandidate(pick).commitText.equals("魁"))throw new AssertionError("suffix pick");
   }
   for(String k:new String[]{"ㄇ","ㄧ","ㄥ","ˊ","ㄇ","ㄧ","ㄥ","ˊ","ㄎ","ㄨ","ㄟ","ˊ"})c.press(k);
   if(!c.state().candidates.get(0).equals("明明魁"))throw new AssertionError("joined word must be first: "+c.state().candidates);
   System.out.println("PASS consecutive picks joined with full reading");
   c.clear();
   RimeVocabularyInstaller.class.getDeclaredMethod("resetLearned",java.io.File.class).invoke(null,user);
   for(String k:new String[]{"ㄇ","ㄧ","ㄥ","ˊ","ㄇ","ㄧ","ㄥ","ˊ","ㄎ","ㄨ","ㄟ","ˊ"})c.press(k);
   if(c.state().candidates.contains("明明魁"))throw new AssertionError("reset must remove joined word");
   c.chooseCandidate(c.state().candidates.indexOf("明明"));c.press("backspace");c.press("ˊ");
   c.chooseCandidate(c.state().candidates.indexOf("魁"));
   if(java.nio.file.Files.readString(new java.io.File(user,"committed_vocab.tsv").toPath()).contains("明明魁"))throw new AssertionError("broken run learned joined word");
   System.out.println("PASS reset and broken pick run");
  }
 }
}
