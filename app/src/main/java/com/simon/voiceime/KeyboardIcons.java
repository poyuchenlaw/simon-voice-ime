package com.simon.voiceime;
import android.graphics.*;
/** Named baseline frame dimensions. Other pages register measured baseline frames, never guess. */
public final class KeyboardIcons {
    public static final float DUAL_FRAME_DP=16,DUAL_VECTOR_DP=14;
    private KeyboardIcons(){}
    public static void draw(Canvas c,String value,Paint p,float width,float height,float density){
        if(p.hasGlyph(value)){Rect b=new Rect();p.getTextBounds(value,0,value.length(),b);c.drawText(value,(width-b.width())/2f-b.left,(height-b.height())/2f-b.top,p);return;}
        c.save();float size=DUAL_VECTOR_DP*density;c.translate((width-size)/2,(height-size)/2);c.scale(size/DUAL_FRAME_DP,size/DUAL_FRAME_DP);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.25f);
        if("⌫".equals(value)){Path path=new Path();path.moveTo(1,8);path.lineTo(5,3);path.lineTo(15,3);path.lineTo(15,13);path.lineTo(5,13);path.close();c.drawPath(path,p);c.drawLine(7,5,12,11,p);c.drawLine(12,5,7,11,p);}
        else {c.drawCircle(8,8,4,p);c.drawCircle(8,8,1.5f,p);for(int i=0;i<8;i++){c.save();c.rotate(i*45,8,8);c.drawLine(8,1,8,4,p);c.restore();}}
        p.setStyle(Paint.Style.FILL);c.restore();
    }
}
