package com.simon.voiceime;

import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Real Android line geometry and real-host input receipts for FIX1. */
public class App690Fix1Test extends App690FSupport {
    private JSONObject ink(TextView v) throws Exception {
        Rect b=new Rect();String text=v.getText().toString();v.getPaint().getTextBounds(text,0,text.length(),b);
        int baseline=v.getBaseline()-v.getScrollY();
        int left=v.getCompoundPaddingLeft()-v.getScrollX()+Math.round(v.getLayout().getLineLeft(0))+b.left;
        int top=baseline+b.top,right=left+b.width(),bottom=baseline+b.bottom;
        float inset=v.getTag(inst.getTargetContext().getResources().getIdentifier("keyboard_visible_inset","id","com.simon.voiceime")) instanceof Number?((Number)v.getTag(inst.getTargetContext().getResources().getIdentifier("keyboard_visible_inset","id","com.simon.voiceime"))).floatValue()*density():0;
        Paint.FontMetrics fm=v.getPaint().getFontMetrics();
        return new JSONObject().put("text",text).put("sp",sizeSp(v)).put("px",v.getTextSize()).put("width",v.getWidth()).put("height",v.getHeight()).put("baseline",baseline).put("scrollY",v.getScrollY()).put("font",new JSONArray(new float[]{fm.top,fm.ascent,fm.descent,fm.bottom})).put("ink",new JSONArray(new int[]{left,top,right,bottom})).put("lines",v.getLineCount()).put("clipped",left<inset||top<inset||right>v.getWidth()-inset||bottom>v.getHeight()-inset).put("visible_px",Math.max(b.width(),b.height()));
    }
    public void testCharacterInkInsideActualKeys() throws Exception {
        JSONArray rows=new JSONArray();int bad=0;
        for(String p:new String[]{"bopomofo","english","numbers"}) {
            View surface=page(p);
            JSONArray values=main(()->{JSONArray a=new JSONArray();for(View v:keys(surface))if(v instanceof TextView){Object kind=v.getTag(inst.getTargetContext().getResources().getIdentifier("keyboard_content_kind","id","com.simon.voiceime"));if("character".equals(kind)||"right".equals(v.getTag(inst.getTargetContext().getResources().getIdentifier("keyboard_role","id","com.simon.voiceime"))))a.put(ink((TextView)v).put("page",p));}return a;});
            assertTrue("character keys collected "+p,values.length()>0);
            for(int i=0;i<values.length();i++){JSONObject q=values.getJSONObject(i);rows.put(q);if(q.getBoolean("clipped")||q.getInt("lines")!=1)bad++;}
        }
        save("fix1-character-ink.json",rows);assertEquals("all character ink inside actual keys",0,bad);
    }
    public void testVoiceIconsMeet6891() throws Exception {
        View surface=page("voice");JSONArray rows=new JSONArray();int bad=0;
        for(String name:new String[]{"btnMic","btnSpace"})for(String label:name.equals("btnMic")?new String[]{"🎤","⏹"}:new String[]{"␣"}){
            TextView v=(TextView)surface.findViewById(inst.getTargetContext().getResources().getIdentifier(name,"id","com.simon.voiceime"));
            main(()->{v.setText(label);return null;});inst.waitForIdleSync();JSONObject q=main(()->ink(v));rows.put(q);
            if(q.getBoolean("clipped")||q.getInt("visible_px")<(label.equals("🎤")?91:label.equals("⏹")?87:0))bad++;
        }
        save("fix1-voice-icons.json",rows);assertEquals("unclipped icons at least baseline visible size",0,bad);
    }
    public void testVoiceIconFrameThreshold() throws Exception {
        View surface=page("voice");TextView v=(TextView)surface.findViewById(inst.getTargetContext().getResources().getIdentifier("btnMic","id","com.simon.voiceime"));JSONArray values=new JSONArray();
        for(float sp:new float[]{28,28.25f,28.5f,28.75f,29})for(String text:new String[]{"🎤","⏹"}){
            main(()->{v.setText(text);v.setTextSize(sp);return null;});inst.waitForIdleSync();values.put(main(()->ink(v)));
        }
        save("fix1-icon-threshold.json",values);
    }
    public void testLandscapeSplitDecision() throws Exception {
        page("dual_hand");shell("settings put system accelerometer_rotation 0; settings put system user_rotation 1");Thread.sleep(1600);inst.waitForIdleSync();service=main(()->((java.lang.ref.WeakReference<?>)field(api("SimonIMEService"),"notificationVoiceService")).get());root=main(()->(View)field(service,"rootView"));page("dual_hand");
        JSONObject q=main(()->{Object keyboard=dual();android.content.res.Configuration c=root.getResources().getConfiguration();android.util.DisplayMetrics m=root.getResources().getDisplayMetrics();return new JSONObject().put("orientation",c.orientation).put("screenHeightDp",c.screenHeightDp).put("width",root.getWidth()).put("density",m.density).put("scaledDensity",m.scaledDensity).put("height_gate",182<=c.screenHeightDp*.55f).put("row_gate",DualHandVisuals.landscapeRowDp(c.screenHeightDp)).put("preference",inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).getBoolean("dual_hand_landscape_split",true)).put("failedSplitKey",field(keyboard,"failedSplitKey")).put("split",call(keyboard,"landscapeSplit"));});
        JSONArray trials=main(()->{Object keyboard=dual();boolean old=(Boolean)field(keyboard,"split");float row=(Float)field(keyboard,"rowDp");JSONArray a=new JSONArray();try{set(keyboard,"split",true);for(float h:new float[]{50,51,52,53,54,55,60,80}){set(keyboard,"rowDp",h);JSONObject t=new JSONObject().put("rowDp",h);try{t.put("fit",call(keyboard,"fitLabelSize",((View)call(keyboard,"view")).getWidth()));}catch(Exception e){t.put("rejected",e.toString());}a.put(t);}}finally{set(keyboard,"split",old);set(keyboard,"rowDp",row);}return a;});q.put("fit_trials",trials);q.put("first_refusal",main(()->{Object keyboard=dual();android.util.DisplayMetrics m=root.getResources().getDisplayMetrics();boolean was=(Boolean)field(keyboard,"split");set(keyboard,"split",true);try{int[] widths=(int[])call(keyboard,"columnWidths",((View)call(keyboard,"view")).getWidth());Paint p=new Paint();p.setTypeface((Typeface)field(keyboard,"typeface"));p.setTextSize(12*m.scaledDensity);DualHandTable table=(DualHandTable)field(keyboard,"table");List<String> states=new ArrayList<>();states.add(null);states.addAll(Arrays.asList(DualHandTable.STATES));for(String state:states)for(String key:DualHandTable.RIGHT_KEYS){String[] texts=new String[5];float[][] sizes=new float[5][];for(int i=0;i<5;i++){String text=table.right(state,key,DualHandTable.DIRECTIONS[i]);if(!text.isEmpty())text=(String)call(keyboard,"suffix",state,text);texts[i]=text;sizes[i]=KeyboardLabels.extent(text,p);}float width=(Float)call(keyboard,"keyContentWidth",key,widths),height=Math.round(50*m.density)-2*Math.round(2*m.density)-2*Math.round(m.density);if(!KeyboardLabels.fits(width,height,sizes,0))return new JSONObject().put("state",state).put("key",key).put("width",width).put("height",height).put("sp",12).put("texts",new JSONArray(texts)).put("sizes",new JSONArray(sizes));}return new JSONObject();}finally{set(keyboard,"split",was);}}));save("fix1-landscape-decision.json",q);assertEquals(2,q.getInt("orientation"));assertTrue("landscape split actually active: "+q,q.getBoolean("split"));
    }
}
