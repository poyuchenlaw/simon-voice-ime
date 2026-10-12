package com.simon.voiceime;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.TextView;

/** The selected palette is resolved again when the keyboard is shown. */
public final class KeyboardTheme {
    private KeyboardTheme() {}
    public static boolean isDark(String mode,boolean system) {
        return "dark".equals(mode) || !"light".equals(mode) && system;
    }
    public static int color(String mode,boolean system,String role) {
        boolean dark=isDark(mode,system);
        switch(role){
            case "key":return dark?0xFF16213E:0xFFFFFFFF;
            case "text":return dark?0xFFE0E0E0:0xFF1D2939;
            case "border":return dark?0xFF7288AB:0xFFD0D5DD;
            case "pressed":return dark?0xFF354E78:0xFFE4E7EC;
            case "accent":return dark?0xFF4ECCA3:0xFF08745B;
            default:return dark?0xFF1A1A2E:0xFFF2F4F7;
        }
    }
    private static int color(Context context,String role){
        boolean system=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        return color(context.getSharedPreferences("simon_ime_prefs",0).getString("keyboard_theme","system"),system,role);
    }
    public static int resourceColor(Context context,int resource){
        if(resource==R.color.keyboard_bg)return color(context,"background");
        if(resource==R.color.keyboard_key_bg||resource==R.color.key_bg)return color(context,"key");
        if(resource==R.color.keyboard_key_border)return color(context,"border");
        if(resource==R.color.keyboard_key_pressed)return color(context,"pressed");
        if(resource==R.color.mode_append)return color(context,"accent");
        return color(context,"text");
    }
    private static GradientDrawable key(Context c,boolean pressed){
        float density=c.getResources().getDisplayMetrics().density;
        GradientDrawable shape=new GradientDrawable();shape.setColor(color(c,pressed?"pressed":"key"));shape.setCornerRadius(5*density);shape.setStroke(Math.max(1,Math.round(density)),color(c,"border"));return shape;
    }
    public static void apply(Context c,View view){
        if(view==null)return;
        Drawable background=view.getBackground();Object tag=view.getTag();
        boolean key=tag instanceof String&&(((String)tag).startsWith("dh-key:")||((String)tag).startsWith("key:"));
        if(key||background instanceof InsetDrawable){
            StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},key(c,true));states.addState(new int[]{},key(c,false));
            int inset=Math.round(c.getResources().getDisplayMetrics().density);view.setBackground(new InsetDrawable(states,inset));
            boolean light=color(c,"key")==0xFFFFFFFF;view.setElevation(light?c.getResources().getDisplayMetrics().density:0);
        }else if(background instanceof ColorDrawable){
            int value=((ColorDrawable)background).getColor();
            if(value==0xFF16213E||value==0xFFFFFFFF)view.setBackgroundColor(color(c,"key"));
            else if(value==0xFF1A1A2E||value==0xFF111122||value==0xFF0D0D1A||value==0xFF0A1020||value==0xFFF2F4F7)view.setBackgroundColor(color(c,"background"));
        }
        if(view instanceof TextView){
            TextView text=(TextView)view;int value=text.getCurrentTextColor();
            if(value==0xFF4ECCA3||value==0xFF08745B)text.setTextColor(color(c,"accent"));
            else if(value==0xFFE0E0E0||value==0xFFFFFFFF||value==0xFFCCCCCC||value==0xFF888888||value==0xFF666666||value==0xFF1D2939||value==0xFF17243A)text.setTextColor(color(c,"text"));
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(c,group.getChildAt(i));}
    }
}
