package com.simon.voiceime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.TextView;

/** A caret drawn between codepoints, without inserting a glyph into the user's text. */
public final class PreviewCursorView extends TextView {
    private int boundary=-1;
    private boolean lit=true;
    private final Runnable blink=new Runnable(){public void run(){lit=!lit;invalidate();postDelayed(this,500);}};
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();lit=true;postDelayed(blink,500);}
    @Override protected void onDetachedFromWindow(){removeCallbacks(blink);super.onDetachedFromWindow();}
    private final Paint caretPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public PreviewCursorView(Context c,AttributeSet attrs){super(c,attrs);caretPaint.setColor(0xffffc857);}
    void setBoundary(int cp){boundary=cp;lit=true;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(!lit||getLayout()==null||getText().length()==0)return;
        String text=getText().toString();int count=text.codePointCount(0,text.length());
        int at=boundary<0?count:Math.min(boundary,count);
        int utf=text.offsetByCodePoints(0,at);
        float x=getTotalPaddingLeft()+getLayout().getPrimaryHorizontal(utf)-getScrollX();
        float top=getExtendedPaddingTop()+getLayout().getLineTop(0);
        float bottom=getExtendedPaddingTop()+getLayout().getLineBottom(0);
        caretPaint.setStrokeWidth(getResources().getDisplayMetrics().density*2);
        caretPaint.setColor(getCurrentTextColor());
        float cap=getResources().getDisplayMetrics().density*3;
        x=Math.max(cap,Math.min(getWidth()-cap,x));
        canvas.drawLine(x,top,x,bottom,caretPaint);
        canvas.drawLine(x-cap,top,x+cap,top,caretPaint);
        canvas.drawLine(x-cap,bottom,x+cap,bottom,caretPaint);
    }
}
