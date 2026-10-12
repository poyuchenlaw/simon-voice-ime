package com.simon.voiceime;
import org.junit.Test;import static org.junit.Assert.*;import java.util.*;
public class App690APaletteTest {
 static double linear(int x){double c=x/255.;return c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4);}
 static double lum(int c){return .2126*linear(c>>16&255)+.7152*linear(c>>8&255)+.0722*linear(c&255);}
 static double contrast(int a,int b){double x=lum(a),y=lum(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);}
 static float[] hsv(int c){return java.awt.Color.RGBtoHSB(c>>16&255,c>>8&255,c&255,null);}
 static void threshold(double actual,double required){assertTrue(actual+" < "+required,actual>=required);}
 static void modes(int[] colors,int tools){
  for(int i=0;i<4;i++){threshold(contrast(colors[i],tools),4.5);threshold(hsv(colors[i])[1],.4);for(int j=0;j<i;j++){double d=Math.abs(hsv(colors[i])[0]-hsv(colors[j])[0])*360;threshold(Math.min(d,360-d),30);}}
 }
 static int c(boolean dark,String role){return DualHandVisuals.color(dark,role);}
 @Test public void a2_1_actualModeRolesAndRecording(){for(boolean dark:new boolean[]{true,false}){
  int[] modes={c(dark,"mode_append"),c(dark,"mode_replace"),c(dark,"mode_spell"),c(dark,"mode_translate")};modes(modes,c(dark,"tools"));
  threshold(contrast(c(dark,"recording"),c(dark,"core")),3);threshold(contrast(c(dark,"recording"),c(dark,"background")),3);threshold(contrast(c(dark,"text"),c(dark,"recording")),3);
 }}
 @Test public void a2_1_checkerRejectsEveryInjectedViolation(){
  for(double[] pair:new double[][]{{4.49,4.5},{2.99,3},{2.99,3},{2.99,3},{29.9,30},{.39,.4}}){boolean rejected=false;try{threshold(pair[0],pair[1]);}catch(AssertionError expected){rejected=true;}assertTrue(Arrays.toString(pair),rejected);}
  for(boolean dark:new boolean[]{true,false})modes(dark?new int[]{0xFF4ECCA3,0xFFF87171,0xFFF0A500,0xFF6BC5F0}:new int[]{0xFF08745B,0xFFB42318,0xFF8A5300,0xFF0369A1},c(dark,"tools"));
  boolean rejected=false;try{modes(new int[]{0xFF4ECCA3,0xFF4ECCA3,0xFFF0A500,0xFF6BC5F0},c(true,"tools"));}catch(AssertionError expected){rejected=true;}assertTrue(rejected);
 }
 @Test public void a2_3_allUsedTextPairsAndTenBaselineRoles(){
  String[] roles={"left","right","zero","core","tools","text","secondary","hint","border","highlight"};
  int[][] base={{0xFF161B26,0xFF1C2230,0xFF1E293B,0xFF252D3D,0xFF1A202C,0xFFFFFFFF,0xFF94A3B8,0xFF38BDF8,0xFF6366F1,0xFF48546A},{0xFFFFFFFF,0xFFFFFFFF,0xFFEEF2F6,0xFFE0E2EC,0xFFF8F9FA,0xFF1D2939,0xFF586174,0xFF0369A1,0xFF94A3B8,0xFFE0F2FE}};
  double min=100;String location="";
  for(int i=0;i<2;i++){boolean dark=i==0;for(int j=0;j<roles.length;j++)assertEquals(roles[j],base[i][j],c(dark,roles[j]));
   for(String bg:new String[]{"left","right","zero","core","tools"})for(String fg:new String[]{"text","secondary","hint","accent"}){double ratio=contrast(c(dark,fg),c(dark,bg));threshold(ratio,4.5);if(ratio<min){min=ratio;location=dark+" "+fg+"/"+bg;}}
   threshold(contrast(c(dark,"text"),c(dark,"highlight")),4.5);
   for(String bg:new String[]{"action_positive_bg","action_negative_bg"})threshold(contrast(c(dark,"text"),c(dark,bg)),4.5);
   threshold(contrast(c(dark,"destructive"),c(dark,"background")),4.5);
  }System.out.println("A2-3 minimum="+min+" at "+location);
 }
}
