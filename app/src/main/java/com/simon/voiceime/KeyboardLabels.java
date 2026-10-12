package com.simon.voiceime;

import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import java.util.List;

/** Page letter fitting; function text has the baseline 12sp rule and central abbreviations. */
public final class KeyboardLabels {
    public static final float FUNCTION_TEXT_SP=12,MAX_TEXT_SP=24,MIN_TEXT_SP=12,FIT_STEP_SP=.25f;
    public static final float KEY_MARGIN_DP=2,FIT_VERTICAL_MARGIN_DP=1;
    private static int fitCount;
    private KeyboardLabels() {}
    public static String labelClass(String value,boolean characterKey){
        if(characterKey)return "character";
        String plain=value.replace("\uFE0F","");
        return plain.codePointCount(0,plain.length())==1&&Character.getType(plain.codePointAt(0))==Character.OTHER_SYMBOL?"icon":"function";
    }
    public static String functionLabel(String value,String direction,boolean split){
        switch(value){
            case "41鍵":return "41";case "英數":return "英";case "收起":return "收";
            case "語音":return "語";case "符號":return "符";case "還原":return "還";
            case "刪字":return split?value:String.join("\n",value.split(""));
            case "送出":return "right".equals(direction)?"送":value;
            default:return value;
        }
    }
    /** Registered only; consumers must measure before enabling the 123 fallback globally. */
    public static String fallbackFunctionLabel(String value){return "123".equals(value)?"數":functionLabel(value,"tap",false);}
    public static float contentWidth(float viewWidth,float margin,float visibleInset){return viewWidth-2*(margin+visibleInset);}
    public static final class Label {
        public final float width,height,margin;public final String[] positions;
        public Label(float width,float height,float margin,String... positions){
            if(positions.length!=5)throw new IllegalArgumentException("tap/up/left/down/right required");
            this.width=width;this.height=height;this.margin=margin;this.positions=positions.clone();
        }
    }
    public static float[] extent(String value,Paint paint){
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
    public static boolean fits(float width,float height,float[][] sizes,float margin){
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
    public static float fit(Paint paint,float scaledDensity,List<Label> labels){
        if(labels.isEmpty())throw new IllegalArgumentException("No character labels; voice pages do not fit");
        fitCount++;
        for(float sp=MAX_TEXT_SP;sp>=MIN_TEXT_SP;sp-=FIT_STEP_SP){
            paint.setTextSize(sp*scaledDensity);boolean fits=true;
            for(Label label:labels){float[][] sizes=new float[5][];for(int i=0;i<5;i++)sizes[i]=extent(label.positions[i],paint);
                if(!fits(label.width,label.height,sizes,label.margin)){fits=false;break;}}
            if(fits)return sp;
        }
        throw new IllegalStateException("No non-overlapping label size");
    }
    public static int fitCount(){return fitCount;}
    /** Each page owns a cache; all labels, geometry, metrics and typeface identity participate. */
    public static final class Cache {
        private String last="";private Object font;private float result;private int count;
        public float fit(int width,float density,float scaledDensity,Typeface face,Paint paint,List<Label> labels){
            StringBuilder key=new StringBuilder(width+":"+density+":"+scaledDensity);
            for(Label label:labels){key.append('|').append(label.width).append(':').append(label.height).append(':').append(label.margin);for(String text:label.positions)key.append(':').append(text.length()).append(':').append(text);}
            String current=key.toString();if(!current.equals(last)||font!=face){result=KeyboardLabels.fit(paint,scaledDensity,labels);last=current;font=face;count++;}return result;
        }
        public int count(){return count;}
    }
}
