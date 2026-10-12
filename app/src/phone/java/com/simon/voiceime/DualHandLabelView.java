package com.simon.voiceime;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** Pack glyph ink into vertical cells rather than adding a full text-line ascent per symbol. */
final class DualHandLabelView extends TextView {
    DualHandLabelView(Context context){super(context);setIncludeFontPadding(false);}
    static float[] extent(String value,Paint paint){
        float width=0,cell=0;Rect ink=new Rect();String plain=value.replace("\n","");
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
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        float[] size=extent(getText().toString(),getPaint());setMeasuredDimension(resolveSize((int)size[0],widthSpec),resolveSize((int)size[1],heightSpec));
    }
    @Override protected void onDraw(Canvas canvas){
        String value=getText().toString().replace("\n","");int[] points=value.codePoints().toArray();if(points.length==0)return;
        Paint paint=getPaint();paint.setColor(getCurrentTextColor());float cell=getHeight()/(float)points.length;Rect ink=new Rect();
        for(int i=0;i<points.length;i++){
            String glyph=new String(Character.toChars(points[i]));paint.getTextBounds(glyph,0,glyph.length(),ink);
            canvas.drawText(glyph,(getWidth()-ink.width())/2f-ink.left,(i+.5f)*cell-(ink.top+ink.bottom)/2f,paint);
        }
    }
}
