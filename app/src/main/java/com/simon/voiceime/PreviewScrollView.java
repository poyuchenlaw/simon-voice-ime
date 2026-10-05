package com.simon.voiceime;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

/** Width-constrained text, growing upward with the IME window to at most three lines. */
public final class PreviewScrollView extends ScrollView {
    private int typingViewportHeight;
    private float measuredFont=-1;
    private int measuredWidth=-1;
    public PreviewScrollView(Context context,AttributeSet attrs){super(context,attrs);setFillViewport(false);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        super.onMeasure(widthSpec,heightSpec);
        if(getChildCount()==0||!(getChildAt(0) instanceof TextView))return;
        TextView text=(TextView)getChildAt(0);android.text.Layout layout=text.getLayout();
        if(layout==null)return;
        int visible=Math.min(3,layout.getLineCount());
        // An empty Latin line is shorter than the first CJK glyph at large
        // scales. Reserve that first glyph's real fallback-font footprint
        // for empty and provisional text, so the first character cannot resize the viewport
        // after the IME window has already measured the empty line.
        int firstLine=0;
        {
            android.text.StaticLayout.Builder sample=android.text.StaticLayout.Builder.obtain("國",0,1,text.getPaint(),Math.max(1,text.getMeasuredWidth()-text.getTotalPaddingLeft()-text.getTotalPaddingRight()))
                .setIncludePad(text.getIncludeFontPadding()).setLineSpacing(text.getLineSpacingExtra(),text.getLineSpacingMultiplier());
            if(android.os.Build.VERSION.SDK_INT>=28)sample.setUseLineSpacingFromFallbacks(text.isFallbackLineSpacing());
            firstLine=sample.build().getLineBottom(0)+text.getTotalPaddingTop()+text.getTotalPaddingBottom();
        }
        int desired=Math.max(getSuggestedMinimumHeight(),layout.getLineBottom(visible-1)+text.getTotalPaddingTop()+text.getTotalPaddingBottom());
        desired=Math.max(desired,firstLine);
        // A provisional character may wrap, then merge into the preceding
        // syllable when its tone arrives. Keep the grown viewport until the
        // composition clears: shrinking/reopening its window between two taps
        // can leave InputDispatcher one layout behind the drawn keys.
        if(text.length()==0||measuredFont!=text.getTextSize()||measuredWidth!=text.getMeasuredWidth())typingViewportHeight=0;
        measuredFont=text.getTextSize();measuredWidth=text.getMeasuredWidth();
        typingViewportHeight=Math.max(typingViewportHeight,desired);desired=typingViewportHeight;
        // The child keeps its full height; only its viewport is capped.
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(resolveSize(desired,heightSpec),MeasureSpec.EXACTLY));
    }
    void revealBoundary(int codePoint){
        if(getChildCount()==0||!(getChildAt(0) instanceof TextView))return;
        TextView text=(TextView)getChildAt(0);android.text.Layout layout=text.getLayout();if(layout==null)return;
        String value=text.getText().toString();int count=value.codePointCount(0,value.length());
        int utf=value.offsetByCodePoints(0,Math.max(0,Math.min(count,codePoint)));
        int line=layout.getLineForOffset(utf),top=text.getTotalPaddingTop()+layout.getLineTop(line),bottom=text.getTotalPaddingTop()+layout.getLineBottom(line);
        int y=getScrollY(),handle=Math.round(5*getResources().getDisplayMetrics().density);
        if(top<y)y=top;
        else if(bottom+handle>y+getHeight())y=bottom+handle-getHeight();
        scrollTo(0,Math.max(0,y));
    }
}
