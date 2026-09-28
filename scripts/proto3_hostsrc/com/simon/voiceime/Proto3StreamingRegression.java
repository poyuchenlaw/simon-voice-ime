package com.simon.voiceime;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Drives the production ZhuyinInputController through the production phone JNI. */
public final class Proto3StreamingRegression {
  static final class JniEngine implements ZhuyinInputController.Engine {
    long h;
    JniEngine(String system,String user){h=ChewingEngine.nativeCreate(system,user);if(h==0)throw new AssertionError("nativeCreate failed");}
    public void key(String s){ChewingEngine.nativeKey(h,ZhuyinKeyMap.physicalKey(s));}
    public void backspace(){ChewingEngine.nativeBackspace(h);} public void space(){ChewingEngine.nativeSpace(h);}
    public void enter(){ChewingEngine.nativeEnter(h);} public void choose(int i){ChewingEngine.nativeChoose(h,i);}
    public void moveCursor(String d){ChewingEngine.nativeMoveCursor(h,"right".equals(d));}
    public int cursorPosition(){return ChewingEngine.nativeCursor(h);}
    public String composingText(){return text(ChewingEngine.nativeComposing(h));}
    public List<String> candidates(){return Arrays.asList(strings(ChewingEngine.nativeCandidates(h)));}
    public String takeCommit(){return text(ChewingEngine.nativeTakeCommit(h));}
    public void clear(){ChewingEngine.nativeClear(h);}
    void close(){ChewingEngine.nativeDestroy(h);}
  }
  static String text(byte[] b){return new String(b,StandardCharsets.UTF_8);}
  static String[] strings(byte[][] xs){String[] out=new String[xs.length];for(int i=0;i<xs.length;i++)out[i]=text(xs[i]);return out;}
  static String type(ZhuyinInputController c,String s){StringBuilder out=new StringBuilder();for(int i=0;i<s.length();i++){ZhuyinInputController.State st=c.press(s.substring(i,i+1));if(st.commitText!=null)out.append(st.commitText);}return out.toString();}
  static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
  public static void main(String[] args){
    JniEngine e=new JniEngine(args[0],args[1]); ZhuyinInputController c=new ZhuyinInputController(e);
    // Two initials without a tone: the second must start/continue the next syllable.
    type(c,"ㄕㄅ"); String afterTwo=c.state().composingText;
    System.out.println("after_shi_then_b="+afterTwo);
    require(afterTwo.codePointCount(0,afterTwo.length())>=2 && afterTwo.contains("ㄅ"),"new initial replaced previous syllable: "+afterTwo);
    c.clear();
    String committed=type(c,"ㄕㄅㄨㄕㄉㄜ");
    // Follow the IME commit protocol: select candidate zero when shown, then Enter.
    ZhuyinInputController.State sp=c.press("space"); if(sp.commitText!=null)committed+=sp.commitText;
    ZhuyinInputController.State en=c.press("enter"); if(en.commitText!=null)committed+=en.commitText;
    System.out.println("tone_free_commit="+committed+" composing="+e.composingText());
    require(committed.contains("是不是的"),"tone-free phrase did not produce expected phrase: "+committed);
    e.close(); System.out.println("PROTO3_STREAMING_REGRESSION_PASS");
  }
}
