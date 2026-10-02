package com.simon.voiceime;

import junit.framework.TestCase;
import androidx.test.platform.app.InstrumentationRegistry;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.*;
import android.graphics.Rect;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import okhttp3.*;

/** Actual installed IME + production queue; fake endpoints live entirely inside the guest. */
public class VoiceGuardsAndroidTest extends TestCase {
    android.app.Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    UiAutomation ui; ServerSocket endpoint; volatile boolean stop, progress;
    volatile String failure;
    final List<Long> archiveSizes=new CopyOnWriteArrayList<>();
    final List<String> archiveIds=new CopyOnWriteArrayList<>();
    final List<String> archiveHashes=new CopyOnWriteArrayList<>();
    Thread server;
    File out;
    @Override protected void setUp() throws Exception {
        super.setUp(); ui=getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo flags=ui.getServiceInfo();
        flags.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ui.setServiceInfo(flags);
        out=new File(getInstrumentation().getTargetContext().getFilesDir(),"v656-test");out.mkdirs();
        getInstrumentation().getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit()
            .putString("server_url","http://127.0.0.1:8186").putBoolean("ime_auto_upload",true)
            .putInt("voice_session_cap_minutes",10).commit();
        endpoint=new ServerSocket(8186,8,InetAddress.getByName("127.0.0.1"));
        server=new Thread(() -> {while(!stop)try {
            Socket s=endpoint.accept();new Thread(() -> {try(Socket socket=s){serve(socket);}catch(Exception e){if(!stop)failure=e.toString();}},"FixtureConnection").start();
        }catch(Exception e){if(!stop)failure=e.toString();}},"GuestVoiceFixture");server.start();
    }
    @Override protected void tearDown()throws Exception{
        try {
            ui.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS");
            ((android.media.AudioManager)getInstrumentation().getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE)).setMicrophoneMute(false);
        } finally { ui.dropShellPermissionIdentity(); }
        stop=true;endpoint.close();server.join(2000);super.tearDown();
    }
    String shell(String command)throws Exception{
        try(ParcelFileDescriptor fd=ui.executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }
    String line(InputStream in)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();for(int b;(b=in.read())!=-1;){if(b=='\n')break;if(b!='\r')out.write(b);}return out.toString("US-ASCII");
    }
    void serve(Socket socket)throws Exception{
        socket.setSoTimeout(5000);InputStream in=new BufferedInputStream(socket.getInputStream());
        String request=line(in),key="";int length=0;
        for(String h;!(h=line(in)).isEmpty();){
            if(h.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(h.substring(15).trim());
            if(h.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))key=h.substring(18).trim();
        }
        if(request.startsWith("GET /ws/stream-audio")){
            String accept=android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),android.util.Base64.NO_WRAP);
            OutputStream o=socket.getOutputStream();o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));o.flush();
            int idx=0;
            while(!stop){Thread.sleep(1000);if(progress){byte[] text=("{\"type\":\"chunk\",\"index\":"+(idx++)+",\"text\":\"\"}").getBytes(StandardCharsets.UTF_8);o.write(0x81);o.write(text.length);o.write(text);o.flush();}}
            return;
        }
        if(length>4_100_000)throw new IOException("fixture saw unbounded request "+length);
        byte[] body=new byte[length];int p=0,n;while(p<length&&(n=in.read(body,p,length-p))!=-1)p+=n;
        if(request.startsWith("POST /v1/audio-archive ")){
            String form=new String(body,StandardCharsets.ISO_8859_1);
            String label="name=\"client_session_id\"";int part=form.indexOf(label),a=form.indexOf("\r\n\r\n",part)+4,b=form.indexOf("\r\n",a);
            String id=form.substring(a,b);
            int wave=form.indexOf("RIFF"),data=wave+44,end=form.indexOf("\r\n--",data);
            if(wave<0||end<data)throw new IOException("missing WAV");
            byte[] pcm=Arrays.copyOfRange(body,data,end);String sha=hex(MessageDigest.getInstance("SHA-256").digest(pcm));
            archiveSizes.add((long)pcm.length);archiveIds.add(id);archiveHashes.add(sha);
            JSONObject response=new JSONObject().put("text","段"+archiveSizes.size()).put("receipt",new JSONObject().put("client_session_id",id).put("byte_count",pcm.length).put("sha256",sha));
            respond(socket,200,response.toString());
        }else respond(socket,404,"{}");
    }
    String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    void respond(Socket socket,int code,String text)throws Exception{
        byte[] body=text.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();
        out.write(("HTTP/1.1 "+code+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    AccessibilityNodeInfo editable(AccessibilityNodeInfo n){
        if(n==null)return null;if(n.isEditable()&&n.isVisibleToUser())return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo hit=editable(n.getChild(i));if(hit!=null)return hit;}return null;
    }
    AccessibilityNodeInfo find(AccessibilityNodeInfo node,String id){
        if(node==null)return null;if(id.equals(node.getViewIdResourceName())&&node.isVisibleToUser())return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),id);if(found!=null)return found;}return null;
    }
    AccessibilityNodeInfo node(String id){
        for(android.view.accessibility.AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),id);if(found!=null)return found;}return null;
    }
    AccessibilityNodeInfo await(String id)throws Exception{
        for(int i=0;i<100;i++){AccessibilityNodeInfo n=node(id);if(n!=null)return n;Thread.sleep(200);}throw new AssertionError("node not found: "+id+shell("dumpsys input_method"));
    }
    void tap(String id)throws Exception{
        AccessibilityNodeInfo n=await(id);Rect bounds=new Rect();n.getBoundsInScreen(bounds);shell("input tap "+bounds.centerX()+" "+bounds.centerY());Thread.sleep(300);
    }
    void openIme()throws Exception{
        // Rebind the service after the fixture changed its endpoint preferences.
        shell("ime disable com.simon.voiceime/.SimonIMEService");Thread.sleep(300);
        shell("ime enable com.simon.voiceime/.SimonIMEService");
        shell("input keyevent WAKEUP");shell("wm dismiss-keyguard");shell("ime enable com.simon.voiceime/.SimonIMEService");shell("ime set com.simon.voiceime/.SimonIMEService");
        shell("am force-stop com.ime.sandbox.testpad");shell("am start -n com.ime.sandbox.testpad/.MainActivity");Thread.sleep(1000);
        // Activity/window and IME transitions are asynchronous; re-read visible nodes.
        long deadline=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<deadline){
            if(node("com.simon.voiceime:id/btnMic")!=null)return;
            AccessibilityNodeInfo tab=null,field=null;
            for(android.view.accessibility.AccessibilityWindowInfo w:ui.getWindows()){
                AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
                if(field==null)field=editable(root);
                for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByText("🎤"))if(n.isVisibleToUser())tab=n;
            }
            AccessibilityNodeInfo target=tab!=null?tab:field;
            if(target!=null){Rect r=new Rect();target.getBoundsInScreen(r);shell("input tap "+r.centerX()+" "+(tab!=null?r.centerY():r.top+40));}
            Thread.sleep(500);
        }
        await("com.simon.voiceime:id/btnMic");
    }
    String events()throws Exception{
        File file=new File(getInstrumentation().getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");
        return file.exists()?new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8):"";
    }
    void awaitEvent(String phase)throws Exception {
        long until=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<until){if(events().contains("\"phase\":\""+phase+"\""))return;Thread.sleep(100);}
        fail("missing metadata event: "+phase+"; "+events());
    }
    void save(String name,JSONObject data)throws Exception{
        data.put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256"));
        try(FileOutputStream stream=new FileOutputStream(new File(out,name+".json"))){stream.write(data.toString().getBytes(StandardCharsets.UTF_8));}
    }
    public void test01StalledFakeStreamStopsActualRecorder()throws Exception{
        openIme();while(!"追".contentEquals(await("com.simon.voiceime:id/btnMode").getText()))tap("com.simon.voiceime:id/btnMode");
        long start=SystemClock.elapsedRealtime();tap("com.simon.voiceime:id/btnMic");
        long readyUntil=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<readyUntil&&!"⏹".contentEquals(await("com.simon.voiceime:id/btnMic").getText()))Thread.sleep(100);
        assertEquals("⏹",await("com.simon.voiceime:id/btnMic").getText().toString());
        long stopped=0;
        while(SystemClock.elapsedRealtime()-start<24000){
            AccessibilityNodeInfo mic=node("com.simon.voiceime:id/btnMic");if(mic!=null&&"🎤".contentEquals(mic.getText())){stopped=SystemClock.elapsedRealtime()-start;break;}Thread.sleep(100);
        }
        assertTrue("actual recorder didn't stop by 20s + polling/stop allowance",stopped>=20000&&stopped<23500);
        assertTrue("plain guard status must survive finalization UI",
            await("com.simon.voiceime:id/statusText").getText().toString().contains("已停止錄音"));
        awaitEvent("stall_stop");
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long deadline=SystemClock.elapsedRealtime()+6000;
        while(q.totalBytes()>0&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
        assertEquals("guarded audio reaches receipt custody",0L,q.totalBytes());
        save("stall",new JSONObject().put("stopped_ms",stopped).put("verdict","PASS"));
    }
    public void test02LargePendingUsesProductionQueueAndHttp()throws Exception{
        // Real production queue with a local test HTTP transport; IME transport is exercised by test01.
        VoicePendingQueue queue=new VoicePendingQueue(new File(out,"large-queue"),16000);
        String id=queue.begin();byte[] block=new byte[64000];Arrays.fill(block,(byte)7);
        for(int left=7_680_020;left>0;){int n=Math.min(left,block.length);queue.append(id,block,n);left-=n;}queue.markPending(id,"fixture");
        List<String> text=new ArrayList<>();
        queue.uploadOne(id,(file,child,rate)->{
            RequestBody audio=new RequestBody(){
                public MediaType contentType(){return MediaType.get("audio/wav");}
                public long contentLength(){return file.length()+44;}
                public void writeTo(okio.BufferedSink sink)throws IOException{
                    java.nio.ByteBuffer h=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)file.length()+36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                        .putInt(16).putShort((short)1).putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16)
                        .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)file.length());sink.write(h.array());
                    try(InputStream in=new FileInputStream(file)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)sink.write(b,0,n);}
                }
            };
            Request req=new Request.Builder().url("http://127.0.0.1:8186/v1/audio-archive").post(new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file","fixture.wav",audio).addFormDataPart("client_session_id",child).build()).build();
            try(Response response=new OkHttpClient().newCall(req).execute()){assertEquals(200,response.code());return response.body().string();}
        },(value,started)->text.add(value),null);
        assertEquals(Arrays.asList(3_840_000L,3_840_000L,20L),archiveSizes);assertEquals(3,new HashSet<>(archiveIds).size());
        assertEquals(Arrays.asList("段1段2段3"),text);assertTrue(queue.receiptConfirmed(id));assertFalse(queue.pcmFile(id).exists());assertNull(failure);
        save("chunks",new JSONObject().put("sizes",new JSONArray(archiveSizes)).put("text",text.get(0)).put("receipts",archiveIds.size()).put("verdict","PASS"));
    }
    public void test03ScreenOffIdleStopsActualRecorder()throws Exception{
        openIme();while(!"拼".contentEquals(await("com.simon.voiceime:id/btnMode").getText()))tap("com.simon.voiceime:id/btnMode");
        tap("com.simon.voiceime:id/btnMic");long readyUntil=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<readyUntil&&!"⏹".contentEquals(await("com.simon.voiceime:id/btnMic").getText()))Thread.sleep(100);
        assertEquals("⏹",await("com.simon.voiceime:id/btnMic").getText().toString());
        // hw.audioInput=no emits a full-scale synthetic tone, not silence. Mute the
        // guest input so this fixture actually represents no detected speech.
        android.media.AudioManager audio=(android.media.AudioManager)getInstrumentation().getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        try {
            ui.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS");
            audio.setMicrophoneMute(true);
            assertTrue("fixture must mute guest microphone",audio.isMicrophoneMute());
        } finally { ui.dropShellPermissionIdentity(); }
        shell("input keyevent SLEEP");long start=SystemClock.elapsedRealtime();
        Thread.sleep(2000);
        android.os.PowerManager power=(android.os.PowerManager)getInstrumentation().getTargetContext().getSystemService(android.content.Context.POWER_SERVICE);
        android.hardware.display.DisplayManager displays=(android.hardware.display.DisplayManager)getInstrumentation().getTargetContext().getSystemService(android.content.Context.DISPLAY_SERVICE);
        JSONObject probe=new JSONObject().put("interactive",power.isInteractive()).put("display_state",displays.getDisplay(0).getState());
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        JSONObject energy=q.runIO(() -> {
            File[] pcm=new File(getInstrumentation().getTargetContext().getFilesDir(),"voice_pending").listFiles((d,n)->n.endsWith(".pcm"));
            File latest=null;for(File f:pcm)if(latest==null||f.lastModified()>latest.lastModified())latest=f;
            if(latest==null)return new JSONObject().put("sampled",false);
            byte[] data=new byte[(int)Math.min(64000,latest.length())];
            try(RandomAccessFile f=new RandomAccessFile(latest,"r")){f.seek(latest.length()-data.length);f.readFully(data);}
            double sum=0,squares=0;int count=data.length/2;
            for(int i=0;i+1<data.length;i+=2){short v=(short)((data[i]&255)|(data[i+1]<<8));sum+=v;squares+=(double)v*v;}
            double mean=count==0?0:sum/count,rms=count==0?0:Math.sqrt(squares/count),ac=count==0?0:Math.sqrt(Math.max(0,squares/count-mean*mean));
            return new JSONObject().put("sampled",true).put("samples",count).put("mean",mean).put("rms",rms).put("ac_rms",ac);
        });
        probe.put("energy",energy);save("screen-probe",probe);
        assertFalse("fixture must turn screen off",power.isInteractive());
        assertTrue("fixture must supply silence below speech energy threshold",energy.getDouble("rms")<800.0);
        Thread.sleep(62500);awaitEvent("screen_off_idle_stop");
        shell("input keyevent WAKEUP");shell("wm dismiss-keyguard");
        save("screen-off",new JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("verdict","PASS"));
    }
    public void test04CapStopsWithHealthyServerProgress()throws Exception{
        progress=true;getInstrumentation().getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putInt("voice_session_cap_minutes",1).commit();
        openIme();while(!"追".contentEquals(await("com.simon.voiceime:id/btnMode").getText()))tap("com.simon.voiceime:id/btnMode");
        tap("com.simon.voiceime:id/btnMic");long start=SystemClock.elapsedRealtime();Thread.sleep(62500);
        awaitEvent("cap_stop");assertEquals("🎤",await("com.simon.voiceime:id/btnMic").getText().toString());
        save("cap",new JSONObject().put("elapsed_ms",SystemClock.elapsedRealtime()-start).put("verdict","PASS"));
    }
}
