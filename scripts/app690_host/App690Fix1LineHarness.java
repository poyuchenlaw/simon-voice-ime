package com.simon.voiceime;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** XML key sizes and TTF metrics are independent of the product fit decision. */
public class App690Fix1LineHarness {
 public static void main(String[] args)throws Exception {
  Map<String,List<String[]>> groups=new LinkedHashMap<>();
  for(String line:Files.readAllLines(Path.of(args[0]))){String[] row=line.split("\t",-1);if(row[2].equals("character"))groups.computeIfAbsent(row[0],k->new ArrayList<>()).add(row);}
  int bad=0,cases=0;float ascent=Float.parseFloat(args[3]),descent=Float.parseFloat(args[4]);
  for(Map.Entry<String,List<String[]>> entry:groups.entrySet()){
   String[] env=entry.getKey().split(":");float density=Float.parseFloat(env[2]),scale=Float.parseFloat(env[3]);
   App688Harness.android.graphics.Paint paint=new App688Harness.android.graphics.Paint();List<App688Harness.KeyboardLabels.Label> labels=new ArrayList<>();
   for(String[] row:entry.getValue()){String text=new String(Base64.getDecoder().decode(row[1]),StandardCharsets.UTF_8);labels.add(new App688Harness.KeyboardLabels.Label(Float.parseFloat(row[3])-2*Math.round(density)-2*Math.round(2*density),Float.parseFloat(row[4]),Math.round(density),-1,.2f,text,"","","",""));}
   float sp=App688Harness.KeyboardLabels.fit(paint,density*scale,labels);paint.setTextSize(sp*density*scale);
   boolean padding=Boolean.parseBoolean(env[0].equals("bopomofo")?args[1]:args[2]);
   float top=padding?ascent:1,bottom=padding?descent:.2f;
   for(String[] row:entry.getValue()){
    String text=new String(Base64.getDecoder().decode(row[1]),StandardCharsets.UTF_8);App688Harness.Rect ink=new App688Harness.Rect();paint.getTextBounds(text,0,text.length(),ink);
    float h=Float.parseFloat(row[4]),px=sp*density*scale;int above=(int)Math.ceil(top*px),below=(int)Math.ceil(bottom*px),baseline=above+Math.max(0,((int)h-above-below)/2);
    float visibleTop=baseline+ink.top,visibleBottom=baseline+ink.bottom;
    if(visibleTop<Math.round(density)||visibleBottom>h-Math.round(density)){bad++;System.out.println("LINE_CLIPPED "+entry.getKey()+" "+text+" key_h="+h+" baseline="+baseline+" ink_y="+visibleTop+":"+visibleBottom+" font_padding="+padding);}
   }
   System.out.println("LINE_GRID "+entry.getKey()+" sp="+sp+" font_padding="+padding);cases++;
  }
  assert cases==150:"three pages x50";assert bad==0:"actual line clipping="+bad;System.out.println("FIX1 real XML row height/TTF baseline: 150 grids, 0 clipping");
 }
}
