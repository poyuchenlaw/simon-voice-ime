package com.simon.voiceime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.TextView;

/** A caret drawn between codepoints, without inserting a glyph into the user's text. */
public final class PreviewCursorView extends TextView {
    private int boundary=-1;
    private final Paint caretPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public PreviewCursorView(Context c,AttributeSet attrs){super(c,attrs);caretPaint.setColor(0xffffc857);}
    void setBoundary(int cp){boundary=cp;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(boundary<0||getLayout()==null)return;
        String text=getText().toString();int count=text.codePointCount(0,text.length());
        if(boundary>count)return;
        int utf=text.offsetByCodePoints(0,boundary);
        float x=getTotalPaddingLeft()+getLayout().getPrimaryHorizontal(utf)-getScrollX();
        float top=getTotalPaddingTop()+getLayout().getLineTop(0);
        float bottom=getTotalPaddingTop()+getLayout().getLineBottom(0);
        caretPaint.setStrokeWidth(getResources().getDisplayMetrics().density*2);
        canvas.drawLine(x,top,x,bottom,caretPaint);
    }
}
