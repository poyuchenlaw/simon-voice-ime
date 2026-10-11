package com.simon.voiceime;
import android.graphics.*;
import android.text.style.ReplacementSpan;
/** Keep the accepted glyph, with a dashed pronunciation reminder. */
final class DualHandWarningSpan extends ReplacementSpan {
    @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){if(metrics!=null)paint.getFontMetricsInt(metrics);return (int)Math.ceil(paint.measureText(text,start,end));}
    @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,Paint paint){
        Paint glyph=new Paint(paint);glyph.setUnderlineText(false);canvas.drawText(text,start,end,x,y,glyph);
        float density=paint.getTextSize()/18f,width=paint.measureText(text,start,end);
        glyph.setStyle(Paint.Style.STROKE);glyph.setStrokeWidth(Math.max(1,density));glyph.setPathEffect(new DashPathEffect(new float[]{3*density,2*density},0));
        canvas.drawLine(x,y+Math.max(2,paint.descent()/2),x+width,y+Math.max(2,paint.descent()/2),glyph);
    }
}
