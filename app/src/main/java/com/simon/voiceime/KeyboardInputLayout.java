package com.simon.voiceime;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;
/** Observe key touches before child listeners consume them. */
public final class KeyboardInputLayout extends LinearLayout {
 java.util.function.Consumer<MotionEvent> beforeTouch;
 private int baselineBottom=-1,lastInset=-1;
 public KeyboardInputLayout(Context context,AttributeSet attrs){super(context,attrs);addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->applyInsets(v.getRootWindowInsets()));setOnApplyWindowInsetsListener((v,in)->{applyInsets(in);return in;});}
 private void applyInsets(android.view.WindowInsets in){
  if(in==null)return;if(baselineBottom<0)baselineBottom=getPaddingBottom();int inset=KeyboardInsets.bottom(in);
  // A full-height input already ends above navigation; padding would clip its page.
  int available=getRootView().getHeight()-in.getSystemWindowInsetBottom();
  if(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE&&getHeight()>0&&available>0&&getHeight()>=available)inset=0;
  if(lastInset!=inset){lastInset=inset;setPadding(getPaddingLeft(),getPaddingTop(),getPaddingRight(),baselineBottom+inset);}
 }
 @Override public boolean dispatchTouchEvent(MotionEvent event){
  if(event.getActionMasked()==MotionEvent.ACTION_DOWN&&beforeTouch!=null)beforeTouch.accept(event);
  return super.dispatchTouchEvent(event);
 }
}
