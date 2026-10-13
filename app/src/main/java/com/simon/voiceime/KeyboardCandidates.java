package com.simon.voiceime;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
/** All candidate rows use this helper; 3x3/symbol grids retain their separate baseline size. */
public final class KeyboardCandidates {
    public static final float CANDIDATE_TEXT_SP=19,GRID_TEXT_SP=18,CANDIDATE_PADDING_PX=12;
    private KeyboardCandidates(){}
    public static int textColor(boolean dark){return DualHandVisuals.color(dark,"text");}
    public static void apply(TextView v,boolean dark,boolean selected){
        float density=v.getResources().getDisplayMetrics().density;
        v.setTypeface(Typeface.DEFAULT);v.setTextSize(CANDIDATE_TEXT_SP);v.setGravity(Gravity.CENTER);v.setSingleLine(true);v.setEllipsize(null);v.setIncludeFontPadding(true);
        v.setPadding((int)CANDIDATE_PADDING_PX,0,(int)CANDIDATE_PADDING_PX,0);
        v.setTag(R.id.keyboard_role,selected?"highlight":"background");v.setTag(R.id.keyboard_text_role,"text");v.setTag(R.id.keyboard_content_kind,"candidate");v.setTextColor(textColor(dark));
        v.setBackgroundColor(DualHandVisuals.color(dark,selected?"highlight":"background"));v.setSelected(selected);
    }
    public static void applyGrid(TextView v,boolean dark,boolean fixed){
        KeyboardKeycaps.apply(v,dark,"tools",1);v.setTypeface(fixed?KeyboardTypeface.forLabel(v.getContext(),v.getText().toString(),true,true):Typeface.DEFAULT);
        v.setTag(R.id.keyboard_text_role,"text");v.setTag(R.id.keyboard_content_kind,fixed?"symbol":"candidate");v.setTextColor(textColor(dark));v.setTextSize(GRID_TEXT_SP);v.setGravity(Gravity.CENTER);v.setSingleLine(true);v.setEllipsize(null);if(fixed)v.setIncludeFontPadding(false);
    }
}

