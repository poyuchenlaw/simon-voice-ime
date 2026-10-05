package com.simon.voiceime;
import java.util.*;
/** Public key/preview seam: full candidate menus belong to the background worker. */
public class PreviewOnly670HostTest {
 static final class Engine implements ZhuyinInputController.Engine {
  String keys="";int fullMenus;
  public String provisionalText(){return keys.isEmpty()?"":"今";}
  public void key(String key){keys+=key;}
  public String sentenceKeys(){return keys;}
  public String composingText(){return keys;}
  public boolean preservesUnparsedInput(){return true;}
  public List<String> candidates(){fullMenus++;return Arrays.asList("今","金","斤");}
  public int cursorPosition(){return keys.length();}public String takeCommit(){return "";}
  public void backspace(){}public void space(){}public void enter(){}public void choose(int n){}public void moveCursor(String d){}public void clear(){keys="";}
 }
 public static void main(String[] args){Engine engine=new Engine();ZhuyinInputController c=new ZhuyinInputController(engine);c.setTextLayout(true);c.press("ㄐ");if(!"今".equals(c.textPreview()))throw new AssertionError("preview lost provisional character");if(engine.fullMenus!=0)throw new AssertionError("RED: main key/preview requested "+engine.fullMenus+" full native menus");System.out.println("PASS preview/key does not request full candidate menus");}
}
