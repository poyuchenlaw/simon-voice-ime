package com.simon.voiceime;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowInsets;
/** Root gesture inset once; three-button navigation reports zero gesture inset. */
public final class KeyboardInsets {
    private KeyboardInsets(){}
    public static int bottom(WindowInsets in){return in==null?0:Build.VERSION.SDK_INT>=30?in.getInsets(WindowInsets.Type.systemGestures()).bottom:Build.VERSION.SDK_INT>=29?in.getSystemGestureInsets().bottom:0;}
    public static boolean ownedByParent(View v){for(ViewParent p=v.getParent();p!=null;p=p.getParent())if(p instanceof KeyboardInputLayout)return true;return false;}
}
