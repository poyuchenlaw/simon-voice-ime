package com.simon.voiceime;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Emulator-only destructive fixture. Test-only API34 hidden-window observer,
 * actual IME window, physical taps, after-layout draw samples. The guest app
 * install/prefs and selected IME are disposable, owned by run_frames.py. */
public class RowFlicker665AndroidTest extends TestCase {
    Instrumentation inst; UiAutomation ui; File out;
    View root; TextView row1,row2; View row3,keyboard;
    final List<JSONObject> frames=new ArrayList<>();
    volatile int keyIndex=-1; volatile boolean stop; volatile Throwable error;
    ServerSocket endpoint; Thread server;
    ViewTreeObserver.OnDrawListener observer;
    String shell(String command)throws Exception {
        try(ParcelFileDescriptor fd=ui.executeShellCommand(command); InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    void save(String name,String text)throws Exception {
        try(FileOutputStream f=new FileOutputStream(new File(out,name))){f.write(text.getBytes(StandardCharsets.UTF_8));}
    }
    AccessibilityNodeInfo find(AccessibilityNodeInfo n,String label){
        if(n==null)return null;
        if(label.equals(String.valueOf(n.getText()))||label.equals(String.valueOf(n.getContentDescription())))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=find(n.getChild(i),label);if(hit!=null)return hit;}
        return null;
    }
    AccessibilityNodeInfo node(String label){for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo n=find(w.getRoot(),label);if(n!=null)return n;}return null;}
    AccessibilityNodeInfo await(String label)throws Exception {
        long end=SystemClock.uptimeMillis()+30000;
        do{AccessibilityNodeInfo n=node(label);if(n!=null&&n.isVisibleToUser())return n;Thread.sleep(100);}while(SystemClock.uptimeMillis()<end);
        throw new AssertionError("missing visible key: "+label);
    }
    void tap(Rect r){long t=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0),u=MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0);assertTrue(ui.injectInputEvent(d,false)&&ui.injectInputEvent(u,false));d.recycle();u.recycle();}
    void tap(String label)throws Exception {Rect r=new Rect();await(label).getBoundsInScreen(r);tap(r);Thread.sleep(200);}
    String line(InputStream in)throws Exception {ByteArrayOutputStream b=new ByteArrayOutputStream();for(int c;(c=in.read())!=-1;){if(c=='\n')break;if(c!='\r')b.write(c);}return b.toString("US-ASCII");}
    void startEndpoint()throws Exception {
        endpoint=new ServerSocket(8181,4,InetAddress.getByName("127.0.0.1"));
        server=new Thread(()->{int count=0;while(!stop)try(Socket s=endpoint.accept()){
            s.setSoTimeout(4000);InputStream in=new BufferedInputStream(s.getInputStream());String first=line(in);int size=0;
            for(String h;!(h=line(in)).isEmpty();)if(h.toLowerCase().startsWith("content-length:"))size=Integer.parseInt(h.substring(15).trim());
            byte[] body=in.readNBytes(size);String response="{}";int code=404;
            if(first.startsWith("POST /v1/ime/sentence-candidates ")){
                JSONObject req=new JSONObject(new String(body,StandardCharsets.UTF_8));save("request-"+(++count)+".json",req.toString());
                long now=SystemClock.elapsedRealtime();
                response=new JSONObject().put("kind","response").put("schema_version",1).put("request_id",req.getString("request_id"))
                    .put("editor_generation",req.getLong("editor_generation")).put("composition_generation",req.getLong("composition_generation")).put("mode","suggestions")
                    .put("keep",new JSONObject().put("id","keep").put("text",req.getString("literal")).put("repairs",new JSONArray())).put("candidates",new JSONArray())
                    .put("decision",new JSONObject().put("display","none").put("selected_id","keep").put("jev_status","ok").put("choice_confidence",.95).put("meaning_probability",.95).put("reason","ready"))
                    .put("gemini_model","sandbox-fixture").put("jev_model","sandbox-fixture").put("policy_version","sentence-r2-v1")
                    .put("server_times",new JSONObject().put("server_receive_ms",now).put("gemini_done_ms",now).put("jev_start_ms",now).put("jev_done_ms",now).put("response_send_ms",now).put("clock_domain","guest-fixture")).toString();code=200;Thread.sleep(100);
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("HTTP/1.1 "+code+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(bytes);s.getOutputStream().flush();
        }catch(SocketException e){/* Superseded HTTP calls deliberately close their socket. */}
        catch(Exception e){if(!stop)error=e;}},"GuestRow665Endpoint");server.start();
    }
    void bindWindow()throws Exception {
        Class<?> cls=Class.forName("android.view.WindowManagerGlobal");Object wm=cls.getDeclaredMethod("getInstance").invoke(null);
        java.lang.reflect.Field views=cls.getDeclaredField("mViews");views.setAccessible(true);
        for(Object obj:(List<?>)views.get(wm)){
            View view=(View)obj;TextView candidate=view.findViewById(R.id.boStreamPreview);
            if(candidate==null||!candidate.isShown())continue;
            root=view;row1=candidate;row2=view.findViewById(R.id.boPhoneticPreview);row3=view.findViewById(R.id.boCandidateBar);keyboard=view.findViewById(R.id.bopomofoKeyboard);return;
        }
        throw new AssertionError("IME window not in target process");
    }
    JSONObject geometry()throws Exception {
        int[] at=new int[2];keyboard.getLocationOnScreen(at);String text=row1.getText().toString();
        return new JSONObject().put("uptime_ms",SystemClock.uptimeMillis()).put("key_index",keyIndex).put("preview",text).put("preview_chars",text.codePointCount(0,text.length()))
            .put("reading",row2.getText().toString()).put("row1_text_size_px",row1.getTextSize()).put("row1_scroll_x",((View)row1.getParent()).getScrollX()).put("row1_internal_scroll_x",row1.getScrollX())
            .put("row2_scroll_x",((View)row2.getParent()).getScrollX()).put("row2_internal_scroll_x",row2.getScrollX()).put("row2_content_width",row2.getWidth()).put("row2_viewport_width",((View)row2.getParent()).getWidth())
            .put("row2_caret_cap",5*row2.getResources().getDisplayMetrics().density)
            .put("row2_caret_x",row2.getPaddingLeft()+row2.getLayout().getPrimaryHorizontal(row2.getText().length()))
            .put("row3_height",row3.getHeight()).put("keyboard_top",at[1]);
    }
    void screenshot(String name)throws Exception {
        Bitmap image=ui.takeScreenshot();assertNotNull(image);try(FileOutputStream f=new FileOutputStream(new File(out,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,f);}image.recycle();
        final JSONObject[] current={null};inst.runOnMainSync(()->{try{current[0]=geometry();}catch(Exception e){throw new RuntimeException(e);}});save(name+".json",current[0].toString());
    }
    public void testHumanSpeedRows()throws Exception {
        inst=InstrumentationRegistry.getInstrumentation();ui=inst.getUiAutomation();AccessibilityServiceInfo flags=ui.getServiceInfo();flags.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(flags);
        assertEquals("fixture must only run on an emulator","1",shell("getprop ro.kernel.qemu").trim());
        out=new File(inst.getTargetContext().getFilesDir(),"v665");out.mkdirs();startEndpoint();
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("server_url","http://127.0.0.1:8181").putString("auth_password","sandbox").putString("ai_sentence_mode","shadow").putBoolean("auto_correction",false).putBoolean("ime_auto_upload",true).putBoolean("auto_vocab_enabled",false).commit();
        shell("ime enable com.simon.voiceime/.SimonIMEService");shell("ime set com.simon.voiceime/.SimonIMEService");shell("am force-stop com.ime.sandbox.testpad");shell("am start -W -n com.ime.sandbox.testpad/.MainActivity");tap("test_input");
        // Instrumentation restarts the IME process; recheck the selected IME and
        // visible page just as the existing testpad readiness helper does.
        long readyDeadline=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<readyDeadline){
            AccessibilityNodeInfo key=node("ㄗ");if(key!=null&&key.isVisibleToUser())break;
            shell("ime enable com.simon.voiceime/.SimonIMEService");shell("ime set com.simon.voiceime/.SimonIMEService");
            AccessibilityNodeInfo page=node("注");if(page!=null&&page.isVisibleToUser())tap("注");else tap("test_input");Thread.sleep(500);
        }
        await("ㄗ");tap("ㄅ");tap("⌫");Thread.sleep(500);
        inst.runOnMainSync(()->{try{bindWindow();observer=()->{try{frames.add(geometry());}catch(Exception e){error=e;}};root.getViewTreeObserver().addOnDrawListener(observer);}catch(Exception e){throw new RuntimeException(e);}});
        String[] syllables={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};
        Map<String,Rect> positions=new HashMap<>();for(String s:syllables)for(int j=0;j<s.length();j++){String k=s.charAt(j)==' '?"空白":s.substring(j,j+1);if(!positions.containsKey(k)){Rect r=new Rect();await(k).getBoundsInScreen(r);positions.put(k,r);}}
        final String[] finalText={""};
        try {
            save("typing-start.json",new JSONObject().put("uptime_ms",SystemClock.uptimeMillis()).put("sequence",new JSONArray(syllables)).put("interval_ms",200).put("mode","shadow").toString());
            for(int n=0;n<syllables.length;n++){
                String s=syllables[n];for(int j=0;j<s.length();j++){keyIndex++;String k=s.charAt(j)==' '?"空白":s.substring(j,j+1);tap(positions.get(k));Thread.sleep(200);}
                if(n>=3&&n<=6)screenshot("chars-"+(n+1));
            }
            Thread.sleep(1200);screenshot("chars-10");inst.runOnMainSync(()->finalText[0]=row1.getText().toString());
        }finally {
            inst.runOnMainSync(()->root.getViewTreeObserver().removeOnDrawListener(observer));StringBuilder data=new StringBuilder();for(JSONObject f:frames)data.append(f).append('\n');save("frames.jsonl",data.toString());stop=true;endpoint.close();server.join(5000);
        }
        assertNull("observer/fixture failure: "+error,error);assertEquals(10,finalText[0].codePointCount(0,finalText[0].length()));
        // Forward typing may scroll right, but must never snap back to earlier readings.
        int previous=0;for(JSONObject frame:frames){int x=frame.getInt("row2_scroll_x");assertTrue("row2 moves backwards at key "+frame.getInt("key_index")+": "+previous+" -> "+x,x>=previous);previous=x;
            float cap=5*inst.getTargetContext().getResources().getDisplayMetrics().density;
            assertTrue("reading caret clipped after layout at key "+frame.getInt("key_index")+": caret="+frame.getDouble("row2_caret_x")+" scroll="+x+" viewport="+frame.getInt("row2_viewport_width"),frame.getDouble("row2_caret_x")-cap>=x&&frame.getDouble("row2_caret_x")+cap<=x+frame.getInt("row2_viewport_width"));
        }
    }
}
