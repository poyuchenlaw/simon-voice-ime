package com.simon.voiceime;
import junit.framework.TestCase;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import android.text.Spanned;
import android.text.style.MetricAffectingSpan;
/** Compiled by A; execution is reserved for the locked F/X1 phase. */
public class App690AVisualContractTest extends TestCase {
 private android.app.Instrumentation getInstrumentation(){return androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();}
 public void testFactoryHighlightMatchesBaselineWithoutDiamond(){
  for(boolean dark:new boolean[]{false,true}){
   float density=getInstrumentation().getTargetContext().getResources().getDisplayMetrics().density;
   Bitmap expected=Bitmap.createBitmap(200,160,Bitmap.Config.ARGB_8888),actual=Bitmap.createBitmap(200,160,Bitmap.Config.ARGB_8888);
   Canvas c=new Canvas(expected);int color=DualHandVisuals.color(dark,"left");GradientDrawable baseline=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{color,color});baseline.setCornerRadius(Math.round(5*density));baseline.setBounds(0,0,200,160);baseline.draw(c);
   Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);ink.setColor(dark?0x40FFFFFF:0xB0FFFFFF);ink.setStrokeWidth(Math.round(.5f*density));c.drawLine(Math.round(5*density),Math.round(.5f*density),200-Math.round(5*density),Math.round(.5f*density),ink);
   KeyboardKeycaps.Cap cap=KeyboardKeycaps.create(dark,"left",density,0);cap.setBounds(0,0,200,160);cap.draw(new Canvas(actual));
   for(int y=0;y<160;y++)for(int x=0;x<200;x++)assertEquals("pixel "+x+","+y,expected.getPixel(x,y),actual.getPixel(x,y));
   Rect padding=new Rect();assertFalse(cap.getPadding(padding));assertEquals(0,padding.left);expected.recycle();actual.recycle();
  }
 }
 public void testFontOnceMixedSpanAndOpenContent(){
  var c=getInstrumentation().getTargetContext();Typeface bundled=KeyboardTypeface.get(c);for(int i=0;i<30;i++)assertSame(bundled,KeyboardTypeface.get(c));assertEquals(1,KeyboardTypeface.loadCount());
  assertSame(Typeface.DEFAULT,KeyboardTypeface.forLabel(c,"𪚥自訂資料夾",false));assertFalse(KeyboardTypeface.covered(c,"𪚥"));
  Spanned mixed=(Spanned)KeyboardTypeface.mixed(c,"漢ㄅˊ字");assertEquals(2,mixed.getSpans(0,mixed.length(),MetricAffectingSpan.class).length);
 }
 public void testFactoryMarkerCandidateAndGridSizes(){
  getInstrumentation().runOnMainSync(()->{
   var c=getInstrumentation().getTargetContext();TextView v=new TextView(c);KeyboardKeycaps.apply(v,true,"zero",1);assertEquals(KeyboardKeycaps.FACTORY,v.getTag(R.id.keyboard_keycap_factory));assertEquals(Math.round(1.5f*c.getResources().getDisplayMetrics().density),v.getElevation(),0f);
   KeyboardCandidates.apply(v,true,true);assertSame(Typeface.DEFAULT,v.getTypeface());assertEquals(KeyboardCandidates.CANDIDATE_TEXT_SP,v.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity,.01f);
   v.setText("三字詞");KeyboardCandidates.applyGrid(v,true,false);assertEquals(18,v.getTextSize()/c.getResources().getDisplayMetrics().scaledDensity,.01f);assertTrue(v.getBackground() instanceof KeyboardKeycaps.Cap);
  });
 }
}
