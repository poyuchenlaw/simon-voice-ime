package com.simon.voiceime;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.*;
import org.json.*;
import java.util.*;

/** Measurement only. Canvas commands are read from the actual View/Drawable,
 * never inferred from the palette or from a product version number. */
final class App690ProbeEvidence {
    static JSONArray box(RectF r)throws JSONException { return new JSONArray(new float[]{r.left,r.top,r.right,r.bottom}); }
    static Matrix screenMatrix(View v) {
        Matrix m=new Matrix();v.transformMatrixToGlobal(m);
        // transformMatrixToGlobal already includes the attached window's screen
        // offset. App690ProbeEvidenceTest compares it to getLocationOnScreen.
        return m;
    }
    static JSONArray matrix(Matrix m)throws JSONException {float[] values=new float[9];m.getValues(values);return new JSONArray(values);}
    static RectF mapped(View v,RectF local) {RectF out=new RectF(local);screenMatrix(v).mapRect(out);return out;}
    static final class Commands extends Canvas {
        final JSONArray lines=new JSONArray(),roundRects=new JSONArray(),paths=new JSONArray();
        Commands(Bitmap b){super(b);}
        @Override public void drawLine(float x0,float y0,float x1,float y1,Paint p){
            try{lines.put(new JSONObject().put("path",new JSONArray(new float[]{x0,y0,x1,y1})).put("width_px",p.getStrokeWidth()).put("argb",p.getColor()).put("canvas_matrix",matrix(getMatrix())));}catch(JSONException e){throw new IllegalStateException(e);}super.drawLine(x0,y0,x1,y1,p);
        }
        @Override public void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint p){
            try{roundRects.put(new JSONObject().put("bounds",new JSONArray(new float[]{l,t,r,b})).put("argb",p.getColor()).put("style",p.getStyle().name()));}catch(JSONException e){throw new IllegalStateException(e);}super.drawRoundRect(l,t,r,b,rx,ry,p);
        }
        @Override public void drawPath(Path path,Paint p){
            RectF b=new RectF();path.computeBounds(b,true);
            try{paths.put(new JSONObject().put("bounds",box(b)).put("argb",p.getColor()).put("style",p.getStyle().name()).put("width_px",p.getStrokeWidth()).put("approximation",new JSONArray(path.approximate(.25f))));}catch(JSONException e){throw new IllegalStateException(e);}super.drawPath(path,p);
        }
    }
    static JSONObject drawing(View cap)throws Exception {
        JSONObject out=new JSONObject().put("stroke_width_px",JSONObject.NULL).put("stroke_path",JSONObject.NULL).put("draw_bounds",JSONObject.NULL).put("drawable_highlight_color",JSONObject.NULL);
        if(cap.getWidth()<=0||cap.getHeight()<=0)return out.put("draw_capture_error","zero size");
        Bitmap b=Bitmap.createBitmap(cap.getWidth(),cap.getHeight(),Bitmap.Config.ARGB_8888);
        try{
            Commands c=new Commands(b);Drawable d=cap.getBackground();
            // New factory draws the highlight in its Drawable. Legacy KeyCap draws it in onDraw.
            if(d!=null&&d.getClass().getName().endsWith("KeyboardKeycaps$Cap"))d.draw(c);else cap.draw(c);
            out.put("draw_source","actual Canvas commands; software readback, hardware samples separate").put("draw_lines",c.lines).put("draw_paths",c.paths).put("draw_round_rects",c.roundRects);
            for(int i=0;i<c.lines.length();i++){
                JSONObject line=c.lines.getJSONObject(i);JSONArray p=line.getJSONArray("path");
                if(p.getDouble(1)==p.getDouble(3)&&p.getDouble(2)-p.getDouble(0)>cap.getWidth()/2f&&p.getDouble(1)<10*cap.getResources().getDisplayMetrics().density){
                    out.put("stroke_width_px",line.get("width_px")).put("stroke_path",p).put("drawable_highlight_color",line.get("argb"));
                    float width=(float)line.getDouble("width_px");RectF bounds=new RectF((float)p.getDouble(0),(float)p.getDouble(1)-width/2,(float)p.getDouble(2),(float)p.getDouble(3)+width/2);
                    out.put("draw_bounds",box(bounds));break;
                }
            }
            if(cap.getContentDescription()!=null&&cap.getContentDescription().toString().equals("開啟校正選字")&&c.roundRects.length()>0){
                JSONObject halo=c.roundRects.getJSONObject(0);RectF bounds=new RectF((float)halo.getJSONArray("bounds").getDouble(0),(float)halo.getJSONArray("bounds").getDouble(1),(float)halo.getJSONArray("bounds").getDouble(2),(float)halo.getJSONArray("bounds").getDouble(3));
                out.put("drawn_role","halo").put("drawn_argb",halo.get("argb")).put("halo_ink_bounds",box(mapped(cap,bounds))).put("halo_color_source","actual marker onDraw Paint.color");
            }
        }finally{b.recycle();}return out;
    }
    static JSONObject geometry(View cap,List<View> windows)throws Exception {
        JSONArray orders=new JSONArray(),occluders=new JSONArray();int own=windows.indexOf(cap.getRootView());
        RectF actual=mapped(cap,new RectF(0,0,cap.getWidth(),cap.getHeight()));
        for(int i=0;i<windows.size();i++){
            View w=windows.get(i);if(!w.isShown())continue;
            RectF bounds=mapped(w,new RectF(0,0,w.getWidth(),w.getHeight()));
            JSONObject item=new JSONObject().put("index",i).put("class",w.getClass().getName()).put("bounds",box(bounds)).put("z",w.getZ()).put("alpha",w.getAlpha());
            if(w.getLayoutParams() instanceof WindowManager.LayoutParams){WindowManager.LayoutParams p=(WindowManager.LayoutParams)w.getLayoutParams();item.put("type",p.type).put("flags",p.flags).put("format",p.format).put("window_alpha",p.alpha);}
            orders.put(item);if(i>own&&RectF.intersects(bounds,actual))occluders.put(item);
        }
        float alpha=1;for(View v=cap;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null)alpha*=v.getAlpha();
        return new JSONObject().put("view_matrix",matrix(screenMatrix(cap))).put("alpha",alpha).put("view_timestamp",SystemClock.uptimeMillis()).put("window_z",own).put("window_order",orders).put("occluding_windows",occluders).put("transformed_view_bounds",box(actual));
    }
    static JSONObject pixel(Bitmap scene,Matrix m,float x,float y)throws Exception {
        float[] at={x,y};m.mapPoints(at);int sx=Math.round(at[0]),sy=Math.round(at[1]);
        return new JSONObject().put("local_xy",new JSONArray(new float[]{x,y})).put("screen_xy",new JSONArray(new int[]{sx,sy})).put("argb",sx>=0&&sy>=0&&sx<scene.getWidth()&&sy<scene.getHeight()?scene.getPixel(sx,sy):JSONObject.NULL);
    }
    static JSONArray strokeSamples(View cap,Bitmap scene,JSONObject drawing,List<JSONObject> labels)throws Exception {
        JSONArray samples=new JSONArray();if(drawing.isNull("stroke_path"))return samples;
        JSONArray p=drawing.getJSONArray("stroke_path");float x0=(float)p.getDouble(0),x1=(float)p.getDouble(2),y=(float)p.getDouble(1),d=cap.getResources().getDisplayMetrics().density;Matrix m=screenMatrix(cap);
        Bitmap underlay=Bitmap.createBitmap(cap.getWidth(),cap.getHeight(),Bitmap.Config.ARGB_8888);Drawable background=cap.getBackground();Object shape=null;try{shape=App690FSupport.field(background,"shape");}catch(Exception absent){}
        if(shape instanceof Drawable)((Drawable)shape).draw(new Canvas(underlay));else if(background!=null)background.draw(new Canvas(underlay));
        try{for(int step=1;step<20;step++)for(float dy:new float[]{-.5f,0,.5f}){
            float x=x0+(x1-x0)*step/20f;JSONObject top=pixel(scene,m,x,y+dy),below=pixel(scene,m,x,y+2*d);JSONArray ta=top.getJSONArray("screen_xy"),ba=below.getJSONArray("screen_xy");boolean masked=false;
            for(JSONObject label:labels){JSONArray ink=label.getJSONArray("transformed_ink_bounds");RectF box=new RectF((float)ink.getDouble(0)-2,(float)ink.getDouble(1)-2,(float)ink.getDouble(2)+2,(float)ink.getDouble(3)+2);if(box.contains(ta.getInt(0),ta.getInt(1))||box.contains(ba.getInt(0),ba.getInt(1)))masked=true;}
            int sx=Math.round(x),ty=Math.round(y+dy),by=Math.round(y+2*d);
            JSONObject sample=new JSONObject().put("stroke",top).put("below",below).put("coverage_offset_px",dy).put("excluded",masked?"transformed text ink":JSONObject.NULL).put("underlay",sx>=0&&ty>=0&&sx<underlay.getWidth()&&ty<underlay.getHeight()?underlay.getPixel(sx,ty):JSONObject.NULL).put("below_underlay",sx>=0&&by>=0&&sx<underlay.getWidth()&&by<underlay.getHeight()?underlay.getPixel(sx,by):JSONObject.NULL).put("underlay_source",shape instanceof Drawable?"actual factory shape before highlight":"actual background Drawable before KeyCap.onDraw");samples.put(sample);
        }}finally{underlay.recycle();}return samples;
    }
    static JSONArray diamondSamples(View cap,Bitmap scene,List<RectF> screenInk,List<View> windows)throws Exception {
        float w=cap.getWidth(),h=cap.getHeight(),d=cap.getResources().getDisplayMetrics().density;float[][] points={{w/2,8*d},{w-8*d,h/2},{w/2,h-8*d},{8*d,h/2}};int offset=Math.max(1,Math.round(2*d));Matrix m=screenMatrix(cap);JSONArray out=new JSONArray();
        Rect visible=new Rect();cap.getGlobalVisibleRect(visible);int[] s=new int[2],win=new int[2];cap.getRootView().getLocationOnScreen(s);cap.getRootView().getLocationInWindow(win);visible.offset(s[0]-win[0],s[1]-win[1]);int own=windows.indexOf(cap.getRootView());
        for(int edge=0;edge<4;edge++){float[] a=points[edge],b=points[(edge+1)%4];int steps=Math.max(1,(int)Math.ceil(Math.hypot(a[0]-b[0],a[1]-b[1])));
            for(int i=0;i<=steps;i++)for(int dy=-1;dy<=1;dy++){
                int x=Math.round(a[0]+(b[0]-a[0])*i/steps),y=Math.round(a[1]+(b[1]-a[1])*i/steps)+dy;if(x<0||x>=w||y<0||y+offset>=h)continue;
                JSONObject first=pixel(scene,m,x,y),second=pixel(scene,m,x,y+offset);JSONArray axy=first.getJSONArray("screen_xy"),bxy=second.getJSONArray("screen_xy");RectF segment=new RectF(Math.min(axy.getInt(0),bxy.getInt(0))-.5f,Math.min(axy.getInt(1),bxy.getInt(1))-.5f,Math.max(axy.getInt(0),bxy.getInt(0))+.5f,Math.max(axy.getInt(1),bxy.getInt(1))+.5f);
                String exclusion=null;for(RectF ink:screenInk){RectF expanded=new RectF(ink);expanded.inset(-2,-2);if(RectF.intersects(expanded,segment)){exclusion="transformed ink or guide dot";break;}}
                if(!visible.contains(axy.getInt(0),axy.getInt(1))||!visible.contains(bxy.getInt(0),bxy.getInt(1)))exclusion="outside visible clip";
                for(int z=own+1;z<windows.size();z++){View window=windows.get(z);if(window.isShown()&&RectF.intersects(mapped(window,new RectF(0,0,window.getWidth(),window.getHeight())),segment))exclusion="occluding window";}
                out.put(new JSONObject().put("edge",edge).put("step",i).put("first",first).put("second",second).put("excluded",exclusion==null?JSONObject.NULL:exclusion));
            }
        }return out;
    }
}
