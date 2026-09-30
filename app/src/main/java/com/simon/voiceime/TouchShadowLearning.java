package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.function.BooleanSupplier;

/** UI geometry/persistence boundary for the observation-only model. No key routing API. */
final class TouchShadowLearning {
    static final String PREFS="touch_model_shadow";
    private final SharedPreferences prefs;
    private final Handler handler;
    private final LinkedHashMap<String,TouchModelShadow> layouts=new LinkedHashMap<>();
    private TouchModelShadow current;
    private String currentId;
    private long resetEpoch;
    TouchShadowLearning(Context c,Handler h){prefs=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);handler=h;resetEpoch=prefs.getLong("reset_epoch",0);}
    static void resetDefaults(Context c){
        SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long epoch=p.getLong("reset_epoch",0)+1;
        p.edit().clear().putLong("reset_epoch",epoch).apply();
    }
    private void checkReset(){long epoch=prefs.getLong("reset_epoch",0);if(epoch!=resetEpoch){invalidate();layouts.clear();current=null;currentId=null;resetEpoch=epoch;}}
    private static String digest(byte[] bytes)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte v:hash)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}
    void invalidate(){for(TouchModelShadow s:layouts.values())s.invalidate();}
    private void collect(View v,List<View> keys){
        if(v.getVisibility()!=View.VISIBLE)return;
        Object tag=v.getTag();
        if(tag!=null&&tag.toString().startsWith("key:")){
            String k=tag.toString().substring(4);
            if("space".equals(k)||(k.length()==1&&"ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".contains(k)))keys.add(v);
        } else if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),keys);}
    }
    JSONObject observe(View root,String physicalKey,MotionEvent e) {
        try {
            checkReset();
            List<View> views=new ArrayList<>();collect(root,views);if(views.isEmpty())return new JSONObject();
            int[] origin=new int[2];root.getLocationOnScreen(origin);
            String screen=root.getResources().getConfiguration().screenWidthDp+"x"+root.getResources().getConfiguration().screenHeightDp
                    +":"+root.getWidth()+"x"+root.getHeight();
            List<TouchModel.Key> keys=new ArrayList<>();
            for(View v:views){if(v.getWidth()==0||v.getHeight()==0)return new JSONObject();int[] p=new int[2];v.getLocationOnScreen(p);
                keys.add(new TouchModel.Key(screen,v.getTag().toString().substring(4),p[0]-origin[0]+v.getWidth()/2.0,
                    p[1]-origin[1]+v.getHeight()/2.0,v.getWidth(),v.getHeight()));}
            TouchModel defaults=new TouchModel(keys);
            String id=digest(defaults.snapshot());
            if(!id.equals(currentId)) {
                invalidate();currentId=id;current=layouts.get(id);
                if(current==null){
                    String saved=prefs.getString("p_"+id,null);
                    if(saved!=null)try{defaults.rollback(Base64.decode(saved,Base64.NO_WRAP));}
                        catch(Exception invalid){Log.w("TouchShadow","saved parameters rejected; using defaults",invalid);}
                    current=new TouchModelShadow(defaults);layouts.put(id,current);
                    if(layouts.size()>4)layouts.remove(layouts.keySet().iterator().next());
                }
            }
            List<TouchModel.Alternative> alternatives=current.press(screen,physicalKey,e.getRawX()-origin[0],e.getRawY()-origin[1]);
            return fields(alternatives,screen);
        }catch(Exception failure){Log.w("TouchShadow","observation skipped",failure);return new JSONObject();}
    }
    static JSONObject fields(List<TouchModel.Alternative> alternatives,String screen)throws Exception {
        JSONArray rows=new JSONArray();
        for(TouchModel.Alternative a:alternatives)rows.put(new JSONObject().put("key",a.key).put("probability",a.probability));
        return new JSONObject().put("touch_model_mode","shadow").put("touch_model_screen",screen).put("touch_alternatives",rows);
    }
    void confirmChoice(){checkReset();if(current!=null&&current.confirmChoice()>0)save(current,currentId);}
    void discardTrace(){checkReset();if(current!=null)current.discardTrace();}
    private void save(TouchModelShadow owner,String layout){
        SharedPreferences.Editor edit=prefs.edit();
        List<String> stored=new ArrayList<>();for(String k:prefs.getAll().keySet())if(k.startsWith("p_"))stored.add(k);
        if(!stored.contains("p_"+layout)&&stored.size()>=8)edit.remove(stored.get(0));
        edit.putString("p_"+layout,Base64.encodeToString(owner.snapshot(),Base64.NO_WRAP)).apply();
    }
    void confirmAfterDelay(BooleanSupplier unchanged){
        checkReset();if(current==null)return;
        final TouchModelShadow owner=current;final String layout=currentId;final long epoch=resetEpoch;
        final long id=owner.awaitConfirmation(SystemClock.elapsedRealtime());if(id<0)return;
        handler.postDelayed(()->{
            checkReset();if(epoch!=resetEpoch||!layouts.containsValue(owner))return;
            try{if(owner.confirm(id,SystemClock.elapsedRealtime(),unchanged.getAsBoolean())>0){
                save(owner,layout);
            }}catch(Exception failure){owner.invalidate();Log.w("TouchShadow","confirmation skipped",failure);}
        },10_050);
    }
}
