package com.simon.voiceime;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Numeric geometry only. One summary when the composition ends; no text access. */
final class LayoutDiagnostics implements ViewTreeObserver.OnDrawListener {
    static final class Axis {
        private boolean initialized;
        private int previous,direction;
        int moves,max;
        void restart(){initialized=false;direction=0;}
        void sample(int value){
            if(initialized){int delta=value-previous;
                if(delta!=0){int next=delta>0?1:-1;if(direction!=0&&direction!=next){moves++;max=Math.max(max,Math.abs(delta));}direction=next;}
            }
            initialized=true;previous=value;
        }
    }
    private final Context context;
    private final ImeTelemetry telemetry;
    private final View root;
    private final View[] views;
    private final Axis[] x=new Axis[4],y=new Axis[4],glyphX=new Axis[2];
    private final int[] lineEnds={-1,-1};
    private final int[] location=new int[2];
    private final int[] tops=new int[4],heights=new int[4],baselines=new int[4],widths=new int[4],scrolls=new int[4];
    private final float[] textSizes=new float[4];
    private boolean active,hasSample;
    LayoutDiagnostics(Context context,View root,ImeTelemetry telemetry){
        this.context=context;this.root=root;this.telemetry=telemetry;
        views=new View[]{root.findViewById(R.id.boStreamPreview),root.findViewById(R.id.boPhoneticPreview),root.findViewById(R.id.boCandidateBar),root.findViewById(R.id.bopomofoKeyboard)};
        root.getViewTreeObserver().addOnDrawListener(this);
    }
    void composition(boolean hasText){
        if(hasText&&!active){active=true;hasSample=false;for(int i=0;i<4;i++){x[i]=new Axis();y[i]=new Axis();}for(int i=0;i<2;i++){glyphX[i]=new Axis();lineEnds[i]=-1;}}
        else if(!hasText)finish();
    }
    @Override public void onDraw(){if(active)sample();}
    private static String family(View view){
        if(!(view instanceof TextView)||android.os.Build.VERSION.SDK_INT<34)return null;
        android.graphics.Typeface face=((TextView)view).getTypeface();
        return face==null?null:face.getSystemFontFamilyName();
    }
    private void sample(){
        try {
            for(int i=0;i<4;i++){
                View v=views[i];if(v==null)return;
                v.getLocationOnScreen(location);
                int scroll=v.getScrollX(),width=v.getWidth(),baseline=v instanceof TextView?((TextView)v).getBaseline():-1;
                if(i<2&&v.getParent() instanceof View)scroll+=((View)v.getParent()).getScrollX();
                if(i==2){View cs=root.findViewById(R.id.boCandidateScroll),ci=root.findViewById(R.id.boCandidateItems);if(cs!=null)scroll+=cs.getScrollX();if(ci!=null)width=ci.getWidth();}
                x[i].sample(scroll);y[i].sample(location[1]+Math.max(0,baseline));
                if(i<2){android.text.Layout layout=((TextView)v).getLayout();if(layout!=null&&layout.getLineCount()>0){
                    int end=layout.getLineEnd(0);if(lineEnds[i]!=end){glyphX[i].restart();lineEnds[i]=end;}
                    // Catch same-length glyph reflow even when scrollX stays still.
                    // Only numeric layout extents are read; no text is inspected.
                    glyphX[i].sample(Math.round(layout.getLineRight(0)));
                }}
                tops[i]=location[1];heights[i]=v.getHeight();baselines[i]=baseline;widths[i]=width;scrolls[i]=scroll;
                textSizes[i]=v instanceof TextView?((TextView)v).getTextSize():0;
            }
            hasSample=true;
        }catch(Exception error){Log.w("ImeLayoutDiagnostics","layout sample unavailable",error);}
    }
    private JSONObject summary()throws Exception {
            JSONArray rows=new JSONArray();
            for(int i=0;i<4;i++)rows.put(new JSONObject().put("row",i+1).put("top",tops[i]).put("height",heights[i]).put("baseline",baselines[i])
                .put("textSizePx",textSizes[i]).put("width",widths[i]).put("scrollX",scrolls[i])
                .put("xMoves",x[i].moves+(i<2?glyphX[i].moves:0)).put("xMax",Math.max(x[i].max,i<2?glyphX[i].max:0)).put("yMoves",y[i].moves).put("yMax",y[i].max));
            // Metadata and JSON are assembled once, outside the draw callback.
            android.util.DisplayMetrics dm=context.getResources().getDisplayMetrics();
            android.util.DisplayMetrics screen=new android.util.DisplayMetrics();screen.setTo(dm);
            android.view.WindowManager window=(android.view.WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            if(window!=null)window.getDefaultDisplay().getRealMetrics(screen);
            int display=-1;try{display=Settings.Secure.getInt(context.getContentResolver(),"display_density_forced",-1);}catch(SecurityException unavailable){/* Recorded as unavailable, never inferred. */}
            return new JSONObject().put("fontScale",context.getResources().getConfiguration().fontScale).put("densityDpi",dm.densityDpi)
                .put("screenWidth",screen.widthPixels).put("screenHeight",screen.heightPixels).put("displayDensitySetting",display)
                .put("typefaceRow1",family(views[0])==null?JSONObject.NULL:family(views[0])).put("typefaceRow2",family(views[1])==null?JSONObject.NULL:family(views[1])).put("views",rows);
    }
    void finish(){
        if(!active)return;
        if(!hasSample)sample();active=false;
        if(hasSample&&telemetry!=null)try{telemetry.record("layout_diag","bopomofo",summary(),false);}
        catch(Exception error){Log.w("ImeLayoutDiagnostics","layout summary unavailable",error);}
        hasSample=false;
    }
    void close(){finish();if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnDrawListener(this);}
}
