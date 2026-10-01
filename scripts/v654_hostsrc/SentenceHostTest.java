package com.simon.voiceime;
import java.nio.file.*;
public class SentenceHostTest {
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  var user=Path.of(args[1]);Files.createDirectories(user);
  RimeVocabularyInstaller.install(user.toFile(),java.util.Collections.singletonList(new String[]{"甲乙","ㄅㄛ2 ㄇㄧㄣ2"}));
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(args[0],user.toString())){
   java.lang.reflect.Method method;
   try{method=RimeZhuyinEngine.class.getDeclaredMethod("prepareSentence",String.class,String.class);}
   catch(NoSuchMethodException missing){throw new AssertionError("No local full-reading sentence validation",missing);}
   check((Boolean)method.invoke(e,"ㄉㄨㄛ ㄩˊ","多餘"),"worked tone repair mapped by shipped Rime");
   check(e.previewText().equals("多餘"),"tone text prepared");
   check(e.composingText().equals("多餘"),"explicit AI apply must replace owned editor preedit with candidate");
   check((Boolean)method.invoke(e,"ㄑㄧㄢˊ","前"),"worked neighbour mapped by Rime");
   check(e.previewText().equals("前"),"neighbour text prepared");
   check(!(Boolean)method.invoke(e,"ㄑㄧㄢˊ","多餘"),"mismatched entire text refused");
   ZhuyinInputController original=new ZhuyinInputController(e);original.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],user.toString()));
   original.clear();for(String key:new String[]{"ㄉ","ㄨ","ㄛ","ˋ","ㄩ","ˊ"})original.press(key);
   String oldText=original.previewText(),oldKeys=original.sentenceKeys();
   original.moveCursorToPreviewBoundary(1);String oldPhonetic=original.phoneticText();var oldOptions=original.state().candidates;
   Class<?> tx;try{tx=Class.forName("com.simon.voiceime.AiComposition");}catch(ClassNotFoundException missing){throw new AssertionError("No exact composition undo transaction",missing);}
   Object transaction=tx.getDeclaredConstructor(ZhuyinInputController.class).newInstance(original);
   ZhuyinInputController applied=(ZhuyinInputController)tx.getDeclaredMethod("apply",String.class,String.class).invoke(transaction,"ㄉㄨㄛ ㄩˊ","多餘");
   check(applied!=null&&applied.previewText().equals("多餘"),"one tap apply remains preedit");
   check(applied.state().commitText.isEmpty(),"apply cannot commit");
   ZhuyinInputController restored=(ZhuyinInputController)tx.getDeclaredMethod("undo",ZhuyinInputController.class).invoke(transaction,applied);
   check(restored==original&&original.previewText().equals(oldText)&&original.sentenceKeys().equals(oldKeys)&&original.phoneticText().equals(oldPhonetic)&&original.state().candidates.equals(oldOptions),"undo exact keys grouping literal caret menu");
   e.clear();for(String key:new String[]{"ㄅ","ㄛ","ˊ","ㄇ","ㄧ","ㄣ","ˊ"})e.key(key);
   check(e.candidates().contains("甲乙"),"fixture installed in actual Rime menu");
   check(e.prepareSentence("ㄅㄛˊㄇㄧㄣˊ","甲乙"),"explicit installed reading revalidated locally");
   check(!e.prepareSentence("ㄅㄛˋㄇㄧㄣˊ","甲乙"),"installed word cannot invent another reading");
   System.out.println("PASS shipped Rime full-reading examples and unmapped refusal");
  }
 }
}
