package com.simon.voiceime;
import java.util.*;
public class App690BOutputReplay {
 static class Engine implements ZhuyinInputController.Engine {
  String text="",commit="";List<String> choices;
  Engine(List<String> c){choices=c;}
  public void key(String k){text+=k;}public void backspace(){if(!text.isEmpty())text=text.substring(0,text.length()-1);}public void space(){text="你好";}public void enter(){commit=text;text="";}public void choose(int i){text=choices.get(i);}public void moveCursor(String d){}public int cursorPosition(){return text.length();}public String composingText(){return text;}public List<String> candidates(){return choices;}public String takeCommit(){String out=commit;commit="";return out;}public void clear(){text="";}
 }
 static void run(String[] keys,String expected,boolean candidates){
  ZhuyinInputController c=new ZhuyinInputController(new Engine(candidates?List.of("你好","你號"):List.of()));StringBuilder committed=new StringBuilder();
  for(String key:keys){ZhuyinInputController.State s=key.startsWith("pick:")?c.chooseCandidate(Integer.parseInt(key.substring(5))):c.press(key);committed.append(s.commitText);System.out.println(key+"|"+s.composingText+"|"+s.commitText+"|"+String.join(",",s.candidates)+"|"+s.accepted);}
  assert expected.equals(committed.toString()):expected+" != "+committed;
 }
 public static void main(String[] args){
  run(new String[]{"ㄋ","ㄧ","ˇ","ㄏ","ㄠ","ˇ","space","pick:0","enter"},"你好",true);
  run(new String[]{"ㄋ","ㄧ","ˇ","ㄏ","ㄠ","ˇ","space","pick:1","enter"},"你號",true);
  run(new String[]{"ㄅ","enter"},"ㄅ",false);
  run(new String[]{"ㄅ","ㄆ","backspace","enter"},"ㄅ",false);
 }
}
