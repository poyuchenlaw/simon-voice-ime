package com.simon.voiceime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Replays normalized telemetry keys through the production controller and Rime JNI boundary. */
public final class LoggedKeysReplay {
  static final class Engine implements ZhuyinInputController.Engine, AutoCloseable {
    final RimeZhuyinNative rime;
    Engine(String shared,String user){rime=new RimeZhuyinNative(shared,user);}
    public void key(String symbol){rime.key(ZhuyinKeyMap.physicalKey(symbol));}
    public void backspace(){rime.backspace();} public void space(){rime.space();} public void enter(){rime.enter();}
    public void choose(int i){rime.choose(i);} public void moveCursor(String d){rime.moveCursor("right".equals(d));}
    public int cursorPosition(){return rime.cursor();} public String composingText(){return rime.composing();}
    public List<String> candidates(){return Arrays.asList(rime.candidates());} public String takeCommit(){return rime.takeCommit();}
    public void clear(){rime.clear();} public void close(){rime.close();}
  }
  public static void main(String[] args)throws Exception{
    Engine engine=new Engine(args[0],args[1]); ZhuyinInputController controller=new ZhuyinInputController(engine);
    int groups=0,events=0,modeSwitches=0;
    try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
      String line; while((line=in.readLine())!=null){
        if(line.isEmpty())continue; groups++;
        for(String symbol:line.split("\t")){
          if(symbol.startsWith("to")){controller.clear();modeSwitches++;continue;}
          ZhuyinInputController.State state=controller.press(symbol);
          if(state==null||state.composingText==null||state.candidates==null)throw new AssertionError("controller returned invalid state");
          events++;
        }
        controller.clear();
      }
    } finally {controller.close();}
    System.out.println("PASS telemetry controller replay groups="+groups+" key_events="+events+" mode_switches="+modeSwitches);
  }
}
