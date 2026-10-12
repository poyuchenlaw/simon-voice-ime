package com.simon.voiceime;

import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import android.text.InputType;
import org.json.*;import java.util.*;import java.io.*;import java.security.*;

/** Device assertions use a real external editor and host Layout truth, not the IME anchor. */
public class App690CorrectionTest extends App690FSupport {
    private final JSONArray evidence=new JSONArray();
    private View marker()throws Exception {return main(()->byDescription("開啟校正選字"));}
    private List<TextView> grid()throws Exception {
        return main(()->{List<TextView> result=new ArrayList<>();for(View v:windows())if(v!=root.getRootView())gridViews(v,result);return result;});
    }
    private void gridViews(View v,List<TextView> result){if(!v.isShown())return;if(v instanceof TextView&&v.isClickable()&&!((TextView)v).getText().toString().isEmpty()&&v.getContentDescription()==null)result.add((TextView)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)gridViews(((ViewGroup)v).getChildAt(i),result);}
    private JSONObject locate(String page,boolean top)throws Exception {
        page(page);if(top)hostCall("top",new Bundle());text("測試文字");Thread.sleep(600);selection(4);Thread.sleep(550);JSONObject truth=selection(2);
        View m=marker();assertNotNull(page+": real external selection must show capsule",m);Rect actual=main(()->rect(m));JSONArray b=truth.getJSONArray("character_bounds"),visible=truth.getJSONArray("visible");float d=(float)truth.getDouble("density");int[] expected=App690FPixels.capsule((float)b.getDouble(0),(float)b.getDouble(1),(float)b.getDouble(3),(float)visible.getDouble(1),(float)visible.getDouble(3),root.getResources().getDisplayMetrics().widthPixels,d);
        assertTrue(page+" x error<=2dp",Math.abs(actual.left-expected[0])<=2*d);assertTrue(page+" y error<=2dp",Math.abs(actual.top-expected[1])<=2*d);assertTrue(actual.top>=visible.getInt(1)&&actual.bottom<=visible.getInt(3));assertTrue(actual.left>=0&&actual.right<=root.getResources().getDisplayMetrics().widthPixels);assertEquals(Math.round(32*d),actual.width());assertEquals(Math.round(24*d),actual.height());
        if(top){assertTrue("near-top truth must be below20dp",b.getDouble(1)-visible.getDouble(1)<20*d);assertTrue("capsule switched below top character",actual.top>=b.getDouble(3)-12*d);}
        return new JSONObject().put("page",page).put("top",top).put("truth",truth).put("actual",new JSONArray(new int[]{actual.left,actual.top,actual.right,actual.bottom})).put("expected",new JSONArray(expected));
    }
    public void testR7aThreePagesAndNearTopIndependentLayout()throws Exception {
        try{for(String p:new String[]{"dual_hand","bopomofo","voice"})evidence.put(locate(p,false));for(String p:new String[]{"dual_hand","bopomofo","voice"})evidence.put(locate(p,true));}finally{save("correction-position.json",evidence);}
    }
    public void testR7cSameCommittedTextGridAndR7dReplacementProtection()throws Exception {
        List<String> reference=null;
        for(String p:new String[]{"dual_hand","bopomofo","voice"}){locate(p,false);View m=marker();tap(m);Thread.sleep(150);List<TextView> cells=grid();assertTrue("grid nonempty "+p,!cells.isEmpty());List<String> values=new ArrayList<>();for(TextView cell:cells)values.add(main(()->cell.getText().toString()));if(reference==null)reference=values;else assertEquals("three pages must use same fallback/grid ranking",reference,values);
            JSONObject before=hostCall("state",new Bundle());TextView chosen=cells.get(0);String replacement=main(()->chosen.getText().toString());tap(chosen);JSONObject after=hostCall("state",new Bundle());String original=before.getString("text");assertEquals(original.substring(0,1)+replacement+original.substring(2),after.getString("text"));assertEquals(before.getInt("start")+replacement.length()-1,after.getInt("start"));
            locate(p,false);tap(marker());Thread.sleep(150);cells=grid();assertFalse(cells.isEmpty());TextView stale=cells.get(0);text("宿主已變動");main(()->{stale.performClick();return null;});assertEquals("宿主已變動",hostCall("state",new Bundle()).getString("text"));
        }
    }
    public void testR7bExactReadingVersusVoiceFallback()throws Exception {
        page("bopomofo");text("");for(String key:new String[]{"ㄧ","ㄣ","ˊ","ㄏ","ㄤ","ˊ"}){main(()->{call(service,"onTypingKeyPressed",key);return null;});Thread.sleep(80);}main(()->{call(service,"onTypingKeyPressed","enter");return null;});Thread.sleep(300);
        assertEquals("known polyphonic fixture committed through41-key", "銀行",hostCall("state",new Bundle()).getString("text"));selection(0);Thread.sleep(550);selection(2);assertNotNull(marker());tap(marker());Thread.sleep(150);List<String> exact=new ArrayList<>();for(TextView t:grid())exact.add(main(()->t.getText().toString()));assertFalse("exact ㄏㄤ reading excludes pure ㄒㄧㄥ 星",exact.contains("星"));assertFalse(exact.isEmpty());
        page("voice");text("銀行");selection(0);Thread.sleep(550);selection(2);tap(marker());Thread.sleep(150);List<String> observed=new ArrayList<>();for(TextView t:grid())observed.add(main(()->t.getText().toString()));
        Object tracks=main(()->field(correction(),"tracks"));@SuppressWarnings("unchecked")List<Object> expected=(List<Object>)main(()->call(tracks,"grid",Arrays.asList(call(tracks,"readings","銀"),call(tracks,"readings","行")),1));List<String> values=new ArrayList<>();for(Object x:expected)values.add(field(x,"text").toString());assertEquals("voice uses all table readings",values,observed);
    }
    public void testR7eRebaseAndInvalidatedRecordFallsBack()throws Exception {
        page("bopomofo");text("");for(String k:new String[]{"ㄧ","ㄣ","ˊ","ㄏ","ㄤ","ˊ","enter"})main(()->{call(service,"onTypingKeyPressed",k);return null;});Thread.sleep(300);assertEquals("銀行",hostCall("state",new Bundle()).getString("text"));
        selection(0);main(()->{android.view.inputmethod.InputConnection c=(android.view.inputmethod.InputConnection)call(service,"getCurrentInputConnection");c.commitText("前",1);return null;});Thread.sleep(200);selection(3);tap(marker());Thread.sleep(150);for(TextView v:grid())assertFalse("exact reading survives prefix rebase",main(()->v.getText().toString()).equals("星"));
        page("voice");text("前銀星");selection(0);Thread.sleep(550);selection(3);assertNotNull("changed glyph still has fallback candidates",marker());
    }
    private String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private Map<String,String> files(File dir)throws Exception {Map<String,String> result=new TreeMap<>();File[] list=dir.listFiles();if(list!=null)for(File f:list)if(f.isDirectory())for(Map.Entry<String,String>x:files(f).entrySet())result.put(f.getName()+"/"+x.getKey(),x.getValue());else if(!f.getName().equals("dual_hand_personal.tsv"))result.put(f.getName(),f.length()+":"+hex(MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(f.toPath()))));return result;}
    public void testR7fPasswordNoCapsuleNoDiskAndR7hGates()throws Exception {
        Map<String,String> before=files(inst.getTargetContext().getFilesDir());Bundle b=new Bundle();b.putBoolean("enabled",true);hostCall("password",b);Thread.sleep(250);main(()->{StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyDeath().build());return null;});
        try{for(String p:new String[]{"dual_hand","bopomofo","voice"}){page(p);text("密碼假文");selection(0);Thread.sleep(550);selection(2);assertNull(p+": password capsule absent",marker());Object c=correction();Object records=main(()->optionalField(c,"positions"));if(records instanceof Map)assertEquals("password records empty",0,((Map<?,?>)records).size());else fail("record readback unavailable; cannot claim privacy");}}finally{main(()->{StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX);return null;});b.putBoolean("enabled",false);hostCall("password",b);}
        assertEquals("files unchanged outside permitted personal learning",before,files(inst.getTargetContext().getFilesDir()));
        page("bopomofo");text("測試");main(()->{call(service,"onTypingKeyPressed","ㄅ");return null;});selection(1);assertNull("composing41 must not show capsule",marker());
        page("voice");main(()->{set(service,"isRecording",true);return null;});try{text("測試");selection(1);assertNull("recording voice must not show capsule",marker());}finally{main(()->{set(service,"isRecording",false);return null;});}
        locate("dual_hand",false);page("english");assertNull("switch hides capsule",marker());assertTrue("switch hides grid",grid().isEmpty());locate("dual_hand",false);tap(marker());Thread.sleep(150);assertFalse("positive control opened grid",grid().isEmpty());page("english");assertTrue("switch dismisses an actually open grid",grid().isEmpty());
    }
    public void testR7gRimeFilesUnchangedAndPersonalLearningIncrements()throws Exception {
        page("bopomofo");Object zhuyin=main(()->field(service,"zhuyinInput"));Map<String,String> before=files(inst.getTargetContext().getFilesDir());locate("bopomofo",false);Object c=correction(),memory=main(()->field(c,"memory"));Object previous=main(()->field(memory,"entries"));long countBefore=0;for(Object entry:((Map<?,?>)previous).values())countBefore+=(Long)field(entry,"count");tap(marker());Thread.sleep(150);List<TextView> cells=grid();assertFalse(cells.isEmpty());tap(cells.get(0));assertEquals("Rime/non-personal files unchanged",before,files(inst.getTargetContext().getFilesDir()));Object next=main(()->field(memory,"entries"));long countAfter=0;for(Object entry:((Map<?,?>)next).values())countAfter+=(Long)field(entry,"count");assertEquals("one explicit grid choice increments personal count by1",countBefore+1,countAfter);assertNotNull(zhuyin);
    }
}
