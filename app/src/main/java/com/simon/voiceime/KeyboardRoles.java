package com.simon.voiceime;
import android.content.Context;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
/** Independent keyed tags leave the key: touch-routing tag untouched. */
public final class KeyboardRoles {
    private static int applications,reapplications,unmarked;
    private KeyboardRoles(){}
    public static boolean dark(Context c){return KeyboardTheme.isDark(c.getSharedPreferences("simon_ime_prefs",0).getString("keyboard_theme","system"),(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    public static void mark(View view,String background,String foreground){view.setTag(R.id.keyboard_role,background);view.setTag(R.id.keyboard_text_role,foreground);view.setTag(R.id.keyboard_style_theme,null);}
    public static void apply(Context c,View view){
        if(view==null||"dual-hand-keyboard".equals(view.getTag()))return;
        boolean dark=dark(c);Object previous=view.getTag(R.id.keyboard_style_theme);
        if(!Boolean.valueOf(dark).equals(previous)){
            Object tag=view.getTag(R.id.keyboard_role);String role=tag instanceof String?(String)tag:"tools";
            if(tag==null&&view.getVisibility()==View.VISIBLE)unmarked++;
            Object routed=view.getTag(),kind=view.getTag(R.id.keyboard_content_kind);boolean key=view.getBackground() instanceof KeyboardKeycaps.Cap||(!"candidate".equals(kind)&&!"content".equals(kind)&&(view.isClickable()&&view instanceof TextView||routed instanceof String&&((String)routed).startsWith("key:")));
            if(key){Object inset=view.getTag(R.id.keyboard_visible_inset);KeyboardKeycaps.apply(view,dark,role,inset instanceof Number?((Number)inset).floatValue():1);}
            else view.setBackgroundColor(DualHandVisuals.color(dark,role));
            if(view instanceof TextView){Object fg=view.getTag(R.id.keyboard_text_role);((TextView)view).setTextColor(DualHandVisuals.color(dark,fg instanceof String?(String)fg:"text"));}
            applications++;if(previous!=null)reapplications++;view.setTag(R.id.keyboard_style_theme,dark);
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)apply(c,group.getChildAt(i));}
    }
    public static int applicationCount(){return applications;}public static int reapplyCount(){return reapplications;}public static int unmarkedCount(){return unmarked;}
}
