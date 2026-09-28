package com.simon.voiceime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small, auditable controller+phone-JNI replay for explicit acceptance cases. */
public final class Proto3AcceptanceEval {
  static final class Engine implements ZhuyinInputController.Engine {
    final long h;
    Engine(String system,String user){h=ChewingEngine.nativeCreate(system,user);if(h==0)throw new IllegalStateException("nativeCreate failed");}
    static String text(byte[] b){return b==null?"":new String(b,StandardCharsets.UTF_8);}
    static String[] texts(byte[][] xs){String[] out=new String[xs.length];for(int i=0;i<xs.length;i++)out[i]=text(xs[i]);return out;}
    public void key(String k){ChewingEngine.nativeKey(h,ZhuyinKeyMap.physicalKey(k));} public void backspace(){ChewingEngine.nativeBackspace(h);}
    public void space(){ChewingEngine.nativeSpace(h);} public void enter(){ChewingEngine.nativeEnter(h);} public void choose(int i){ChewingEngine.nativeChoose(h,i);}
    public void moveCursor(String d){ChewingEngine.nativeMoveCursor(h,"right".equals(d));} public int cursorPosition(){return ChewingEngine.nativeCursor(h);}
    public String composingText(){return text(ChewingEngine.nativeComposing(h));} public List<String> candidates(){return Arrays.asList(texts(ChewingEngine.nativeCandidates(h)));}
    public String takeCommit(){return text(ChewingEngine.nativeTakeCommit(h));} public void clear(){ChewingEngine.nativeClear(h);}
  }
  public static void main(String[] a)throws Exception {
    Engine e=new Engine(a[0],a[1]); ZhuyinWordIndex index=a.length>2?ZhuyinWordIndex.fromFile(new File(a[2])):null; ZhuyinInputController c=index==null?new ZhuyinInputController(e):new ZhuyinInputController(e,index);
    BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
    while((line=in.readLine())!=null){String[] f=line.split("\t",3);if(f.length!=3)continue;c.clear();
      ZhuyinInputController.State s=null;
      for(int i=0;i<f[2].length();i++){int n=Proto3Eval.KEYS.indexOf(f[2].charAt(i));if(n<0)throw new IllegalArgumentException("unknown physical key");s=c.press(Proto3Eval.SYMBOLS.substring(n,n+1));}
      if(index==null)s=c.press("space");
      System.out.println(f[0]+"\t"+f[1]+"\t"+s.composingText+"\t"+String.join("|",s.candidates));
    }
  }
}
