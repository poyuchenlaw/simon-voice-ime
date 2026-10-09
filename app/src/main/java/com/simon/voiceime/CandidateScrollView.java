package com.simon.voiceime;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;
public final class CandidateScrollView extends HorizontalScrollView {
    public CandidateScrollView(Context c,AttributeSet a){super(c,a);}
    @Override public boolean dispatchTouchEvent(MotionEvent e){boolean consumed=super.dispatchTouchEvent(e);KeyboardTouchLayout.received(this,e,consumed,false);return consumed;}
}
