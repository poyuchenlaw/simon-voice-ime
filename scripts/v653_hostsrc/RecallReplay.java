package com.simon.voiceime;
import java.util.*;import java.nio.file.*;
public class RecallReplay {
 static final String PHY="1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347",GLY="ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙";
 static String glyph(char c){return c==' '?"space":""+GLY.charAt(PHY.indexOf(c));}
 static int physical(String k){return k.equals("space")?32:PHY.charAt(GLY.indexOf(k));}
 static String raw(String glyphs){StringBuilder s=new StringBuilder();for(char c:glyphs.toCharArray()){int i=GLY.indexOf(c);if(i>=0)s.append(PHY.charAt(i));}return s.toString();}
 static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\t","\\t")+"\"";}
 static List<TouchModel.Key> geometry=new ArrayList<>();
 static void attach(ZhuyinInputController controller,char key,double dx,double dy){
  TouchModel.Key pressed=null;for(var k:geometry)if(k.key.equals(glyph(key)))pressed=k;
  if(pressed==null)throw new AssertionError("missing geometry "+key);
  var posterior=new TouchModel(geometry).predict("cover",pressed.x+dx,pressed.y+dy);
  int[] keys=new int[posterior.size()];double[] p=new double[keys.length];boolean[] near=new boolean[keys.length];
  for(int i=0;i<keys.length;i++){var a=posterior.get(i);keys[i]=physical(a.key);p[i]=a.probability;for(var k:geometry)if(k.key.equals(a.key))near[i]=Math.hypot(k.x-pressed.x,k.y-pressed.y)<=1.25*Math.max(Math.max(k.pitchX,k.pitchY),Math.max(pressed.pitchX,pressed.pitchY));}
  controller.recordTouch(keys,p,near);
 }
 public static void main(String[] a)throws Exception{
  for(String line:Files.readAllLines(Path.of(a[2]))){var f=line.split("\t");geometry.add(new TouchModel.Key("cover",f[0],Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4])));}
  for(String line:Files.readAllLines(Path.of(a[3]))){String[] f=line.split("\t",-1);String id=f[0],input=f[1],intended=f[7];int pos=Integer.parseInt(f[2]),from=Integer.parseInt(f[5]),to=Integer.parseInt(f[6]);double dx=Double.parseDouble(f[3]),dy=Double.parseDouble(f[4]);
   Path user=Path.of(a[1],id.replace('/','_'));Files.createDirectories(user);
   try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],user.toString())){ZhuyinInputController c=new ZhuyinInputController(e);StringBuilder early=new StringBuilder();
    for(int i=0;i<input.length();i++){var state=c.press(glyph(input.charAt(i)));early.append(state.commitText);if(i==pos)attach(c,input.charAt(i),dx,dy);}
    String original=e.previewText();var syll=e.phoneticSyllables();String physical="";for(String s:syll)physical+=raw(s);int offset=input.length()-physical.length();
    int boundary=0,sum=offset,best=Integer.MAX_VALUE;for(int i=0;i<=syll.size();i++){int distance=Math.abs(sum-from);if(distance<best){best=distance;boundary=i;}if(i<syll.size())sum+=raw(syll.get(i)).length();}
    long started=System.nanoTime();var state=c.moveCursorToPreviewBoundary(boundary);double elapsed=(System.nanoTime()-started)/1e6;
    String[] readings=e.regroupReadings();int rank=-1;String expected=(from-offset)+":"+(to-offset)+":"+intended;
    if(from>=offset)for(int i=0;i<readings.length;i++)if(expected.equals(readings[i])){rank=i+1;break;}
    if(!original.equals(e.previewText()))throw new AssertionError("cursor changed literal "+id);
    boolean local=true;String selected="";if(rank>0){selected=state.candidates.get(rank-1);c.chooseCandidate(rank-1);String before=raw(String.join("",syll));String after=raw(e.phoneticText());String target=before.substring(0,from-offset)+intended+before.substring(to-offset);local=after.equals(target);}
    System.out.println("{\"id\":"+q(id)+",\"slot\":"+q(f[8])+",\"rank\":"+rank+",\"cursor_ms\":"+elapsed+",\"options\":"+readings.length+",\"boundary\":"+boundary+",\"early_commit\":"+q(early.toString())+",\"literal\":"+q(original)+",\"chosen\":"+q(selected)+",\"local_only\":"+local+"}");
   }
  }
 }
}
