package com.simon.voiceime;
import java.nio.file.*;import java.util.*;import org.json.*;
/** Measures real standalone-initial readings followed by vowels, without inventing words. */
public final class ShortcutTelemetryHostTest {
 public static void main(String[] a)throws Exception{
  Set<String> runs=new LinkedHashSet<>();for(String l:Files.readAllLines(Paths.get(a[2])))runs.add(new JSONObject(l).getString("keys"));
  int aligned=0;try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){ZhuyinInputController c=new ZhuyinInputController(e);c.setTextLayout(true);c.setLearningEnabled(false);
   for(String keys:runs){c.clear();for(int n=0;n<keys.length();n++){c.press(keys.charAt(n)==' '?"space":keys.substring(n,n+1));String physical=keys.substring(0,n+1);List<String> reading=c.phoneticSyllables();if(!String.join("",reading).replace('ˉ',' ').equals(physical))continue;aligned++;
    int offset=0;JSONArray standalone=new JSONArray();for(int i=0;i+1<reading.size();i++){String s=reading.get(i),next=reading.get(i+1);if(s.length()==1&&"ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ".contains(s)&&!next.isEmpty()&&"ㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ".indexOf(next.charAt(0))>=0)standalone.put(offset);offset+=s.length();}
    System.out.println(new JSONObject().put("prefix",physical).put("standalone",standalone).put("readings",new JSONArray(reading)).put("preview",c.textPreview()).toString());
   }}
  }System.err.println("runs="+runs.size()+" aligned_prefixes="+aligned);
 }
}
