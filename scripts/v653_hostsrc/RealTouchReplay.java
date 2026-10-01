package com.simon.voiceime;
import java.util.*;import java.nio.file.*;
public class RealTouchReplay {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] a)throws Exception{
  for(String line:Files.readAllLines(Path.of(a[2]))){var f=line.split("\t");RecallReplay.geometry.add(new TouchModel.Key("cover",f[0],Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4])));}
  for(String line:Files.readAllLines(Path.of(a[3]))){var f=line.split("\t");Path u=Path.of(a[1],f[0]);Files.createDirectories(u);
   try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],u.toString())){ZhuyinInputController c=new ZhuyinInputController(e);String[] offsets=f[3].split(";");for(int p=0;p<f[1].length();p++){char key=f[1].charAt(p);c.press(RecallReplay.glyph(key));var xy=offsets[p].split(",");RecallReplay.attach(c,key,Double.parseDouble(xy[0]),Double.parseDouble(xy[1]));}
    String originalReading=RecallReplay.raw(e.phoneticText());String old=e.previewText();var state=c.moveCursorToPreviewBoundary(Integer.parseInt(f[2]));String desired=f[0].equals("qier")?"前":"換";int index=state.candidates.indexOf(desired);System.out.println(f[0]+" literal="+old+" rank="+(index+1)+" top5="+state.candidates.subList(0,Math.min(5,state.candidates.size())));
    check(index>=0&&index<3,"real offsets intended reading must be in top3");check(e.previewText().equals(old),"no auto replacement");check(state.candidates.contains(old),"literal option must remain selectable");c.chooseCandidate(index);String expected=f[0].equals("qier")?"接續之前的輸入法":"再切換的方式上";check(e.previewText().equals(expected),"local repair exactly: "+e.previewText());
    String repairedReading=RecallReplay.raw(e.phoneticText());check(originalReading.length()==repairedReading.length(),"key count stays fixed");int changes=0;for(int j=0;j<originalReading.length();j++)if(originalReading.charAt(j)!=repairedReading.charAt(j))changes++;check(changes==1,"single raw key repair only");if(f[0].equals("qier")){state=c.moveCursorToPreviewBoundary(1);index=state.candidates.indexOf("接續");check(index>=0,"regroup 接續");c.chooseCandidate(index);check(e.previewText().equals("接續之前的輸入法"),"regroup keeps fixed 前");}
    System.out.println("PASS "+f[0]+" actual key offsets, literal kept, selection local, regroup prefix");
   }
  }
 }
}
