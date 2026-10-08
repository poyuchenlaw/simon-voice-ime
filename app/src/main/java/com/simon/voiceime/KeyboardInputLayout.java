package com.simon.voiceime;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;
/** Observe key touches before child listeners consume them. */
public final class KeyboardInputLayout extends LinearLayout {
 java.util.function.Consumer<MotionEvent> beforeTouch;
 public KeyboardInputLayout(Context context,AttributeSet attrs){super(context,attrs);}
 @Override public boolean dispatchTouchEvent(MotionEvent event){
  if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&beforeTouch!=null)beforeTouch.accept(event);
  return super.dispatchTouchEvent(event);
 }
}
