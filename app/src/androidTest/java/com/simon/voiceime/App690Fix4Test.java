package com.simon.voiceime;
import android.os.*;import android.graphics.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;import org.json.*;import java.util.*;import java.util.concurrent.*;
/** Independent requirements: fixed symbol cells bundled; physical capsule within2dp of host Layout. */
public class App690Fix4Test extends App690FSupport {
    private void labels(View v,JSONArray rows,String path)throws Exception {
        if(v instanceof TextView&&"symbol".equals(v.getTag(inst.getTargetContext().getResources().getIdentifier("keyboard_content_kind","id",inst.getTargetContext().getPackageName())))){
            TextView t=(TextView)v;rows.put(new JSONObject().put("text",t.getText()).put("path",path).put("typeface",(Boolean)call(api("KeyboardTypeface"),"bundled",t.getTypeface())?"bundled":"system"));
        }
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)labels(((ViewGroup)v).getChildAt(i),rows,path+"/"+i);
    }
    private void injectSerif(View v){if(v instanceof TextView)((TextView)v).setTypeface(android.graphics.Typeface.SERIF);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)injectSerif(((ViewGroup)v).getChildAt(i));}
    public void testFixedSymbols()throws Exception {
        JSONArray rows=new JSONArray();try{for(String theme:new String[]{"dark","light"}){
            inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("keyboard_theme",theme).commit();page("dual_hand");
            main(()->{call(service,"dualHandPanel","symbols");labels((View)field(service,"panelContainer"),rows,theme+"/dualHandPanel");View panel=(View)field(service,"panelContainer");injectSerif(panel);call(service,"styleDPanel",panel);labels(panel,rows,theme+"/dualHandPanel/serif");call(service,"closePanel");
                View popup=(View)call(service,"buildSymbolPopupContent",new String[]{"，","。","？"},3,"，");call(service,"styleDPanel",popup);labels(popup,rows,theme+"/popup");injectSerif(popup);call(service,"styleDPanel",popup);labels(popup,rows,theme+"/popup/serif");return null;});
        }}finally{save("fix4-symbols.json",rows);}
        assertTrue(rows.length()>=12);for(int i=0;i<rows.length();i++)assertEquals(rows.getJSONObject(i).toString(),"bundled",rows.getJSONObject(i).getString("typeface"));
    }
    public void testPhysicalPosition()throws Exception {
        Bundle args=androidx.test.platform.app.InstrumentationRegistry.getArguments();String page=args.getString("fix4_page","voice");boolean top=args.getString("fix4_top","false").equals("true");page(page);if(top)hostCall("top",new Bundle());text("保稅區");Thread.sleep(500);
        JSONObject before=hostCall("state",new Bundle());JSONArray box=before.getJSONArray("editor_bounds");int y=(box.getInt(1)+box.getInt(3))/2;
        for(int x:new int[]{box.getInt(0)+34,box.getInt(0)+92}){long down=SystemClock.uptimeMillis();for(int a:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),a,x,y,0);inst.getUiAutomation().injectInputEvent(e,true);e.recycle();}Thread.sleep(300);}
        JSONObject truth=hostCall("state",new Bundle());View marker=main(()->byDescription("開啟校正選字"));assertNotNull("physical host selection capsule "+page,marker);
        JSONObject row=new JSONObject().put("page",page).put("top",top).put("truth",truth);Rect actual=main(()->rect(marker));row.put("actual",new JSONArray(new int[]{actual.left,actual.top,actual.right,actual.bottom}));
        JSONArray b=truth.getJSONArray("character_bounds"),v=truth.getJSONArray("visible");float d=(float)truth.getDouble("density");int[] expected=App690FPixels.capsule((float)b.getDouble(0),(float)b.getDouble(1),(float)b.getDouble(3),(float)v.getDouble(1),(float)v.getDouble(3),root.getResources().getDisplayMetrics().widthPixels,d);row.put("expected",new JSONArray(expected));
        main(()->{Object c=page.equals("dual_hand")?field(dual(),"hooks"):field(service,"pageCorrection");CursorAnchorInfo a=(CursorAnchorInfo)field(c,"anchor");int at=truth.getInt("index");RectF cb=a.getCharacterBounds(at);row.put("anchor",a.toString()).put("characterBoundsNull",cb==null).put("insertionX",a.getInsertionMarkerHorizontal());return null;});
        if(Build.VERSION.SDK_INT>=34){CountDownLatch done=new CountDownLatch(1);main(()->{InputConnection c=(InputConnection)call(service,"getCurrentInputConnection");c.requestTextBoundsInfo(new RectF(0,0,1050,2376),Runnable::run,result->{try{row.put("boundsResultCode",result.getResultCode());TextBoundsInfo info=result.getTextBoundsInfo();if(info!=null){int at=truth.getInt("index");RectF bounds=new RectF();info.getCharacterBounds(at,bounds);Matrix matrix=new Matrix();info.getMatrix(matrix);matrix.mapRect(bounds);row.put("requestedBounds",new JSONArray(new float[]{bounds.left,bounds.top,bounds.right,bounds.bottom}));}}catch(Exception e){try{row.put("boundsError",e.toString());}catch(Exception ignored){}}finally{done.countDown();}});return null;});assertTrue("bounds callback",done.await(2,TimeUnit.SECONDS));}
        float errorX=Math.abs(actual.left-expected[0])/d,errorY=Math.abs(actual.top-expected[1])/d;row.put("error_dp",new JSONArray(new float[]{errorX,errorY}));save("fix4-position.json",row);
        assertTrue(page+" x error<=2dp "+row,errorX<=2);assertTrue(page+" y error<=2dp "+row,errorY<=2);assertTrue("complete visible capsule",actual.left>=0&&actual.right<=1050&&actual.top>=v.getInt(1)&&actual.bottom<=v.getInt(3));
        if(top){assertTrue("top fixture under20dp",b.getDouble(1)-v.getDouble(1)<20*d);assertTrue("below character",actual.top>=b.getDouble(3)-12*d);}
    }
    public void testKeyTimingDiagnostic()throws Exception {
        View surface=page("bopomofo");JSONArray dispatches=new JSONArray(),keysReadback=new JSONArray();JSONObject before=counters();final int[] keyIndex={-1};final long[] begin={0};final String[] header={""};android.util.Printer previous=main(()->(android.util.Printer)field(Looper.getMainLooper(),"mLogging"));
        main(()->{Looper.getMainLooper().setMessageLogging(line->{long now=SystemClock.uptimeMillis();previous.println(line);if(line.startsWith(">>>>>")){begin[0]=now;header[0]=line;}else if(line.startsWith("<<<<<")){long ms=now-begin[0];if(ms>=20)try{dispatches.put(new JSONObject().put("key_index",keyIndex[0]).put("ms",ms).put("header",header[0]).put("fit",call(api("KeyboardLabels"),"fitCount")));}catch(Exception e){throw new RuntimeException(e);}}});return null;});
        try{List<View> inputs=main(()->{List<View> valid=new ArrayList<>();for(View key:keys(surface)){Object t=key.getTag();if(t!=null&&t.toString().startsWith("key:")&&t.toString().substring(4).codePointCount(0,t.toString().substring(4).length())==1)valid.add(key);}return valid;});
            for(int i=0;i<30;i++){keyIndex[0]=i;View key=inputs.get(i%inputs.size());JSONObject row=new JSONObject().put("index",i).put("key",main(()->key.getTag().toString())).put("before",counters()).put("start_epoch_ms",System.currentTimeMillis());tap(key);row.put("end_epoch_ms",System.currentTimeMillis()).put("after",counters());keysReadback.put(row);}
        }finally{main(()->{Looper.getMainLooper().setMessageLogging(previous);return null;});save("fix4-key-diag.json",new JSONObject().put("before",before).put("after",counters()).put("dispatches",dispatches).put("keys",keysReadback).put("pid",android.os.Process.myPid()));}
    }

}
