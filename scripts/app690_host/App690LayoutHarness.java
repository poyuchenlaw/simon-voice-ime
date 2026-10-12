package com.simon.voiceime;
import java.util.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;
/** Shared A fitting implementation, independent XML geometry and AWT font measurements. */
public final class App690LayoutHarness {
 public static void main(String[] args)throws Exception {
  Map<String,List<String[]>> groups=new LinkedHashMap<>();for(String row:Files.readAllLines(Path.of(args[0]))){String[] r=row.split("\t",-1);groups.computeIfAbsent(r[0],k->new ArrayList<>()).add(r);}
  int cases=0,violations=0;for(Map.Entry<String,List<String[]>> entry:groups.entrySet()){
   String[] env=entry.getKey().split(":");float d=Float.parseFloat(env[2]),scale=Float.parseFloat(env[3]);App688Harness.android.graphics.Paint paint=new App688Harness.android.graphics.Paint();List<App688Harness.KeyboardLabels.Label> labels=new ArrayList<>();
   for(String[] r:entry.getValue())if(r[2].equals("character")){String text=new String(Base64.getDecoder().decode(r[1]),StandardCharsets.UTF_8);labels.add(new App688Harness.KeyboardLabels.Label(Float.parseFloat(r[3])-2*Math.round(Float.parseFloat(r[5])*d)-2*Math.round(2*d),Float.parseFloat(r[4])-2*Math.round(d),0,text,"","","",""));}
   float fitted=labels.isEmpty()?0:App688Harness.KeyboardLabels.fit(paint,d*scale,labels);assert labels.isEmpty()||fitted>=12:entry.getKey();float minimum=Float.POSITIVE_INFINITY;
   for(String[] r:entry.getValue()){
    String text=new String(Base64.getDecoder().decode(r[1]),StandardCharsets.UTF_8);String cls=r[2];if(cls.equals("icon"))continue;if(cls.equals("function"))text=App688Harness.KeyboardLabels.functionLabel(text,"tap",false);paint.setTextSize((cls.equals("character")?fitted:12)*d*scale);App688Harness.Rect bounds=new App688Harness.Rect();paint.getTextBounds(text,0,text.length(),bounds);float w=Float.parseFloat(r[3])-2*Math.round(Float.parseFloat(r[5])*d),h=Float.parseFloat(r[4])-2*Math.round(Float.parseFloat(r[5])*d);
    if(bounds.width()>w+.01||bounds.height()>h+.01){violations++;System.out.println("R4_OVERFLOW "+entry.getKey()+" label "+text+" ink "+bounds.width()+"x"+bounds.height()+" cap "+w+"x"+h);}
    minimum=Math.min(minimum,Math.min(w-bounds.width(),h-bounds.height())/d);
    if(env[0].equals("bopomofo")&&env[1].equals("320")&&scale==1.3f&&text.equals("123"))System.out.println("A2-4 123 "+entry.getKey()+" margin_dp="+(w-bounds.width())/d);
   }
   cases++;System.out.println("R4_GRID "+entry.getKey()+" character_sp="+fitted+" function_sp=12 min_margin_dp="+minimum);
  }
  assert violations==0:"R4 overflow rows="+violations;assert cases==200:"four XML pages x50 cells";System.out.println("R4-a shared fit CROSSCHECK PASS "+cases+" grid cells; AWT host, Android ink remains device evidence");
 }
}
