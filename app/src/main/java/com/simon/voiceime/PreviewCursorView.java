package com.simon.voiceime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.TextView;

/** A caret drawn between codepoints, without inserting a glyph into the user's text. */
public final class PreviewCursorView extends TextView {
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){boolean consumed=super.dispatchTouchEvent(e);KeyboardTouchLayout.received(this,e,consumed,false);return consumed;}
    @Override public boolean performClick(){boolean clicked=super.performClick();KeyboardTouchLayout.received(this,null,clicked,clicked);return clicked;}

    // A transient unparsed suffix can grow then shrink the text by one glyph.
    // Preserve the active composition's scroll range; empty text starts a new one.
    private int compositionWidth;
    @Override protected void onTextChanged(CharSequence text,int start,int before,int count){
        super.onTextChanged(text,start,before,count);
        if(text.length()==0)compositionWidth=0;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        super.onMeasure(widthSpec,heightSpec);
        if(getId()!=R.id.boStreamPreview||getText().length()==0||!(getParent() instanceof android.widget.HorizontalScrollView))return;
        compositionWidth=Math.max(compositionWidth,getMeasuredWidth());
        setMeasuredDimension(compositionWidth,getMeasuredHeight());
    }
    private int boundary=-1;
    private boolean lit=true,dragging=false;
    private int activeStart=-1,activeEnd=-1;
    void setDragging(boolean value){dragging=value;lit=true;invalidate();}
    void setActiveSpan(int start,int end){activeStart=start;activeEnd=end;invalidate();}
    private final Runnable blink=new Runnable(){public void run(){lit=!lit;invalidate();postDelayed(this,500);}};
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();lit=true;postDelayed(blink,500);if(getId()==R.id.boStreamPreview&&getParent() instanceof android.view.View){viewport=(android.view.View)getParent();viewport.addOnLayoutChangeListener(viewportLayout);post(this::fitPreview);}}
    @Override protected void onDetachedFromWindow(){removeCallbacks(blink);if(viewport!=null)viewport.removeOnLayoutChangeListener(viewportLayout);viewport=null;super.onDetachedFromWindow();}
    private android.view.View viewport;
    private final android.view.View.OnLayoutChangeListener viewportLayout=(v,l,t,r,b,ol,ot,or,ob)->fitPreview();
    private void fitPreview(){
        if(getId()!=R.id.boStreamPreview||viewport==null||viewport.getWidth()<=0)return;
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        float available=viewport.getWidth()-getTotalPaddingLeft()-getTotalPaddingRight()-10*metrics.density;
        // Measure from an immutable reference, not the previous fitted size.
        // Hinted CJK advances round to pixels; feeding the last size back into
        // the ratio can alternate across that rounding boundary every layout.
        float referenceSize=18*metrics.scaledDensity;
        Paint reference=new Paint(getPaint());reference.setTextSize(referenceSize);
        float glyph=reference.measureText("國");if(glyph<=0||available<=0)return;
        float size=Math.max(14*metrics.scaledDensity,Math.min(referenceSize,referenceSize*available/(17*glyph)));
        if(Math.abs(size-getTextSize())>.25f)setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,size);
    }
    private final Paint caretPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public PreviewCursorView(Context c,AttributeSet attrs){super(c,attrs);caretPaint.setColor(0xffffc857);}
    void setBoundary(int cp){boundary=cp;lit=true;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(getLayout()==null||getText().length()==0)return;
        String text=getText().toString();int count=text.codePointCount(0,text.length());
        int at=boundary<0?count:Math.min(boundary,count);
        int utf=text.offsetByCodePoints(0,at);
        float x=getTotalPaddingLeft()+getLayout().getPrimaryHorizontal(utf)-getScrollX();
        int line=getLayout().getLineForOffset(utf);
        float top=getExtendedPaddingTop()+getLayout().getLineTop(line);
        float bottom=getExtendedPaddingTop()+getLayout().getLineBottom(line);
        caretPaint.setStrokeWidth(getResources().getDisplayMetrics().density*(dragging?4:3));
        caretPaint.setColor(getCurrentTextColor());
        float cap=getResources().getDisplayMetrics().density*5;
        if(activeStart>=0&&activeEnd>activeStart&&activeEnd<=count){
            int from=text.offsetByCodePoints(0,activeStart),to=text.offsetByCodePoints(0,activeEnd);
            for(int n=getLayout().getLineForOffset(from);n<=getLayout().getLineForOffset(to);n++){
                int a=Math.max(from,getLayout().getLineStart(n)),b=Math.min(to,getLayout().getLineEnd(n));if(a>=b)continue;
                float left=getTotalPaddingLeft()+getLayout().getPrimaryHorizontal(a)-getScrollX();
                float right=getTotalPaddingLeft()+(b==getLayout().getLineEnd(n)?getLayout().getLineRight(n):getLayout().getPrimaryHorizontal(b))-getScrollX();
                float y=getExtendedPaddingTop()+getLayout().getLineBottom(n)+2;
                canvas.drawLine(left,y,right,y,caretPaint);
            }
        }
        if(!lit&&!dragging)return;
        x=Math.max(cap,Math.min(getWidth()-cap,x));
        canvas.drawLine(x,top,x,bottom,caretPaint);
        canvas.drawLine(x-cap,top,x+cap,top,caretPaint);
        canvas.drawLine(x-cap,bottom,x+cap,bottom,caretPaint);
        caretPaint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(x,Math.min(getHeight()-cap,bottom+cap),cap,caretPaint);
        caretPaint.setStyle(Paint.Style.FILL);
    }
}
