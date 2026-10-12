package com.simon.voiceime;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** Measure and draw glyph ink; newlines select vertical stacking without full line ascents. */
final class DualHandLabelView extends TextView {
    DualHandLabelView(Context context){super(context);setIncludeFontPadding(false);}
    static float[] extent(String value,Paint paint){
        float width=0,cell=0;Rect ink=new Rect();String plain=value.replace("\n","");
        if(!value.contains("\n")){
            paint.getTextBounds(plain,0,plain.length(),ink);
            return new float[]{(float)Math.ceil(Math.max(paint.measureText(plain),ink.width())),ink.height()};
        }
        for(int cp:plain.codePoints().toArray()){
            String glyph=new String(Character.toChars(cp));paint.getTextBounds(glyph,0,glyph.length(),ink);
            width=Math.max(width,Math.max(paint.measureText(glyph),ink.width()));cell=Math.max(cell,ink.height());
        }
        return new float[]{(float)Math.ceil(width), (float)Math.ceil(cell*plain.codePointCount(0,plain.length()))};
    }
    static boolean fits(float width,float height,float[][] sizes,float margin){
        float[][] boxes=new float[5][4];
        for(int i=0;i<5;i++){
            float w=sizes[i][0],h=sizes[i][1];if(w==0||h==0)continue;
            float x=i==2?margin:i==4?width-margin-w:(width-w)/2;
            float y=i==1?margin:i==3?height-margin-h:(height-h)/2;
            if(x<0||y<0||x+w>width||y+h>height)return false;
            boxes[i]=new float[]{x,y,x+w,y+h};
            for(int j=0;j<i;j++){float[] b=boxes[j];if(b[2]>b[0]&&b[3]>b[1]&&x<b[2]&&x+w>b[0]&&y<b[3]&&y+h>b[1])return false;}
        }
        return true;
    }
    private boolean icon(){String s=getText().toString();return "⌫".equals(s)||"⚙".equals(s);}
    private final Paint systemPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint iconPaint(){systemPaint.setTypeface(Typeface.DEFAULT);systemPaint.setTextSize(getTextSize());systemPaint.setColor(getCurrentTextColor());return systemPaint;}
    private void drawIcon(Canvas c,String value){
        Paint p=iconPaint();if(p.hasGlyph(value)){Rect b=new Rect();p.getTextBounds(value,0,value.length(),b);c.drawText(value,(getWidth()-b.width())/2f-b.left,(getHeight()-b.height())/2f-b.top,p);return;}
        c.save();float size=14*getResources().getDisplayMetrics().density;c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/16,size/16);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.25f);
        if("⌫".equals(value)){Path path=new Path();path.moveTo(1,8);path.lineTo(5,3);path.lineTo(15,3);path.lineTo(15,13);path.lineTo(5,13);path.close();c.drawPath(path,p);c.drawLine(7,5,12,11,p);c.drawLine(12,5,7,11,p);}
        else {c.drawCircle(8,8,4,p);c.drawCircle(8,8,1.5f,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,8,8);c.drawLine(8,1,8,4,p);c.restore();}}
        p.setStyle(Paint.Style.FILL);c.restore();
    }
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
