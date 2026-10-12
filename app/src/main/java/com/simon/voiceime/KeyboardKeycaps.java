package com.simon.voiceime;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Outline;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.OvershootInterpolator;
/** Keycap drawing only; visible inset never changes text padding or the touch rectangle. */
public final class KeyboardKeycaps {
    public static final float CORNER_DP=5,ELEVATION_DP=1.5f,HIGHLIGHT_DP=.5f,PRESS_SCALE=.96f;
    public static final int RELEASE_MS=190;
    public static final String FACTORY="keyboard-keycap-690";
    private static int applications,reapplications;
    private KeyboardKeycaps(){}
    public static final class Cap extends Drawable {
        public final String role;public final float insetDp;public final boolean dark;
        private final float density;private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final GradientDrawable shape;
        Cap(boolean dark,String role,float density,float insetDp){
            this.dark=dark;this.role=role;this.density=density;this.insetDp=insetDp;
            shape=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{DualHandVisuals.color(dark,role),DualHandVisuals.color(dark,role)});
            shape.setCornerRadius(Math.round(CORNER_DP*density));if("zero".equals(role))shape.setStroke(Math.round(density),DualHandVisuals.color(dark,"border"));
        }
        @Override protected void onBoundsChange(Rect bounds){Rect visible=new Rect(bounds);int inset=Math.round(insetDp*density);visible.inset(inset,inset);shape.setBounds(visible);}
        @Override public boolean getPadding(Rect padding){padding.set(0,0,0,0);return false;}
        @Override public void getOutline(Outline outline){shape.getOutline(outline);}
        @Override public void setAlpha(int alpha){shape.setAlpha(alpha);invalidateSelf();}
        @Override public void setColorFilter(ColorFilter filter){shape.setColorFilter(filter);invalidateSelf();}
        @Override public int getOpacity(){return shape.getOpacity();}
        @Override public void draw(Canvas canvas){
            shape.draw(canvas);Rect r=shape.getBounds();
            ink.setColor(DualHandVisuals.color(dark,"top_highlight"));ink.setStrokeWidth(Math.round(HIGHLIGHT_DP*density));
            canvas.drawLine(r.left+Math.round(CORNER_DP*density),r.top+Math.round(HIGHLIGHT_DP*density),r.right-Math.round(CORNER_DP*density),r.top+Math.round(HIGHLIGHT_DP*density),ink);
        }
    }
    public static Cap create(boolean dark,String role,float density,float visibleInsetDp){return new Cap(dark,role,density,visibleInsetDp);}
    public static void apply(View view,boolean dark,String role,float visibleInsetDp){
        applications++;if(view.getBackground() instanceof Cap)reapplications++;
        float density=view.getResources().getDisplayMetrics().density;
        view.setTag(R.id.keyboard_role,role);view.setTag(R.id.keyboard_visible_inset,visibleInsetDp);view.setTag(R.id.keyboard_keycap_factory,FACTORY);
        view.setHapticFeedbackEnabled(KeyboardHaptics.enabled(view.getContext()));view.setBackground(create(dark,role,density,visibleInsetDp));view.setPadding(0,0,0,0);view.setElevation(Math.round(ELEVATION_DP*density));
    }
    public static int applicationCount(){return applications;}
    public static int reapplyCount(){return reapplications;}
    public static void press(View view){view.animate().cancel();view.animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(40).start();}
    public static void release(View view){view.animate().cancel();view.animate().scaleX(1).scaleY(1).translationX(0).translationY(0).rotationX(0).rotationY(0).setInterpolator(new OvershootInterpolator()).setDuration(RELEASE_MS).start();}
}
