package com.simon.voiceime;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** Measure and draw glyph ink; newlines select vertical stacking without full line ascents. */
final class DualHandLabelView extends TextView {
    DualHandLabelView(Context context){super(context);setIncludeFontPadding(false);}
    static float[] extent(String value,Paint paint){return KeyboardLabels.extent(value,paint);}
    static boolean fits(float width,float height,float[][] sizes,float margin){return KeyboardLabels.fits(width,height,sizes,margin);}
    private boolean icon(){String s=getText().toString();return "⌫".equals(s)||"⚙".equals(s);}
    private final Paint systemPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint iconPaint(){systemPaint.setTypeface(Typeface.DEFAULT);systemPaint.setTextSize(getTextSize());systemPaint.setColor(getCurrentTextColor());return systemPaint;}
    private void drawIcon(Canvas c,String value){KeyboardIcons.draw(c,value,iconPaint(),getWidth(),getHeight(),getResources().getDisplayMetrics().density);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        float[] size=icon()?new float[]{16*getResources().getDisplayMetrics().density,16*getResources().getDisplayMetrics().density}:extent(getText().toString(),getPaint());setMeasuredDimension(resolveSize((int)size[0],widthSpec),resolveSize((int)size[1],heightSpec));
    }
    @Override protected void onDraw(Canvas canvas){
        if(icon()){drawIcon(canvas,getText().toString());return;}
        String text=getText().toString(),value=text.replace("\n","");int[] points=value.codePoints().toArray();if(points.length==0)return;
        Paint paint=getPaint();paint.setColor(getCurrentTextColor());float cell=getHeight()/(float)points.length;Rect ink=new Rect();
        if(!text.contains("\n")){
            paint.getTextBounds(value,0,value.length(),ink);
            canvas.drawText(value,(getWidth()-ink.width())/2f-ink.left,(getHeight()-ink.height())/2f-ink.top,paint);return;
        }
        for(int i=0;i<points.length;i++){
            String glyph=new String(Character.toChars(points[i]));paint.getTextBounds(glyph,0,glyph.length(),ink);
            canvas.drawText(glyph,(getWidth()-ink.width())/2f-ink.left,(i+.5f)*cell-(ink.top+ink.bottom)/2f,paint);
        }
    }
}
