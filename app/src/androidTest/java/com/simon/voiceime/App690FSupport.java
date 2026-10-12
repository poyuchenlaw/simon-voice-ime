package com.simon.voiceime;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Test-only access, with no compile-time dependency on the 6.90 shared API. */
public abstract class App690FSupport extends TestCase {
    protected Instrumentation inst;
    protected Activity host;
    protected EditText editor;
    protected Object service;
    protected View root;
    protected File output;
    private Map<String,?> previous;
    protected <T> T main(Callable<T> action) {
        Object[] answer={null};Throwable[] failure={null};
        inst.runOnMainSync(()->{try{answer[0]=action.call();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new RuntimeException(failure[0]);
        @SuppressWarnings("unchecked") T result=(T)answer[0];return result;
    }
    protected static Field fieldOf(Class<?> type,String name)throws NoSuchFieldException {
        for(Class<?> t=type;t!=null;t=t.getSuperclass())try{Field f=t.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException absent){}
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    protected static Object field(Object owner,String name)throws Exception {return fieldOf(owner instanceof Class?(Class<?>)owner:owner.getClass(),name).get(owner instanceof Class?null:owner);}
    protected static void set(Object owner,String name,Object value)throws Exception {fieldOf(owner.getClass(),name).set(owner,value);}
    protected static Object call(Object owner,String name,Object...args)throws Exception {
        Class<?> type=owner instanceof Class?(Class<?>)owner:owner.getClass();
        for(Class<?> t=type;t!=null;t=t.getSuperclass())for(Method m:t.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            Class<?>[] types=m.getParameterTypes();boolean match=true;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isPrimitive()&&!types[i].isInstance(args[i]))match=false;
            if(match){m.setAccessible(true);return m.invoke(owner instanceof Class?null:owner,args);}
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    protected Class<?> api(String name)throws ClassNotFoundException {return Class.forName("com.simon.voiceime."+name,true,inst.getTargetContext().getClassLoader());}
    protected Object optionalField(Object owner,String name){if(owner==null)return null;try{return field(owner,name);}catch(Exception absent){return null;}}
    @Override protected void setUp()throws Exception {
        super.setUp();inst=InstrumentationRegistry.getInstrumentation();
        SharedPreferences p=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);previous=new HashMap<>(p.getAll());
        p.edit().putBoolean("ime_auto_upload",false).putString("server_url","http://127.0.0.1:9").putBoolean("dual_hand_enabled",true).putBoolean("dual_hand_mode",true).putBoolean("dual_hand_haptics",true).putBoolean("haptic_enabled",true).commit();
        output=new File(inst.getTargetContext().getExternalFilesDir(null),"app690F");assertTrue(output.isDirectory()||output.mkdirs());
        shell("ime enable com.simon.voiceime/.SimonIMEService; ime set com.simon.voiceime/.SimonIMEService");
        shell("am start -W -n com.simon.ime.host690/.App690HostActivity");
        hostCall("reopen",new Bundle());
        String selected=shell("ime set com.simon.voiceime/.SimonIMEService; settings get secure default_input_method");save("ime-selection.txt",selected);assertTrue("selected IME readback: "+selected,selected.strip().endsWith("com.simon.voiceime/.SimonIMEService"));
        long end=SystemClock.uptimeMillis()+5000;
        do{service=main(()->((java.lang.ref.WeakReference<?>)field(api("SimonIMEService"),"notificationVoiceService")).get());if(service!=null){root=main(()->(View)field(service,"rootView"));if(root!=null&&main(()->root.isShown()))break;}Thread.sleep(40);}while(SystemClock.uptimeMillis()<end);
        assertNotNull("IME must be enabled/selected before instrumentation",service);assertNotNull(root);inst.waitForIdleSync();
    }
    @Override protected void tearDown()throws Exception {
        try{try{if(inst!=null)hostCall("finish",new Bundle());}catch(Exception absent){}if(host!=null)main(()->{host.finish();return null;});if(previous!=null){SharedPreferences.Editor e=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().clear();for(Map.Entry<String,?> x:previous.entrySet()){Object v=x.getValue();if(v instanceof String)e.putString(x.getKey(),(String)v);else if(v instanceof Boolean)e.putBoolean(x.getKey(),(Boolean)v);else if(v instanceof Integer)e.putInt(x.getKey(),(Integer)v);else if(v instanceof Long)e.putLong(x.getKey(),(Long)v);else if(v instanceof Float)e.putFloat(x.getKey(),(Float)v);else if(v instanceof Set){@SuppressWarnings("unchecked")Set<String> s=(Set<String>)v;e.putStringSet(x.getKey(),s);}}e.commit();}}finally{super.tearDown();}
    }
    protected Object dual()throws Exception {return field(service,"dualHandPage");}
    protected View page(String name)throws Exception {
        main(()->{set(service,"dualHandSelected",name.equals("dual_hand"));Class<?> mode=api("KeyboardPager$KeyboardMode");Object value=null;for(Object x:mode.getEnumConstants())if(x.toString().equals(name.equals("voice")?"VOICE":name.equals("english")?"ENGLISH":name.equals("numbers")?"NUMBERS":"BOPOMOFO"))value=x;call(service,"switchKeyboard",value);return null;});
        inst.waitForIdleSync();Thread.sleep(80);
        return main(()->name.equals("dual_hand")?(View)call(dual(),"view"):(View)field(service,name.equals("bopomofo")?"bopomofoKeyboard":name+"Keyboard"));
    }
    protected JSONObject counters(){return main(()->{try{return new JSONObject((Map<?,?>)call(api("KeyboardProbe"),"counters"));}catch(ClassNotFoundException absent){return new JSONObject().put("available",false);}});}
    protected float density(){return root.getResources().getDisplayMetrics().density;}
    protected float sizeSp(TextView v){float sp=Build.VERSION.SDK_INT>=34?android.util.TypedValue.deriveDimension(android.util.TypedValue.COMPLEX_UNIT_SP,v.getTextSize(),v.getResources().getDisplayMetrics()):v.getTextSize()/v.getResources().getDisplayMetrics().scaledDensity;return Math.round(sp*10000)/10000f;}
    protected Rect rect(View v){int[] p=new int[2];v.getLocationOnScreen(p);return new Rect(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());}
    protected void touch(View v,int action,long down,float dx,float dy){Rect r=main(()->rect(v));MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,r.exactCenterX()+dx,r.exactCenterY()+dy,0);try{assertTrue(inst.getUiAutomation().injectInputEvent(e,true));}finally{e.recycle();}}
    protected void tap(View v)throws Exception {long down=SystemClock.uptimeMillis();touch(v,MotionEvent.ACTION_DOWN,down,0,0);Thread.sleep(40);touch(v,MotionEvent.ACTION_UP,down,0,0);inst.waitForIdleSync();}
    protected void save(String name,Object value)throws Exception {try(Writer w=new OutputStreamWriter(new FileOutputStream(new File(output,name)),"UTF-8")){w.write(value.toString());w.write('\n');}}
    protected String shell(String command)throws Exception {
        assertTrue("F diagnostic shell requires API31+",Build.VERSION.SDK_INT>=31);
        ParcelFileDescriptor[] pipe=inst.getUiAutomation().executeShellCommandRw("sh");
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){
            try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write((command+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    protected List<View> keys(View v){List<View> list=new ArrayList<>();collectKeys(v,list);return list;}
    private void collectKeys(View v,List<View> result){if(!v.isShown())return;Object tag=v.getTag();if(tag instanceof String&&(((String)tag).startsWith("key:")||((String)tag).startsWith("dh-key:"))||v instanceof TextView&&v.isClickable()){result.add(v);return;}if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collectKeys(((ViewGroup)v).getChildAt(i),result);}
    protected List<View> windows()throws Exception {
        Object wm=call(Class.forName("android.view.WindowManagerGlobal"),"getInstance");
        @SuppressWarnings("unchecked")List<View> views=(List<View>)field(wm,"mViews");return new ArrayList<>(views);
    }
    protected View byDescription(String description)throws Exception {
        for(View v:windows()){View found=described(v,description);if(found!=null)return found;}return null;
    }
    private View described(View v,String description){if(v.isShown()&&description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View q=described(((ViewGroup)v).getChildAt(i),description);if(q!=null)return q;}return null;}
    protected JSONObject hostCall(String method,Bundle extra)throws Exception {
        // The unchanged product manifest cannot query the separate diagnostic host on API 30+.
        String command="content call --uri content://com.simon.ime.host690.truth --method "+method;
        for(String key:extra.keySet()){
            Object value=extra.get(key);String type=value instanceof Boolean?"b":value instanceof Integer?"i":"s";
            command+=" --extra '"+(key+":"+type+":"+value).replace("'","'\\''")+"'";
        }
        String reply=shell(command);int start=reply.indexOf("{\""),end=reply.lastIndexOf("}");
        assertTrue("independent diagnostic host readback: "+reply,start>=0&&end>start);
        return new JSONObject(reply.substring(start,end+1));
    }
    protected void text(String value)throws Exception {Bundle b=new Bundle();b.putString("text",value);hostCall("text",b);inst.waitForIdleSync();}
    protected JSONObject selection(int at)throws Exception {Bundle b=new Bundle();b.putInt("start",at);b.putInt("end",at);hostCall("select",b);Thread.sleep(180);inst.waitForIdleSync();return hostCall("state",new Bundle());}
    protected Object correction(){Object d=optionalField(service,"dualHandPage");Object hooks=d==null?null:optionalField(d,"hooks");Object shared=optionalField(service,"correction");return shared!=null?shared:hooks;}
}
