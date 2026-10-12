package com.simon.voiceime;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
/** Legacy call signature bridges to the one functional palette and independent role tags. */
public final class KeyboardTheme {
    private KeyboardTheme(){}
    public static boolean isDark(String mode,boolean system){return "dark".equals(mode)||!"light".equals(mode)&&system;}
    public static int color(String mode,boolean system,String role){
        if("key".equals(role))role="right";if("pressed".equals(role))role="highlight";
        return DualHandVisuals.color(isDark(mode,system),role);
    }
    public static int resourceColor(Context context,int resource){
        String role="text";
        if(resource==R.color.keyboard_bg)role="background";
        else if(resource==R.color.keyboard_key_bg||resource==R.color.key_bg)role="right";
        else if(resource==R.color.keyboard_key_border)role="border";
        else if(resource==R.color.keyboard_key_pressed)role="highlight";
        else if(resource==R.color.mode_append)role="accent";
        else if(resource==R.color.mic_active)role="recording";
        else if(resource==R.color.mic_idle)role="core";
        else if(resource==R.color.status_text)role="secondary";
        else if(resource==R.color.mode_replace)role="mode_replace";
        else if(resource==R.color.mode_spell)role="mode_spell";
        boolean system=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        return color(context.getSharedPreferences("simon_ime_prefs",0).getString("keyboard_theme","system"),system,role);
    }
    public static void apply(Context c,View view){KeyboardRoles.apply(c,view);}
}
