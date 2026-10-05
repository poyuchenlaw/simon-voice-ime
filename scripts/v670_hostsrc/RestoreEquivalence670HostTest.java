package com.simon.voiceime;
import java.util.*;
public class RestoreEquivalence670HostTest {
 public static void main(String[] a)throws Exception {
  String[] base={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};
  {ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));c.setTextLayout(true);c.setLearningEnabled(false);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
   for(int n=0;n<120;n++)for(char k:base[n%base.length].toCharArray()){c.press(k==' '?"space":String.valueOf(k));if(n<15||n==44||n==119)dump("key-"+n+"-"+k,c);}
   for(int at:new int[]{0,22,60,120}){c.moveCursorToPreviewBoundary(at);dump("cursor-"+at,c);}c.close();
  }
  for(String reading:new String[]{"ㄉㄨㄛˋㄩˊ","ㄨㄤˊㄒㄧㄠˇㄇㄧㄥˊ","ㄅㄨˋㄒㄧㄥˊ","ㄧˋㄑㄧˇ","ㄊㄧㄢ ㄑㄧˋ","ㄦˊㄢˋ"}){
   {ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));c.setTextLayout(true);c.setLearningEnabled(false);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));for(char k:reading.toCharArray()){c.press(k==' '?"space":String.valueOf(k));dump("fixture-"+reading+"-"+k,c);}c.close(); }
  }
 }
 static void dump(String label,ZhuyinInputController c){StringBuilder s=new StringBuilder(label).append("\t").append(c.previewText()).append("\t").append(c.textPreview()).append("\t").append(c.sentenceKeys()).append("\t").append(c.phoneticSyllables());for(ZhuyinInputController.TextChoice x:c.textChoices())s.append("\t").append(x.kind).append(':').append(x.label).append(':').append(x.start).append(':').append(x.end).append(':').append(x.index);System.out.println(s);}
}
