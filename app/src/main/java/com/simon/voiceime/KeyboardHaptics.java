package com.simon.voiceime;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
/** Explicit touch and framework long-press use the same two preference gates. */
public final class KeyboardHaptics {
    private static int requests,calls,emitted,visits;
    private KeyboardHaptics(){}
    public static boolean enabled(Context c){SharedPreferences p=c.getSharedPreferences("simon_ime_prefs",0);return p.getBoolean("haptic_enabled",true)&&p.getBoolean("dual_hand_haptics",true);}
    public static boolean perform(View v,int kind){requests++;if(!enabled(v.getContext()))return false;calls++;boolean accepted=v.performHapticFeedback(kind);if(accepted)emitted++;return accepted;}
    public static void press(View v){perform(v,HapticFeedbackConstants.KEYBOARD_TAP);}
    public static void lock(View v){perform(v,HapticFeedbackConstants.CLOCK_TICK);}
    public static void reject(View v){perform(v,Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.REJECT:HapticFeedbackConstants.LONG_PRESS);}
    public static void sync(Context c,View root){
        if(root==null)return;boolean gate=enabled(c);if(Boolean.valueOf(gate).equals(root.getTag(R.id.keyboard_haptic_gate)))return;
        visits++;tree(root,gate);root.setTag(R.id.keyboard_haptic_gate,gate);
    }
    private static void tree(View v,boolean enabled){v.setHapticFeedbackEnabled(enabled);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)tree(g.getChildAt(i),enabled);}}
    public static int requestCount(){return requests;}public static int callCount(){return calls;}public static int emittedCount(){return emitted;}public static int syncCount(){return visits;}
}
