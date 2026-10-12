package com.simon.voiceime;
import android.graphics.*;
import android.view.View;
import android.widget.TextView;
import java.util.*;
/** Readback for F: measurements are actual View/paint bounds, never promises of no clipping. */
public final class KeyboardProbe {
    public interface Source {List<Map<String,Object>> describe();}
    private KeyboardProbe(){}
    public static Map<String,Object> view(View view){
        Map<String,Object> result=new LinkedHashMap<>();int[] at=new int[2];view.getLocationOnScreen(at);
        result.put("id",view.getId());result.put("bounds",new int[]{at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight()});
        result.put("role",view.getTag(R.id.keyboard_role));result.put("bg_factory",view.getBackground() instanceof KeyboardKeycaps.Cap);
        result.put("corner_dp",view.getBackground() instanceof KeyboardKeycaps.Cap?KeyboardKeycaps.CORNER_DP:0);
        result.put("elevation_dp",view.getElevation()/view.getResources().getDisplayMetrics().density);
        result.put("highlight_line",view.getBackground() instanceof KeyboardKeycaps.Cap);result.put("diamond",false);result.put("guide_dot",false);
        if(view.getBackground() instanceof KeyboardKeycaps.Cap){KeyboardKeycaps.Cap cap=(KeyboardKeycaps.Cap)view.getBackground();result.put("bg_color",DualHandVisuals.color(cap.dark,cap.role));result.put("visible_inset_dp",cap.insetDp);}
        else if(view.getBackground() instanceof android.graphics.drawable.ColorDrawable)result.put("bg_color",((android.graphics.drawable.ColorDrawable)view.getBackground()).getColor());
        return result;
    }
    public static Map<String,Object> text(TextView view,boolean characterKey,boolean fixed){
        Map<String,Object> result=view(view);String value=view.getText().toString(),type=KeyboardLabels.labelClass(value,characterKey);
        result.put("text",value);result.put("label_class","icon".equals(type)?"icon":"text");result.put("label_group",type);
        result.put("typeface",KeyboardTypeface.bundled(view.getTypeface())?"bundled":"system");
        result.put("text_px",view.getTextSize());result.put("text_sp",view.getTextSize()/view.getResources().getDisplayMetrics().scaledDensity);result.put("text_color",view.getCurrentTextColor());
        result.put("text_role",view.getTag(R.id.keyboard_text_role));
        Rect ink=new Rect();view.getPaint().getTextBounds(value.replace("\n",""),0,value.replace("\n","").length(),ink);
        int[] at=new int[2];view.getLocationOnScreen(at);
        float left=at[0]+(view.getWidth()-ink.width())/2f,top=at[1]+(view.getHeight()-ink.height())/2f;
        float right=left+ink.width(),bottom=top+ink.height();
        if(value.contains("\n")){
            int[] points=value.replace("\n","").codePoints().toArray();float cell=points.length==0?0:view.getHeight()/(float)points.length;
            left=Float.POSITIVE_INFINITY;top=Float.POSITIVE_INFINITY;right=Float.NEGATIVE_INFINITY;bottom=Float.NEGATIVE_INFINITY;
            for(int i=0;i<points.length;i++){String glyph=new String(Character.toChars(points[i]));Rect b=new Rect();view.getPaint().getTextBounds(glyph,0,glyph.length(),b);
                float x=at[0]+(view.getWidth()-b.width())/2f,y=at[1]+(i+.5f)*cell-b.height()/2f;left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+b.width());bottom=Math.max(bottom,y+b.height());}
        }
        result.put("label_bounds",new float[]{left,top,right,bottom});result.put("icon_visible_px","icon".equals(type)?Math.max(ink.width(),ink.height()):0);
        result.put("clipped",left<at[0]||top<at[1]||right>at[0]+view.getWidth()||bottom>at[1]+view.getHeight());result.put("fixed",fixed);
        return result;
    }
    public static Map<String,Integer> counters(){Map<String,Integer> m=new LinkedHashMap<>();m.put("font_load",KeyboardTypeface.loadCount());m.put("fit",KeyboardLabels.fitCount());m.put("haptic_request",KeyboardHaptics.requestCount());m.put("haptic_call",KeyboardHaptics.callCount());m.put("haptic_emitted",KeyboardHaptics.emittedCount());m.put("haptic_sync",KeyboardHaptics.syncCount());m.put("style_apply",KeyboardRoles.applicationCount()+KeyboardKeycaps.applicationCount());m.put("style_reapply",KeyboardRoles.reapplyCount()+KeyboardKeycaps.reapplyCount());m.put("unmarked",KeyboardRoles.unmarkedCount());return m;}
}
