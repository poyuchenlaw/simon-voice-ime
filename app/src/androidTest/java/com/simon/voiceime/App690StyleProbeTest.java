package com.simon.voiceime;

import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Same signed test APK runs on 6.89.1: shared API is optional and accessed by reflection. */
public class App690StyleProbeTest extends App690FSupport {
    private final JSONArray rows=new JSONArray(),coverage=new JSONArray(),presses=new JSONArray();
    private final Set<String> chars=new TreeSet<>();
    private JSONObject manifest=new JSONObject();
    private String pageName,state,theme;
    private Typeface bundled;
    private int tagId(String name){return inst.getTargetContext().getResources().getIdentifier(name,"id",inst.getTargetContext().getPackageName());}
    private Object tag(View v,String name){int id=tagId(name);return id==0?null:v.getTag(id);}
    private JSONObject palette()throws Exception {
        JSONObject all=new JSONObject();Class<?> p=api("DualHandVisuals");
        for(String t:new String[]{"dark","light"}){JSONObject colors=new JSONObject();for(String r:new String[]{"left","right","zero","core","tools","text","secondary","hint","border","highlight","accent","recording","mode_append","mode_replace","mode_spell","mode_translate","background","top_highlight","bubble","bubble_text","overlay","halo","transparent","action_positive_bg","action_negative_bg","destructive"})colors.put(r,call(p,"color",t.equals("dark"),r));all.put(t,colors);}return all;
    }
    private int background(View view,JSONObject apiRow)throws Exception {
        android.graphics.drawable.Drawable d=view.getBackground();
        while(d instanceof InsetDrawable)d=((InsetDrawable)d).getDrawable();
        if(d instanceof ColorDrawable)return ((ColorDrawable)d).getColor();
        if(d instanceof GradientDrawable){GradientDrawable gradient=(GradientDrawable)d;if(gradient.getColor()!=null)return gradient.getColor().getDefaultColor();int[] colors=gradient.getColors();if(colors!=null&&colors.length>0){for(int color:colors)assertEquals("uniform keycap gradient",colors[0],color);return colors[0];}}
        if(apiRow.has("bg_color"))return apiRow.getInt("bg_color");
        if(view.getParent() instanceof View)return background((View)view.getParent(),new JSONObject());
        return 0;
    }
    private String classify(TextView v,View cap,String kind){
        if("content".equals(tag(v,"keyboard_content_kind")))return "content";
        if(kind.equals("candidate")||kind.equals("content")||kind.equals("status")||kind.equals("capsule")||kind.equals("grid_cell"))return "content";
        String value=v.getText().toString().replace("\uFE0F","");Object routing=cap.getTag();String key=routing==null?"":routing.toString();
        boolean character=key.startsWith("dh-key:")&&"123456ABCDEFØ".contains(key.substring(7))||key.startsWith("key:")&&key.substring(4).codePointCount(0,key.substring(4).length())==1;
        if(state.equals("fullwidth_symbols")&&plainSymbol(value))return "character";
        if(character&&!(pageName.equals("bopomofo")&&value.equals("，"))&&!(pageName.equals("english")||pageName.equals("numbers"))||character&&(pageName.equals("english")||pageName.equals("numbers"))&&!value.equals("."))return "character";
        if(value.codePointCount(0,value.length())==1&&Character.getType(value.codePointAt(0))==Character.OTHER_SYMBOL)return "icon";
        return "function";
    }
    private boolean plainSymbol(String value){return !value.isEmpty()&&value.codePoints().allMatch(cp->!Character.isLetterOrDigit(cp)&&!Character.isWhitespace(cp));}
    private float[] ink(TextView v){
        String text=v.getText().toString();int[] at=new int[2];v.getLocationOnScreen(at);Paint paint=v.getPaint();Rect b=new Rect();float l=Float.POSITIVE_INFINITY,t=l,r=Float.NEGATIVE_INFINITY,bottom=r;
        if(v.getClass().getSimpleName().equals("DualHandLabelView")){
            String[] lines=text.contains("\n")?text.split("\n"):new String[]{text};float cell=v.getHeight()/(float)Math.max(1,lines.length);
            for(int i=0;i<lines.length;i++){paint.getTextBounds(lines[i],0,lines[i].length(),b);float x=at[0]+(v.getWidth()-b.width())/2f,y=at[1]+(i+.5f)*cell-b.height()/2f;l=Math.min(l,x);t=Math.min(t,y);r=Math.max(r,x+b.width());bottom=Math.max(bottom,y+b.height());}
        }else if(v.getLayout()!=null){android.text.Layout layout=v.getLayout();for(int i=0;i<layout.getLineCount();i++){String line=text.substring(layout.getLineStart(i),Math.min(text.length(),layout.getLineEnd(i))).replace("\n","");paint.getTextBounds(line,0,line.length(),b);float x=at[0]+v.getCompoundPaddingLeft()-v.getScrollX()+layout.getLineLeft(i),y=at[1]+v.getBaseline()-v.getScrollY()+layout.getLineBaseline(i)-layout.getLineBaseline(0);l=Math.min(l,x+b.left);t=Math.min(t,y+b.top);r=Math.max(r,x+b.right);bottom=Math.max(bottom,y+b.bottom);}}
        if(!Float.isFinite(l))return new float[]{at[0],at[1],at[0],at[1]};return new float[]{l,t,r,bottom};
    }
    private boolean monochrome(TextView v){
        String text=v.getText().toString();if(text.isEmpty())return true;
        Paint p=new Paint(v.getPaint());Bitmap a=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888),b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888);p.setColor(0xffff0000);new Canvas(a).drawText(text,8,72,p);p.setColor(0xff00ff00);new Canvas(b).drawText(text,8,72,p);boolean same=a.sameAs(b);a.recycle();b.recycle();return !same;
    }
    private JSONObject row(TextView v,View cap,String id,String kind,JSONObject data,String source)throws Exception {
        String text=v.getText().toString(),group=classify(v,cap,kind);float d=density(),sd=v.getResources().getDisplayMetrics().scaledDensity;Rect box=rect(cap),vr=new Rect(box);float inset=0;
        Object it=tag(cap,"keyboard_visible_inset");if(it instanceof Number)inset=((Number)it).floatValue();vr.inset(Math.round(inset*d),Math.round(inset*d));float[] in=ink(v);
        boolean isBundled=v.getTypeface()==bundled;
        try{isBundled=(Boolean)call(api("KeyboardTypeface"),"bundled",v.getTypeface());}catch(ClassNotFoundException absent){}
        String glyphSource="none";if(group.equals("icon")){String missing=manifest.optString("source_missing_characters","⌫⚙↵⏹🎤📋⚡"),plain=text.replace("\uFE0F","");glyphSource=plain.codePoints().anyMatch(cp->missing.contains(new String(Character.toChars(cp))))?"fallback":"subset";}
        Paint system=new Paint(v.getPaint());system.setTypeface(Typeface.DEFAULT);boolean has=system.hasGlyph(text);
        if(group.equals("icon")&&v.getClass().getSimpleName().equals("DualHandLabelView")&&(text.equals("⌫")||text.equals("⚙"))){isBundled=false;if(!has)glyphSource="vector";if(data.has("label_bounds"))in=toFloats(data.getJSONArray("label_bounds"));}
        Object role=tag(cap,"keyboard_role"),textRole=tag(v,"keyboard_text_role");if(role==null)role=data.opt("role");if(textRole==null)textRole=data.opt("text_role");
        JSONObject result=new JSONObject(data.toString()).put("page",pageName).put("state",state).put("theme",theme).put("id",id).put("key_id",id.split("/label")[0]).put("kind",group.equals("character")?"keycap":group.equals("function")?kind.equals("panel_button")?kind:"function":group.equals("icon")?"icon":kind).put("text",text).put("label_class",group).put("typeface",isBundled?"bundled":"system").put("glyph_source",glyphSource).put("has_glyph",has).put("text_sp",sizeSp(v)).put("text_px",v.getTextSize()).put("text_color",v.getCurrentTextColor()).put("bg_color",background(cap,data)).put("role",role==null?JSONObject.NULL:role).put("text_role",textRole==null?JSONObject.NULL:textRole).put("bounds",new JSONArray(new int[]{box.left,box.top,box.right,box.bottom})).put("visible_bounds",new JSONArray(new int[]{vr.left,vr.top,vr.right,vr.bottom})).put("label_bounds",new JSONArray(in)).put("clipped",in[0]<vr.left||in[1]<vr.top||in[2]>vr.right||in[3]>vr.bottom).put("line_count",v.getClass().getSimpleName().equals("DualHandLabelView")?1:v.getLineCount()).put("ellipsized",v.getEllipsize()!=null).put("fixed",!group.equals("content")).put("source",source).put("monochrome",monochrome(v)).put("icon_visible_px",group.equals("icon")?Math.max(in[2]-in[0],in[3]-in[1]):0).put("candidate_path",kind.equals("candidate")?(pageName.equals("english")?"C":pageName.equals("dual_hand")?"dual":state.equals("candidate_A")?"A":"B"):JSONObject.NULL).put("selected",v.isSelected()).put("padding",new JSONArray(new int[]{v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),v.getPaddingBottom()})).put("color_function",role==null?"unknown":"palette").put("corner_dp",data.optDouble("corner_dp",corner(cap)/d)).put("elevation_dp",cap.getElevation()/d).put("bg_factory",data.optBoolean("bg_factory",cap.getBackground()!=null&&cap.getBackground().getClass().getName().endsWith("KeyboardKeycaps$Cap"))).put("highlight_line",data.optBoolean("highlight_line",false)).put("diamond",false).put("diamond_max_delta",0).put("guide_dot",data.optBoolean("guide_dot",false)).put("guide_dot_pixel",JSONObject.NULL).put("environment",new JSONObject().put("width_dp",root.getWidth()/d).put("density",d).put("font_scale",sd/d));
        Object fit=optionalField(optionalField(service,"dualHandPage"),"labelSizeSp");result.put("fitted_sp",group.equals("character")?pageName.equals("dual_hand")&&fit instanceof Number?fit:JSONObject.NULL:JSONObject.NULL);
        if(group.equals("function")||group.equals("character")||group.equals("icon"))text.codePoints().forEach(cp->chars.add(new String(Character.toChars(cp))));return result;
    }
    private float corner(View v){Drawable bg=v.getBackground();while(bg instanceof InsetDrawable)bg=((InsetDrawable)bg).getDrawable();return bg instanceof GradientDrawable?((GradientDrawable)bg).getCornerRadius():0;}
    private float[] toFloats(JSONArray a)throws Exception{float[] v=new float[a.length()];for(int i=0;i<v.length;i++)v[i]=(float)a.getDouble(i);return v;}
    private void walk(View view,String path,String kind)throws Exception {
        if(!view.isShown())return;List<Map<String,Object>> descriptions=null;
        try{Class<?> source=api("KeyboardProbe$Source");if(source.isInstance(view)){@SuppressWarnings("unchecked")List<Map<String,Object>> d=(List<Map<String,Object>>)call(view,"describe");descriptions=d;}}catch(ClassNotFoundException absent){}
        Object routing=view.getTag();boolean cap=routing instanceof String&&(((String)routing).startsWith("dh-key:")||((String)routing).startsWith("key:"));
        if(descriptions!=null&&view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;Map<String,JSONObject> byDirection=new HashMap<>();for(Map<String,Object> m:descriptions)if(m.containsKey("direction"))byDirection.put(m.get("direction").toString(),new JSONObject(m));String[] dirs={"tap","up","left","down","right"};List<JSONObject> collected=new ArrayList<>();for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i) instanceof TextView&&group.getChildAt(i).isShown()){TextView label=(TextView)group.getChildAt(i);JSONObject m=byDirection.getOrDefault(i<5?dirs[i]:"",new JSONObject());m.put("guide_dot",new JSONObject(descriptions.get(0)).optBoolean("guide_dot",false));collected.add(row(label,view,path+"/label"+i,m.optString("kind",kind),m,"api"));}pixels(view,collected);for(JSONObject r:collected)rows.put(r);return;}
        if(cap&&view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;List<JSONObject> collected=new ArrayList<>();for(int i=0;i<group.getChildCount();i++)if(group.getChildAt(i) instanceof TextView&&group.getChildAt(i).isShown())collected.add(row((TextView)group.getChildAt(i),view,path+"/label"+i,"keycap",new JSONObject().put("guide_dot",mainGuideDot(view)),"reflection_fallback"));pixels(view,collected);for(JSONObject r:collected)rows.put(r);return;}
        if(view instanceof TextView){TextView label=(TextView)view;String observedKind=kind;String resource="";try{resource=view.getResources().getResourceEntryName(view.getId());}catch(Exception none){}
            if(kind.equals("candidate")||"candidate".equals(tag(view,"keyboard_content_kind"))||resource.startsWith("enSuggest")||resource.startsWith("boCandidate")||resource.startsWith("boWordCandidate")||path.contains("dh-candidates"))observedKind="candidate";
            else if(resource.contains("preview")||resource.equals("statusText"))observedKind="status";
            else if("content".equals(tag(view,"keyboard_content_kind")))observedKind="content";else if(kind.equals("capsule")||kind.equals("grid_cell"))observedKind=kind;else if(cap||view.isClickable())observedKind="function";
            else observedKind="content";
            JSONObject r=row(label,view,path,observedKind,new JSONObject(),"view");if(cap)pixels(view,Collections.singletonList(r));rows.put(r);return;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);String tag=child.getTag()==null?"":child.getTag().toString();String resource="";try{resource=child.getResources().getResourceEntryName(child.getId());}catch(Exception absent){}String next=tag.equals("dh-candidates")||resource.equals("boCandidateItems")||resource.equals("boWordCandidateItems")?"candidate":kind;walk(child,path+"/"+(tag.isEmpty()?i:tag),next);}}
    }
    private boolean mainGuideDot(View cap)throws Exception {String key=cap.getTag()==null?"":cap.getTag().toString();if(!key.startsWith("dh-key:")||!Arrays.asList("1","2","3","4","5","6","A","B","C","D","E","F","Ø").contains(key.substring(7)))return false;return ((String)call(dual(),"output",key.substring(7),"tap")).isEmpty();}
    private void pixels(View cap,List<JSONObject> labels)throws Exception {
        if(cap.getWidth()<=0||cap.getHeight()<=0)return;Bitmap image=Bitmap.createBitmap(cap.getWidth(),cap.getHeight(),Bitmap.Config.ARGB_8888);cap.draw(new Canvas(image));Rect at=rect(cap);List<float[]> excluded=new ArrayList<>();for(JSONObject r:labels){float[] b=toFloats(r.getJSONArray("label_bounds"));excluded.add(new float[]{b[0]-at.left,b[1]-at.top,b[2]-at.left,b[3]-at.top});}excluded.add(new float[]{cap.getWidth()/2f-5*density(),cap.getHeight()/2f-5*density(),cap.getWidth()/2f+5*density(),cap.getHeight()/2f+5*density()});int[] p=new int[image.getWidth()*image.getHeight()];image.getPixels(p,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());int[] diamond=App690FPixels.diamond(p,image.getWidth(),image.getHeight(),density(),excluded.toArray(new float[0][]));for(JSONObject r:labels){if(r.getString("label_class").equals("character")){r.put("diamond_max_delta",diamond[0]).put("diamond_samples",diamond[1]).put("diamond",diamond[0]>2);}int inset=tag(cap,"keyboard_visible_inset") instanceof Number?Math.round(((Number)tag(cap,"keyboard_visible_inset")).floatValue()*density()):0;int y=Math.min(image.getHeight()-1,inset);r.put("highlight_line",App690FPixels.delta(image.getPixel(image.getWidth()/2,y),image.getPixel(image.getWidth()/2,Math.min(image.getHeight()-1,y+Math.max(1,Math.round(2*density())))))>2);if(r.optBoolean("guide_dot"))r.put("guide_dot_pixel",image.getPixel(image.getWidth()/2,image.getHeight()/2));int edge=Math.max(1,Math.round(5*density()));JSONArray samples=new JSONArray();for(int sx:new int[]{edge,image.getWidth()-edge-1})for(int sy:new int[]{edge,image.getHeight()-edge-1}){boolean skip=false;for(float[] b:excluded)if(sx>=b[0]-2&&sx<=b[2]+2&&sy>=b[1]-2&&sy<=b[3]+2)skip=true;if(!skip&&sx>=0&&sy>=0&&sx<image.getWidth()&&sy<image.getHeight())samples.put(new JSONObject().put("x",sx).put("y",sy).put("argb",image.getPixel(sx,sy)));}r.put("background_samples",samples).put("sampled_bg_color",image.getPixel(Math.min(image.getWidth()-1,edge),Math.max(0,image.getHeight()-edge))).put("pixel_source","View.draw bitmap; screenshots retained separately");}image.recycle();
    }
    private void screenshot(String name)throws Exception {
        Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull("hardware screenshot",image);
        try(java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(output,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}
    }
    private void capture(View view,String name)throws Exception {
        state=name;coverage.put(new JSONObject().put("page",pageName).put("state",state).put("theme",theme));
        main(()->{int start=rows.length();walk(view,pageName,"keycap");if(!pageName.equals("dual_hand")){
            try{List<Object> labels=new ArrayList<>();Class<?> labelType=api("KeyboardLabels$Label");float d=density();for(int i=start;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.getString("label_class").equals("character")){JSONArray b=r.getJSONArray("visible_bounds");labels.add(labelType.getConstructor(float.class,float.class,float.class,String[].class).newInstance((float)(b.getDouble(2)-b.getDouble(0))-2*Math.round(2*d),(float)(b.getDouble(3)-b.getDouble(1))-2*Math.round(d),0f,new String[]{r.getString("text"),"","","",""}));}}if(!labels.isEmpty()){Paint paint=new Paint();paint.setTypeface(bundled);float fitted=((Number)call(api("KeyboardLabels"),"fit",paint,view.getResources().getDisplayMetrics().scaledDensity,labels)).floatValue();for(int i=start;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);if(r.getString("label_class").equals("character"))r.put("fitted_sp",fitted).put("fit_source","shared_fit_actual_visible_geometry");}}}catch(ClassNotFoundException baseline){}
        }return null;});
        screenshot(pageName+"-"+state+"-"+theme);
    }
    private JSONArray missingStates()throws Exception {
        Set<String> seen=new HashSet<>();for(int i=0;i<coverage.length();i++){JSONObject c=coverage.getJSONObject(i);seen.add(c.getString("page")+"/"+c.getString("state")+"/"+c.getString("theme"));}
        JSONArray missing=new JSONArray();for(String t:new String[]{"dark","light"}){for(String p:new String[]{"dual_hand","bopomofo","voice","english","numbers"})if(!seen.contains(p+"/idle/"+t))missing.put(p+"/idle/"+t);for(String mode:new String[]{"append","replace","spell","translate"})for(String state:new String[]{"standby_","recording_"})if(!seen.contains("voice/"+state+mode+"/"+t))missing.put("voice/"+state+mode+"/"+t);for(String p:new String[]{"dual_hand","bopomofo","voice"})for(String state:new String[]{"capsule","grid"})if(!seen.contains(p+"/"+state+"/"+t))missing.put(p+"/"+state+"/"+t);}return missing;
    }
    public void testCollectAllStyles()throws Exception {
        try{try(java.io.InputStream in=inst.getTargetContext().getAssets().open("fonts/SUBSET_MANIFEST.json")){manifest=new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}}catch(java.io.FileNotFoundException baseline){}
        try{bundled=(Typeface)main(()->call(api("KeyboardTypeface"),"get",inst.getTargetContext()));}catch(RuntimeException absent){bundled=(Typeface)main(()->field(dual(),"typeface"));}
        JSONObject palette=palette();JSONObject before=counters();
        try{for(String t:new String[]{"dark","light"}){theme=t;inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("keyboard_theme",theme).commit();main(()->{call(service,"onStartInputView",call(service,"getCurrentInputEditorInfo"),true);return null;});
            for(String name:new String[]{"dual_hand","bopomofo","voice","english","numbers"}){pageName=name;View v=page(name);capture(v,"idle");
                if(name.equals("english"))for(String shift:new String[]{"lower","upper","caps_lock"}){main(()->{set(service,"shiftActive",!shift.equals("lower"));set(service,"capsLock",shift.equals("caps_lock"));call(service,"updateShiftUI");return null;});capture(v,shift);}
                if(name.equals("voice"))for(String mode:new String[]{"APPEND","REPLACE","SPELL","TRANSLATE"}){main(()->{for(Object x:api("SimonIMEService$Mode").getEnumConstants())if(x.toString().equals(mode))set(service,"currentMode",x);call(service,"updateModeUI");return null;});capture(v,"standby_"+mode.toLowerCase());
                    main(()->{call(service,"startRecording");return null;});
                    try{if(main(()->(Boolean)field(service,"isRecording")))capture(v,"recording_"+mode.toLowerCase());}
                    finally{main(()->{if((Boolean)field(service,"isRecording"))call(service,"stopRecordingAndSend");return null;});Thread.sleep(150);}
                }
                if(name.equals("dual_hand")){for(String pending:new String[]{"ㄅ","ㄐ","ㄧ","ㄨ","ㄩ"}){main(()->{Object b=call(dual(),"buffer");call(b,"clearBuffer");String k=pending.equals("ㄅ")?"1":pending.equals("ㄐ")?"4":pending.equals("ㄩ")?"6":pending.equals("ㄨ")?"3":"4",dir=pending.equals("ㄧ")||pending.equals("ㄩ")||pending.equals("ㄨ")?"right":"tap";Object stroke=call(b,"begin",k,100L);call(b,"end",stroke,dir,false);call(b,"drain",200L);assertEquals("observed pending state",pending,call(b,"pending"));call(dual(),"refresh");return null;});inst.waitForIdleSync();capture(v,"pending_"+pending);}main(()->{call(call(dual(),"buffer"),"clearBuffer");call(dual(),"refresh");return null;});}
                List<View> available=main(()->keys(v));for(int i=0;i<Math.min(3,available.size());i++){View key=available.get(i);JSONObject c0=counters();long down=SystemClock.uptimeMillis();touch(key,MotionEvent.ACTION_DOWN,down,0,0);Thread.sleep(60);float pressed=main(()->key.getScaleX());touch(key,MotionEvent.ACTION_CANCEL,down,0,0);Thread.sleep(250);float restored=main(()->key.getScaleX());presses.put(new JSONObject().put("page",name).put("theme",theme).put("index",i).put("scale_pressed",pressed).put("scale_restored",restored).put("before",c0).put("after",counters()));}
                if(name.equals("english")){main(()->{System.arraycopy(new String[]{"one","two","three"},0,(String[])field(service,"enSuggestions"),0,3);call(service,"updateSuggestionBar");return null;});inst.waitForIdleSync();capture(v,"candidate_C");}
                if(name.equals("dual_hand")){main(()->{Object b=call(dual(),"buffer"),stroke=call(b,"begin","Ø",300L);call(b,"end",stroke,"right",false);call(b,"drain",500L);call(dual(),"refresh");return null;});Thread.sleep(300);capture(v,"candidate_dual");main(()->{Object b=call(dual(),"buffer");call(b,"clearBuffer");for(int i=0;i<9;i++)for(String key:new String[]{"1","A"}){long time=1000L+i*400+(key.equals("A")?100:0);Object stroke=call(b,"begin",key,time);call(b,"end",stroke,"tap",false);call(b,"drain",time+100);}call(dual(),"refresh");return null;});inst.waitForIdleSync();capture(v,"over8");main(()->{call(call(dual(),"buffer"),"applyAi","一".repeat(9));call(dual(),"refresh");return null;});inst.waitForIdleSync();capture(v,"ai_shown");main(()->{call(call(dual(),"buffer"),"clearBuffer");call(dual(),"refresh");return null;});}
                if(name.equals("bopomofo")){main(()->{call(service,"renderZhuyinCandidates",Arrays.asList("一","測試","三字詞"),"char");return null;});inst.waitForIdleSync();capture(v,"candidate_B");main(()->{android.widget.LinearLayout row=(android.widget.LinearLayout)field(service,"boCandidateItems");row.removeAllViews();java.util.List<TextView> pool=new ArrayList<>();for(String value:new String[]{"一","測試","三字詞"}){TextView chip=(TextView)call(service,"pooledTextChoice",pool,pool.size(),new ZhuyinInputController.TextChoice(value,"char","","",0,0));row.addView(chip);}return null;});inst.waitForIdleSync();capture(v,"candidate_A");}
            }
            for(String corrected:new String[]{"dual_hand","bopomofo","voice"}){
                pageName=corrected;View current=page(corrected);text("測試文字");Thread.sleep(550);selection(4);Thread.sleep(550);selection(2);View marker=main(()->byDescription("開啟校正選字"));
                if(marker!=null){state="capsule";coverage.put(new JSONObject().put("page",pageName).put("state",state).put("theme",theme));main(()->{walk(marker,pageName+"/capsule","capsule");return null;});tap(marker);Thread.sleep(100);state="grid";coverage.put(new JSONObject().put("page",pageName).put("state",state).put("theme",theme));main(()->{for(View window:windows())if(window!=root.getRootView()&&window.isShown())walk(window,pageName+"/grid","grid_cell");return null;});}
                else {state="correction_unavailable";}
            }
            pageName="symbols";main(()->{call(service,"dualHandPanel","symbols");return null;});inst.waitForIdleSync();capture(root,"fullwidth_symbols");
            for(String panel:new String[]{"clipboard","commands"}){pageName=panel;main(()->{call(service,panel.equals("clipboard")?"showClipboardPanel":"showCommandsPanel");return null;});inst.waitForIdleSync();capture(root,"panel");}
            main(()->{call(service,"closePanel");return null;});
        }
        }finally{JSONObject glyphs=new JSONObject();Paint system=new Paint();system.setTypeface(Typeface.DEFAULT);String missing=manifest.optString("source_missing_characters","⌫⚙");Set<String> fallback=new LinkedHashSet<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("label_class").equals("icon")&&(row.optString("glyph_source").equals("fallback")||row.optString("glyph_source").equals("vector")))fallback.add(row.getString("text"));}for(int cp:missing.codePoints().toArray()){String ch=new String(Character.toChars(cp));if(cp!=0xfe0f&&fallback.stream().noneMatch(g->g.contains(ch)))fallback.add(ch);}for(String glyph:fallback)glyphs.put(glyph,system.hasGlyph(glyph));
        save("style-probe.json",new JSONObject().put("schema",690).put("rows",rows).put("coverage",coverage).put("palette",palette).put("counters",counters()).put("counters_before",before).put("presses",presses).put("fallback_glyphs",glyphs).put("missing_states",missingStates()));
        save("PROBE_KEYCAP_CHARS.txt",String.join("",chars));}
    }
    public void testCollectIdleFontSizes()throws Exception {
        bundled=(Typeface)main(()->field(dual(),"typeface"));
        for(String t:new String[]{"dark","light"}){theme=t;inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("keyboard_theme",theme).commit();main(()->{call(service,"onStartInputView",call(service,"getCurrentInputEditorInfo"),true);return null;});
            for(String name:new String[]{"dual_hand","bopomofo","voice","english","numbers"}){pageName=name;capture(page(name),"idle");}}
        save("idle-font-probe.json",new JSONObject().put("schema",690).put("rows",rows).put("coverage",coverage).put("palette",palette()).put("counters",counters()));
    }
}
