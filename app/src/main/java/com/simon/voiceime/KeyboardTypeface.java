package com.simon.voiceime;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.MetricAffectingSpan;
import android.text.TextPaint;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One asset load attempt per process. Call at inflate, never in onStartInputView. */
public final class KeyboardTypeface {
    private static Typeface face;
    private static boolean attempted;
    private static int loadCount;
    private static String missing="",characters="";
    private KeyboardTypeface() {}
    public static synchronized Typeface get(Context context) {
        if(!attempted){
            attempted=true;loadCount++;
            try {
                face=Typeface.createFromAsset(context.getAssets(),"fonts/NotoSansTC-Medium-subset.ttf");
                try(InputStream in=context.getAssets().open("fonts/SUBSET_MANIFEST.json")){
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];
                    for(int n;(n=in.read(buffer))!=-1;)bytes.write(buffer,0,n);
                    JSONObject manifest=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                    missing=manifest.getString("source_missing_characters");characters=manifest.getString("characters");
                }
            } catch(Exception unavailable){
                face=Typeface.DEFAULT;android.util.Log.w("KeyboardTypeface","Bundled font unavailable; using system fallback",unavailable);
            }
        }
        return face;
    }
    public static synchronized int loadCount(){return loadCount;}
    public static synchronized boolean bundled(Typeface value){return attempted&&face!=Typeface.DEFAULT&&value==face;}
    public static boolean phonetic(int cp){return cp>=0x3105&&cp<=0x3129||cp==0x02d9||cp==0x02ca||cp==0x02c7||cp==0x02cb;}
    public static Typeface forLabel(Context c,String label,boolean fixed){return forLabel(c,label,fixed,false);}
    public static Typeface forLabel(Context c,String label,boolean fixed,boolean characterKey){
        Typeface f=get(c);if(!fixed)return Typeface.DEFAULT;
        boolean unknown=label.codePoints().anyMatch(cp->!Character.isWhitespace(cp)&&characters.indexOf(new String(Character.toChars(cp)))<0);
        boolean icon=!characterKey&&"icon".equals(KeyboardLabels.labelClass(label,false));
        boolean fallbackIcon=icon&&label.codePoints().anyMatch(cp->cp!=0xfe0f&&missing.indexOf(new String(Character.toChars(cp)))>=0);
        return unknown||fallbackIcon?Typeface.DEFAULT:f;
    }
    public static String classification(Context c,String label,boolean fixed){return bundled(forLabel(c,label,fixed))?"bundled":"system";}
    public static boolean covered(Context c,String label){get(c);return label.codePoints().allMatch(cp->Character.isWhitespace(cp)||characters.indexOf(new String(Character.toChars(cp)))>=0);}
    /** Open text remains system typeface; only phonetics/tone ranges get the bundled font. */
    public static CharSequence mixed(Context c,CharSequence value){
        SpannableString text=new SpannableString(value);Typeface f=get(c);
        for(int at=0;at<text.length();){int cp=Character.codePointAt(text,at),end=at+Character.charCount(cp);if(phonetic(cp))text.setSpan(new FontSpan(f),at,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);at=end;}
        return text;
    }
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface face;FontSpan(Typeface face){this.face=face;}
        @Override public void updateDrawState(TextPaint paint){paint.setTypeface(face);}
        @Override public void updateMeasureState(TextPaint paint){paint.setTypeface(face);}
    }
}
