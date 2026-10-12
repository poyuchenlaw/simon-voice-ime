package com.simon.voiceime;
import android.view.*;
import android.widget.TextView;
import android.text.Spanned;
import android.text.style.MetricAffectingSpan;
import junit.framework.TestCase;
/** Compile here; F/X1 executes the actual Android font, line and span checks. */
public class App690BVisualContractTest extends TestCase {
 public void testInflatedKeysAndMixedPreview()throws Exception {
  var instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
  instrument.runOnMainSync(()->{
   try {
    var context=instrument.getTargetContext();SimonIMEService service=new SimonIMEService();
    var attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",android.content.Context.class);attach.setAccessible(true);attach.invoke(service,context);
    ViewGroup page=(ViewGroup)LayoutInflater.from(context).inflate(R.layout.keyboard_bopomofo,null,false);
    var field=SimonIMEService.class.getDeclaredField("bopomofoKeyboard");field.setAccessible(true);field.set(service,page);
    var style=SimonIMEService.class.getDeclaredMethod("configureBopomofoStyle");style.setAccessible(true);style.invoke(service);
    KeyboardTheme.apply(context,page);
    float density=context.getResources().getDisplayMetrics().density;
    page.measure(View.MeasureSpec.makeMeasureSpec(Math.round(400*density),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));page.layout(0,0,page.getMeasuredWidth(),page.getMeasuredHeight());
    int count=0;Float letterSize=null;
    for(int row=0;row<page.getChildCount();row++)if(page.getChildAt(row) instanceof android.widget.LinearLayout){
     ViewGroup keys=(ViewGroup)page.getChildAt(row);
     for(int col=0;col<keys.getChildCount();col++){
      View v=keys.getChildAt(col);Object route=v.getTag();if(!(v instanceof TextView)||!(route instanceof String)||!((String)route).startsWith("key:"))continue;
      TextView key=(TextView)v;String kind=(String)key.getTag(R.id.keyboard_content_kind);count++;
      assertTrue(key.getBackground() instanceof KeyboardKeycaps.Cap);assertEquals(1,key.getLineCount());assertNull(key.getEllipsize());
      if(!"icon".equals(kind))assertTrue("font "+key.getText(),KeyboardTypeface.bundled(key.getTypeface()));
      float sp=key.getTextSize()/context.getResources().getDisplayMetrics().scaledDensity;
      if("character".equals(kind)){if(letterSize==null)letterSize=sp;assertEquals(letterSize,sp,.01f);assertTrue(sp>=12);}
      if("function".equals(kind))assertEquals(12,sp,.01f);
     }
    }
    assertEquals(49,count);
    for(int id:new int[]{R.id.boStreamPreview,R.id.boPhoneticPreview}){
     TextView preview=page.findViewById(id);preview.setText("漢ㄅˊ𪚥");assertSame(android.graphics.Typeface.DEFAULT,preview.getTypeface());
     Spanned text=(Spanned)preview.getText();MetricAffectingSpan[] spans=text.getSpans(0,text.length(),MetricAffectingSpan.class);assertEquals(2,spans.length);
     for(MetricAffectingSpan span:spans){assertTrue(text.getSpanStart(span)>=1);assertTrue(text.getSpanEnd(span)<=3);}
    }
   }catch(Exception failure){throw new RuntimeException(failure);}
  });
 }
}
