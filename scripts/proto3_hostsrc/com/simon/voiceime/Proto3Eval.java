package com.simon.voiceime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Run each physical key through the production JNI adapter and controller order. */
public final class Proto3Eval {
 static final String SYMBOLS="ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙";
 static final String KEYS="1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347";
 static final class Engine implements ZhuyinInputController.Engine {
   long h; Engine(String s,String u){h=ChewingEngine.nativeCreate(s,u);if(h==0)throw new IllegalStateException("nativeCreate failed");}
   static String t(byte[] b){return b==null?"":new String(b,StandardCharsets.UTF_8);}
   public void key(String k){ChewingEngine.nativeKey(h,ZhuyinKeyMap.physicalKey(k));} public void backspace(){ChewingEngine.nativeBackspace(h);}
   public void space(){ChewingEngine.nativeSpace(h);} public void enter(){ChewingEngine.nativeEnter(h);} public void choose(int i){ChewingEngine.nativeChoose(h,i);}
   public void moveCursor(String d){ChewingEngine.nativeMoveCursor(h,"right".equals(d));} public int cursorPosition(){return ChewingEngine.nativeCursor(h);}
   public String composingText(){return t(ChewingEngine.nativeComposing(h));}
   public List<String> candidates(){ArrayList<String> out=new ArrayList<>();for(byte[] b:ChewingEngine.nativeCandidates(h))out.add(t(b));return out;}
   public String takeCommit(){return t(ChewingEngine.nativeTakeCommit(h));} public void clear(){ChewingEngine.nativeClear(h);} void close(){ChewingEngine.nativeDestroy(h);}
 }
 static void add(StringBuilder b,ZhuyinInputController.State s){if(s!=null&&s.commitText!=null)b.append(s.commitText);}
 public static void main(String[] a)throws Exception{
   BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;int row=0;
   Engine e=new Engine(a[0],a[1]+"/chewing.dat");
   while((line=in.readLine())!=null){String[] f=line.split("\t",3);if(f.length!=3)continue;String id=f[0],mode=f[1],keys=f[2];
     row++;ZhuyinInputController c=new ZhuyinInputController(e);StringBuilder out=new StringBuilder();ArrayList<Long> ns=new ArrayList<>();
     for(char physical:keys.toCharArray()){int index=KEYS.indexOf(physical);if(index<0)throw new IllegalArgumentException("unmapped physical key "+physical);long start=System.nanoTime();ZhuyinInputController.State s=c.press(SYMBOLS.substring(index,index+1));ns.add(System.nanoTime()-start);add(out,s);}
     ZhuyinInputController.State shown=c.state();
     if(!shown.candidates.isEmpty())add(out,c.chooseCandidate(0));else add(out,c.press("space"));
     add(out,c.state());add(out,c.press("enter"));String prediction=out+e.composingText();
     System.out.print(id+"\t"+mode+"\t"+prediction+"\t");for(int i=0;i<ns.size();i++){if(i>0)System.out.print(',');System.out.print(ns.get(i));}System.out.println();c.clear();
   }
   e.close();
 }
}
