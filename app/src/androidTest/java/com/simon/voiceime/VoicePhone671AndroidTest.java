package com.simon.voiceime;

import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Installed APK, public mic button, AudioRecord input supplied through emulator injectAudio.
 * All network fixtures are guest loopback; no production endpoints. */
public class VoicePhone671AndroidTest extends VoiceGuardsAndroidTest {
    long lastMicTap;
    final JSONArray micCoordinateAudit=new JSONArray();
    @Override void tap(String id)throws Exception {
        if(id.endsWith("btnMic")) {
            long gap=SystemClock.uptimeMillis()-lastMicTap;
            if(gap<450)Thread.sleep(450-gap); // Preserve APPEND, beyond the existing spell-mode double-tap gesture.
        }
        android.graphics.Rect bounds=new android.graphics.Rect();await(id).getBoundsInScreen(bounds);
        JSONObject coordinate=null;
        if(id.endsWith("btnMic")){coordinate=physicalMicCoordinates(bounds);bounds=new android.graphics.Rect(coordinate.getInt("left"),coordinate.getInt("top"),coordinate.getInt("right"),coordinate.getInt("bottom"));}
        long t=SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(t,t,0,bounds.centerX(),bounds.centerY(),0);
        assertTrue(ui.injectInputEvent(down,true));down.recycle();Thread.sleep(20);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(t,SystemClock.uptimeMillis(),1,bounds.centerX(),bounds.centerY(),0);
        assertTrue(ui.injectInputEvent(up,true));up.recycle();
        if(id.endsWith("btnMic"))lastMicTap=t;
        Thread.sleep(id.endsWith("btnMic")?50:300);
        if(coordinate!=null){coordinate.put("motion_down_uptime",t);micCoordinateAudit.put(coordinate);save("mic-coordinate-audit",new JSONObject().put("events",micCoordinateAudit));}
    }
    // Physical microphone taps use the actual attached View screen location; accessibility bounds are retained as evidence.
    JSONObject physicalMicCoordinates(android.graphics.Rect readerBounds)throws Exception {
        final JSONObject[] coordinate={null};final Exception[] failure={null};
        getInstrumentation().runOnMainSync(()->{try{
            Class<?> wmClass=Class.forName("android.view.WindowManagerGlobal");Object wm=wmClass.getDeclaredMethod("getInstance").invoke(null);
            java.lang.reflect.Field views=wmClass.getDeclaredField("mViews");views.setAccessible(true);
            int id=getInstrumentation().getTargetContext().getResources().getIdentifier("btnMic","id","com.simon.voiceime");
            android.view.View mic=null;SimonIMEService owner=null;int matches=0;
            for(Object object:(java.util.List<?>)views.get(wm)){
                android.view.View candidate=((android.view.View)object).findViewById(id);
                if(candidate==null||!candidate.isShown()||!candidate.isAttachedToWindow()||candidate.getWindowToken()==null)continue;
                android.content.Context context=candidate.getContext();while(!(context instanceof SimonIMEService)&&context instanceof android.content.ContextWrapper)context=((android.content.ContextWrapper)context).getBaseContext();
                if(!(context instanceof SimonIMEService))continue;mic=candidate;owner=(SimonIMEService)context;matches++;
            }
            if(matches!=1)throw new IllegalStateException("Expected exactly one shown, attached IME microphone: "+matches);
            int[] screen=new int[2];mic.getLocationOnScreen(screen);android.graphics.Rect visible=new android.graphics.Rect();
            if(!mic.getLocalVisibleRect(visible)||visible.isEmpty())throw new IllegalStateException("Current microphone has no visible tap area");
            android.graphics.Rect physical=new android.graphics.Rect(screen[0]+visible.left,screen[1]+visible.top,screen[0]+visible.right,screen[1]+visible.bottom);
            coordinate[0]=new JSONObject().put("captured_uptime",SystemClock.uptimeMillis()).put("reader_bounds",readerBounds.toShortString()).put("reader_center_x",readerBounds.centerX()).put("reader_center_y",readerBounds.centerY())
                .put("left",physical.left).put("top",physical.top).put("right",physical.right).put("bottom",physical.bottom).put("physical_center_x",physical.centerX()).put("physical_center_y",physical.centerY())
                .put("reader_minus_physical_x",readerBounds.centerX()-physical.centerX()).put("reader_minus_physical_y",readerBounds.centerY()-physical.centerY()).put("mic_identity",System.identityHashCode(mic)).put("window_token",String.valueOf(mic.getWindowToken()));
            android.view.inputmethod.EditorInfo info=owner.getCurrentInputEditorInfo();if(info!=null)coordinate[0].put("editor_package",info.packageName).put("editor_field_id",info.fieldId);
        }catch(Exception error){failure[0]=error;}});
        if(failure[0]!=null)throw failure[0];return coordinate[0];
    }
    final AtomicInteger sockets=new AtomicInteger();
    final Map<String,Integer> sessions=new ConcurrentHashMap<>();
    final Map<String,JSONObject> receipts=new ConcurrentHashMap<>();
    final Map<String,byte[]> audio=new ConcurrentHashMap<>();
    final List<String> protocol=new CopyOnWriteArrayList<>();
    final List<String> recovered=new CopyOnWriteArrayList<>();
    final List<Integer> peaks=new CopyOnWriteArrayList<>();
    volatile boolean omitFirst,emptyFirst,rejectTwelfth,closeFirst,rejectAll,reject200,closeOnStop,legacySuccess,guardErrorFirst;
    final Map<Socket,String> requestRoutes=new ConcurrentHashMap<>();
    final Map<Socket,Integer> requestIds=new ConcurrentHashMap<>();final AtomicInteger httpSequence=new AtomicInteger();
    final List<JSONObject> httpAudit=new CopyOnWriteArrayList<>();
    volatile boolean asrFallbackResponse,emptyArchiveFirst,emptyArchiveIssued;
    @Override void respond(Socket socket,int code,String text)throws Exception{
        if(asrFallbackResponse&&code==200) {
            JSONObject payload=new JSONObject(text);
            if(payload.has("text"))text=payload.put("ai_corrected",false).put("correction_applied",false)
                    .put("delivery_source","asr").put("correction_status","pending").toString();
        }
        if(emptyArchiveFirst&&code==200){JSONObject payload=new JSONObject(text);if(payload.has("text")){text=payload.put("text","").put("ai_corrected",false).put("correction_status","pending").toString();emptyArchiveFirst=false;emptyArchiveIssued=true;}}
        JSONObject row=new JSONObject().put("request_id",requestIds.get(socket)).put("route",requestRoutes.get(socket)).put("status",code);
        JSONObject body=new JSONObject(text);row.put("ai_corrected",body.has("ai_corrected")?body.get("ai_corrected"):JSONObject.NULL).put("correction_status",body.optString("correction_status"));
        httpAudit.add(row);save("http-audit",new JSONObject().put("events",new JSONArray(httpAudit)));super.respond(socket,code,text);
    }
    int httpResponses(String route,int status){int n=0;for(JSONObject row:httpAudit)if(route.equals(row.optString("route"))&&row.optInt("status")==status)n++;return n;}
    final Map<String,AtomicInteger> rejectionCalls=new ConcurrentHashMap<>();
    volatile CountDownLatch normalFinalReady,normalFinalRelease;
    volatile OutputStream firstOutput;volatile long stopCloseDelayMs; boolean injectRequired=true; volatile long openDelay,authDelay;
    @Override protected void setUp()throws Exception{
        super.setUp();
        out=new File(getInstrumentation().getTargetContext().getFilesDir(),"v671-vphone");out.mkdirs();
        ui.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS");
        ((android.media.AudioManager)getInstrumentation().getTargetContext().getSystemService(Context.AUDIO_SERVICE)).setMicrophoneMute(false);
        ui.dropShellPermissionIdentity();
    }
    byte[] frame(InputStream in,int[] op)throws Exception{
        int first=in.read();if(first<0)return null;op[0]=first&15;
        int second=in.read();if(second<0)throw new EOFException();
        long length=second&127;if(length==126)length=(in.read()<<8)|in.read();
        if(length==127){length=0;for(int n=0;n<8;n++)length=(length<<8)|in.read();}
        if(length>4000000)throw new IOException("unbounded frame");
        byte[] mask=(second&128)!=0?in.readNBytes(4):new byte[0],body=in.readNBytes((int)length);
        if(body.length!=length)throw new EOFException();
        if(mask.length>0)for(int n=0;n<body.length;n++)body[n]^=mask[n%4];
        return body;
    }
    void sendFrame(OutputStream out,JSONObject data)throws Exception{
        byte[] b=data.toString().getBytes(StandardCharsets.UTF_8);
        synchronized(out){out.write(0x81);if(b.length<126)out.write(b.length);else{out.write(126);out.write(b.length>>8);out.write(b.length&255);}out.write(b);out.flush();}
    }
    JSONObject custody(String id,byte[] pcm)throws Exception{
        return new JSONObject().put("client_session_id",id).put("byte_count",pcm.length)
            .put("sha256",hex(MessageDigest.getInstance("SHA-256").digest(pcm)));
    }
    int peak(byte[] pcm){int p=0;for(int n=0;n+1<pcm.length;n+=2)p=Math.max(p,Math.abs((short)((pcm[n]&255)|(pcm[n+1]<<8))));return p;}
    @Override void serve(Socket socket)throws Exception{
        socket.setSoTimeout(65000);
        InputStream in=new BufferedInputStream(socket.getInputStream());String request=line(in),key="";
        String route=request.split(" ")[0]+" "+request.split(" ")[1].split("\\?")[0];int requestId=httpSequence.incrementAndGet();requestRoutes.put(socket,route);requestIds.put(socket,requestId);
        httpAudit.add(new JSONObject().put("request_id",requestId).put("route",route).put("status",0));
        int length=0;
        for(String h;!(h=line(in)).isEmpty();){String lower=h.toLowerCase(Locale.ROOT);
            if(lower.startsWith("content-length:"))length=Integer.parseInt(h.substring(15).trim());
            if(lower.startsWith("sec-websocket-key:"))key=h.substring(18).trim();
        }
        if(!key.isEmpty()){
            int seq=sockets.incrementAndGet();if(openDelay>0)Thread.sleep(openDelay);
            String accept=android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)),android.util.Base64.NO_WRAP);
            OutputStream output=socket.getOutputStream();
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();httpAudit.add(new JSONObject().put("request_id",requestId).put("route",route).put("status",101));save("http-audit",new JSONObject().put("events",new JSONArray(httpAudit)));
            if(seq==1)firstOutput=output;
            String id=null;boolean authenticated=false;ByteArrayOutputStream pcm=new ByteArrayOutputStream();int[] opcode={0};int progressIndex=0;int nextProgressBytes=48000;
            for(byte[] b;!stop&&(b=frame(in,opcode))!=null;){
                if(opcode[0]==8)return;
                if(opcode[0]==9)continue;
                if(opcode[0]==2){protocol.add(seq+":binary");if(!authenticated)throw new IOException("not authenticated");pcm.write(b);
                    // Real server emits a preview roughly every 1–1.5 seconds of audio.
                    if(pcm.size()>=nextProgressBytes){
                        sendFrame(output,new JSONObject().put("type","chunk").put("index",progressIndex++).put("text","預覽"));
                        nextProgressBytes=pcm.size()+48000;
                    }
                    continue;}
                JSONObject msg=new JSONObject(new String(b,StandardCharsets.UTF_8));String type=msg.optString("type");protocol.add(seq+":"+type);
                if("auth".equals(type)){id=msg.getString("client_session_id");sessions.put(id,seq);
                    if(authDelay>0)Thread.sleep(authDelay);
                    sendFrame(output,new JSONObject().put("type","auth_ok"));authenticated=true;
                }else if("finalize".equals(type)){
                    if(!authenticated)throw new IOException("not authenticated");
                    byte[] bytes=pcm.toByteArray();audio.put(id,bytes);peaks.add(peak(bytes));
                    JSONObject receipt=custody(id,bytes);receipts.put(id,receipt);
                    if(guardErrorFirst&&seq==1){
                        JSONObject error=new JSONObject().put("type","error").put("message","audio_correction_rejected").put("retryable",true);
                        sendFrame(output,error);save("guard-error-frame",error);save("guard-server-receipt",new JSONObject(receipt.toString()));
                        synchronized(output){output.write(new byte[]{(byte)0x88,2,3,(byte)0xf5});output.flush();}
                        return;
                    }
                    if(normalFinalReady!=null){normalFinalReady.countDown();assertTrue(normalFinalRelease.await(10,TimeUnit.SECONDS));}
                    if(!((omitFirst||closeFirst)&&seq==1))sendFrame(output,new JSONObject().put("type","final").put("text",emptyFirst&&seq==1?"":"段"+seq));
                    sendFrame(output,new JSONObject(receipt.toString()).put("type","receipt"));
                    if(closeFirst&&seq==1) {
                        synchronized(output){output.write(new byte[]{(byte)0x88,2,3,(byte)0xe8});output.flush();}
                    }
                    if(omitFirst&&seq==1) {
                        Thread.sleep(45000);
                        sendFrame(output,new JSONObject().put("type","final").put("text","段1"));
                    }
                    return;
                }
            }return;
        }
        byte[] body=in.readNBytes(length);
        if(request.startsWith("POST /v1/audio-archive ")){
            String form=new String(body,StandardCharsets.ISO_8859_1);String label="name=\"client_session_id\"";
            int part=form.indexOf(label),a=form.indexOf("\r\n\r\n",part)+4,b=form.indexOf("\r\n",a);String id=form.substring(a,b);
            int wave=form.indexOf("RIFF"),data=wave+44,end=form.indexOf("\r\n--",data);
            if(wave<0||end<data)throw new IOException("missing WAV");
            byte[] pcm=Arrays.copyOfRange(body,data,end);audio.put(id,pcm);peaks.add(peak(pcm));
            JSONObject receipt=custody(id,pcm);receipts.put(id,receipt);
            int seq=sessions.computeIfAbsent(id,k->sessions.size()+1);
            if(rejectAll){
                rejectionCalls.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
                respond(socket,reject200?200:503,reject200?new JSONObject().put("text","未經校正原文").put("receipt",receipt).put("ai_corrected",false).put("correction_status","pending").put("correction_reason","audio_correction_rejected").put("retryable",true).toString():"{\"detail\":\"audio_correction_rejected\"}");return;
            }
            if(rejectTwelfth&&seq==12){respond(socket,503,"{\"detail\":\"audio_correction_rejected\"}");return;}
            recovered.add(id);
            JSONObject success=new JSONObject().put("text","段"+seq).put("receipt",receipt);
            if(!legacySuccess)success.put("ai_corrected",true);
            respond(socket,200,success.toString());
        }else if(request.startsWith("POST /v1/audio-archive/transcribe ")){
            String form=new String(body,StandardCharsets.ISO_8859_1);
            int part=form.indexOf("name=\"client_session_id\""),a=form.indexOf("\r\n\r\n",part)+4,b=form.indexOf("\r\n",a);
            String id=form.substring(a,b);
            if(guardErrorFirst&&rejectAll){respond(socket,503,"{\"detail\":\"audio_correction_rejected\"}");return;}
            recovered.add(id);JSONObject success=new JSONObject().put("text","段"+sessions.get(id)).put("receipt",receipts.get(id));if(guardErrorFirst)success.put("ai_corrected",true);respond(socket,200,success.toString());
        }else if(request.startsWith("GET /v1/audio-receipt?")){
            String id=request.substring(request.indexOf("client_session_id=")+18).split("[ &]")[0];
            JSONObject receipt=receipts.get(id);respond(socket,receipt==null?404:200,receipt==null?"{}":receipt.toString());
        }else respond(socket,404,"{}");
    }
    // Initial release uses real AudioRecord/PCM with a guest-loopback server. No compression injection.
    final boolean pcmInitial=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("pcm_initial","false").equals("true");
    void startVoice()throws Exception{
        if(pcmInitial)injectRequired=false;
        openIme();int n=0;while(!"追".contentEquals(await("com.simon.voiceime:id/btnMode").getText())&&n++<6)tap("com.simon.voiceime:id/btnMode");
        assertEquals("追",await("com.simon.voiceime:id/btnMode").getText().toString());
    }
    String lastRecordedId;
    void control(String phase,long requested)throws Exception {
        JSONObject data=new JSONObject().put("phase",phase).put("wall_ms",System.currentTimeMillis()).put("uptime_ms",SystemClock.uptimeMillis())
            .put("requested_ms",requested).put("mic",await("com.simon.voiceime:id/btnMic").getText()).put("mode",await("com.simon.voiceime:id/btnMode").getText());
        try(FileWriter writer=new FileWriter(new File(out,"control.jsonl"),true)){writer.write(data.toString()+"\n");}
    }
    void record(long duration)throws Exception{
        long started=System.currentTimeMillis();
        control("before_start",duration);
        tap("com.simon.voiceime:id/btnMic");
        control("after_start_tap",duration);
        long until=SystemClock.elapsedRealtime()+4000;while(SystemClock.elapsedRealtime()<until&&!"⏹".contentEquals(await("com.simon.voiceime:id/btnMic").getText()))Thread.sleep(50);
        assertEquals("⏹",await("com.simon.voiceime:id/btnMic").getText().toString());
        String id=null;
        long diagnosticDeadline=SystemClock.elapsedRealtime()+2000;
        do {
        for(String line:java.nio.file.Files.readAllLines(new File(getInstrumentation().getTargetContext().getFilesDir(),"ime-diagnostics.jsonl").toPath())) {
            JSONObject entry=new JSONObject(line);JSONObject data=entry.optJSONObject("data");if(data==null)data=entry;
            if(data!=null&&"start".equals(data.optString("phase"))){id=data.optString("client_session_id");started=entry.getLong("ts");}
        }
        if(id!=null&&!id.equals(lastRecordedId))break;
        Thread.sleep(20);
        } while(SystemClock.elapsedRealtime()<diagnosticDeadline);
        assertNotNull(id);assertFalse("new public tap creates a distinct recording",id.equals(lastRecordedId));lastRecordedId=id;
        if(injectRequired) {
        VoicePendingQueue recorderQueue=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        until=SystemClock.elapsedRealtime()+4000;
        while(recorderQueue.audioMs(id)<40&&SystemClock.elapsedRealtime()<until)Thread.sleep(20);
        assertTrue("native recorder has produced samples before emulator injection",recorderQueue.audioMs(id)>=40);
        java.nio.file.Files.write(new File(out,"inject-request").toPath(),id.getBytes(StandardCharsets.UTF_8));
        until=SystemClock.elapsedRealtime()+6000;
        File injected=new File(out,"injected-"+id);
        while(!injected.exists()&&SystemClock.elapsedRealtime()<until)Thread.sleep(20);
        assertTrue("external audio injection completed while recorder stays open",injected.exists());
        control("injected",duration);
        }
        // APPEND has an existing 400ms finalization grace. Include it in the
        // requested recorder duration rather than calling a 1.6s capture "0.9s".
        long remaining=duration-400-(System.currentTimeMillis()-started);
        if(remaining>0)Thread.sleep(remaining);
        control("before_stop",duration);
        assertEquals("recording must still be active before public stop","⏹",await("com.simon.voiceime:id/btnMic").getText().toString());
        tap("com.simon.voiceime:id/btnMic");
        if(closeOnStop&&firstOutput!=null){
            long closed=SystemClock.elapsedRealtime();
            synchronized(firstOutput){firstOutput.write(new byte[]{(byte)0x88,2,3,(byte)0xe8});firstOutput.flush();}
            stopCloseDelayMs=SystemClock.elapsedRealtime()-closed;
        }
        control("after_stop_tap",duration);
        until=SystemClock.elapsedRealtime()+6000;
        while(SystemClock.elapsedRealtime()<until&&!"🎤".contentEquals(await("com.simon.voiceime:id/btnMic").getText()))Thread.sleep(50);
        assertEquals("recording finished before next public tap","🎤",await("com.simon.voiceime:id/btnMic").getText().toString());
    }
    String editor()throws Exception{
        for(android.view.accessibility.AccessibilityWindowInfo w:ui.getWindows()){
            AccessibilityNodeInfo n=editable(w.getRoot());if(n!=null)return String.valueOf(n.getText());
        }return "";
    }
    AccessibilityNodeInfo textNode(AccessibilityNodeInfo n,String text) {
        if(n==null)return null;if(text.contentEquals(n.getText()==null?"":n.getText()))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=textNode(n.getChild(i),text);if(found!=null)return found;}
        return null;
    }
    void waitEditor(String expected,long timeout)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until&&!editor().equals(expected))Thread.sleep(150);
        assertEquals(expected,editor());
    }
    void assertProtocol()throws Exception{
        for(int seq=1;seq<=sockets.get();seq++){String prefix=seq+":";String first=null;
            for(String p:protocol)if(p.startsWith(prefix)){first=p;break;}
            if(first!=null)assertEquals(prefix+"auth",first);
        }
        assertNull("fixture protocol failure",failure);
    }
    void receipt(String scenario)throws Exception{
        assertProtocol();
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        JSONArray custodyEvidence=new JSONArray();
        for(String id:audio.keySet()) {
            JSONObject identity=q.recordingIdentity(id);
            assertEquals("server audio spans every captured recorder byte",identity.getLong("byte_count"),audio.get(id).length);
            assertEquals("server audio matches durable recorder SHA",identity.getString("sha256"),receipts.get(id).getString("sha256"));
            if(q.needsAttention(id))assertTrue("rejected audio is retained",q.pcmFile(id).exists());
            else assertTrue("matching custody receipt accepted",q.receiptConfirmed(id));
            custodyEvidence.put(identity.put("audio_ms",audio.get(id).length/32).put("custody_confirmed",q.receiptConfirmed(id)));
        }
        save(scenario,new JSONObject().put("verdict","PASS").put("pid",android.os.Process.myPid()).put("editor",editor()).put("protocol",new JSONArray(protocol))
            .put("http_audit",new JSONArray(httpAudit)).put("recovered",new JSONArray(recovered)).put("peaks",new JSONArray(peaks)).put("sessions",new JSONObject(sessions)).put("custody",custodyEvidence));
    }
    public void testDelayedNormalFinalThenNewClipboardIsNotReplayed()throws Exception {
        normalFinalReady=new CountDownLatch(1);normalFinalRelease=new CountDownLatch(1);startVoice();record(1600);assertTrue(normalFinalReady.await(5,TimeUnit.SECONDS));
        getInstrumentation().runOnMainSync(()->{normalFinalRelease.countDown();SystemClock.sleep(1200);});waitEditor("段1",10000);
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());List<String> history=new ArrayList<>(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());
        cm.setPrimaryClip(ClipData.newPlainText("fixture","使用者後續複製"));Thread.sleep(400);history=new ArrayList<>(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());Thread.sleep(15000);
        assertEquals("段1",editor());assertEquals("使用者後續複製",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(history,new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());
        assertEquals(0,httpResponses("POST /v1/audio-archive/transcribe",200));assertEquals(0,httpResponses("POST /v1/audio-archive/transcribe",503));receipt("normal-final-delayed-no-replay");
    }
    public void testNormalFive()throws Exception{
        startVoice();for(int n=0;n<5;n++)record(1600);
        waitEditor("段1段2段3段4段5",20000);
        assertEquals(5,audio.size());if(!pcmInitial)assertTrue("injectAudio reached real recorder",peaks.stream().anyMatch(p->p>=800));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);List<String> history=new ArrayList<>(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());cm.setPrimaryClip(ClipData.newPlainText("fixture","五段之後新剪貼簿"));Thread.sleep(400);history=new ArrayList<>(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());Thread.sleep(15000);
        assertEquals("段1段2段3段4段5",editor());assertEquals("五段之後新剪貼簿",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(history,new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());assertEquals(0,httpResponses("POST /v1/audio-archive/transcribe",200));assertEquals(0,httpResponses("POST /v1/audio-archive/transcribe",503));
        receipt("normal-five");
    }
    public void testMissingFirst()throws Exception{
        omitFirst=true;startVoice();for(int n=0;n<4;n++)record(1500);
        waitEditor("段2段3段4",50000);
        long until=SystemClock.elapsedRealtime()+15000;while(recovered.size()<1&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals(1,recovered.size());
        Thread.sleep(13000);assertEquals("late final does not duplicate editor","段2段3段4",editor());
        ClipboardHelper history=new ClipboardHelper(getInstrumentation().getTargetContext());
        assertTrue(history.getHistory().contains("段1"));
        if(!pcmInitial)assertTrue("injectAudio reached real recorder",peaks.stream().anyMatch(p->p>=800));
        receipt("missing-first");
    }
    public void testDelayedOpenShortAndRapid()throws Exception{
        openDelay=2500;authDelay=500;startVoice();record(900);record(4000);record(900);
        long until=SystemClock.elapsedRealtime()+30000;while(audio.size()<3&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals("all generations have complete archived PCM",3,audio.size());
        assertTrue("short recording preserved",audio.values().stream().allMatch(p->p.length>=20000));
        if(!pcmInitial)assertTrue("injectAudio reached real recorder",peaks.stream().anyMatch(p->p>=800));
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long custodyUntil=SystemClock.elapsedRealtime()+5000;
        while(audio.keySet().stream().anyMatch(id->!q.receiptConfirmed(id))&&SystemClock.elapsedRealtime()<custodyUntil)Thread.sleep(50);
        receipt("delayed-open-short-rapid");
    }
    public void testExactShortBeforeOpen()throws Exception {
        openDelay=2500;authDelay=500;injectRequired=false;startVoice();record(900);
        long until=SystemClock.elapsedRealtime()+20000;
        while((audio.size()<1||!protocol.contains("1:auth"))&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals(1,audio.size());assertTrue(protocol.contains("1:auth"));
        byte[] pcm=audio.values().iterator().next();
        assertTrue("actual 0.9s AudioRecord capture, allowing read-block granularity",pcm.length/32>=800&&pcm.length/32<=1100);
        assertFalse("stopped unauthenticated socket sends no binary or EOS",protocol.contains("1:binary")||protocol.contains("1:finalize"));
        receipt("exact-short-before-open");
    }
    public void testAudibleEmpty()throws Exception{
        emptyFirst=true;startVoice();record(1600);record(1600);
        waitEditor("段2",20000);
        long until=SystemClock.elapsedRealtime()+20000;while(recovered.size()<1&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals(1,recovered.size());if(!pcmInitial)assertTrue(peaks.stream().anyMatch(p->p>=800));
        receipt("audible-empty");
    }
    public void testBatchRecovery()throws Exception{
        authDelay=3500;rejectTwelfth=true;startVoice();String initialEditor=editor();
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("fixture","使用者原本剪貼簿"));
        for(int n=0;n<12;n++)record(900);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long until=SystemClock.elapsedRealtime()+40000;
        while(recovered.size()<11&&SystemClock.elapsedRealtime()<until)Thread.sleep(200);
        assertEquals(11,recovered.size());
        // Server request arrival precedes both receipt release and the phone's rejection handling.
        String rejected=lastRecordedId;long rejectedUntil=SystemClock.elapsedRealtime()+20000;
        while((q.attempts(rejected)<1||!q.pendingOldestFirst().contains(rejected))&&SystemClock.elapsedRealtime()<rejectedUntil)Thread.sleep(100);
        assertTrue(q.needsAttentionSessions().isEmpty());assertTrue("twelfth refusal completed and remains retryable",q.attempts(rejected)>=1);
        assertTrue(q.pendingOldestFirst().contains(rejected));
        assertTrue(q.pcmFile(rejected).exists());rejectTwelfth=false;
        until=SystemClock.elapsedRealtime()+25000;
        while(!q.delivered(rejected)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("availability restores automatic delivery",q.delivered(rejected));
        ClipboardHelper history=new ClipboardHelper(getInstrumentation().getTargetContext());
        for(int n=1;n<=12;n++)assertTrue(history.getHistory().contains("段"+n));
        assertEquals("段12",cm.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("recovery never inserts into current editor",initialEditor,editor());
        android.app.NotificationManager nm=(android.app.NotificationManager)getInstrumentation().getTargetContext().getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals(1,nm.getActiveNotifications().length);
        Thread.sleep(6000);assertEquals(12,recovered.size());
        receipt("batch-recovery");
    }

    public void testClosedWithoutFinal()throws Exception{
        closeFirst=true;startVoice();record(1600);record(1600);
        waitEditor("段2",15000);
        long until=SystemClock.elapsedRealtime()+15000;while(recovered.size()<1&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals(1,recovered.size());
        assertTrue(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory().contains("段1"));
        receipt("closed-without-final");
    }
    public void testSingleVoice()throws Exception{
        startVoice();record(1600);long stop=SystemClock.elapsedRealtime();waitEditor("段1",10000);save("pcm-stop-latency",new JSONObject().put("stop_to_editor_ms",SystemClock.elapsedRealtime()-stop).put("provider","guest-loopback-fixture"));receipt("single-voice");
    }

    void officialRejection(boolean modern)throws Exception {
        rejectAll=true;reject200=modern;openDelay=2500;authDelay=500;startVoice();String initialEditor=editor();
        android.content.ClipboardManager negativeClipboard=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        negativeClipboard.setPrimaryClip(ClipData.newPlainText("fixture","拒絕期間原剪貼簿"));Thread.sleep(400);List<String> originalHistory=new ArrayList<>(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());record(900);long stopped=SystemClock.elapsedRealtime();
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        String id=lastRecordedId;long until=SystemClock.elapsedRealtime()+30000;
        if(modern) {
            while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            assertTrue("HTTP 200 nonempty text is delivered despite quality metadata",q.delivered(id));
            assertTrue(q.receiptConfirmed(id));assertFalse(q.pcmFile(id).exists());
            assertEquals("未經校正原文",negativeClipboard.getPrimaryClip().getItemAt(0).getText().toString());
            assertTrue(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory().contains("未經校正原文"));
            assertEquals("recovery never edits current field",initialEditor,editor());
            int calls=httpAudit.size();Thread.sleep(6000);assertEquals("already delivered means no retry",calls,httpAudit.size());
            receipt("official-200-available-text");return;
        }
        while((q.attempts(id)<1||!q.pendingOldestFirst().contains(id))&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertFalse(q.needsAttention(id));assertTrue(q.pendingOldestFirst().contains(id));
        assertFalse(q.delivered(id));assertTrue(q.pcmFile(id).exists());
        assertEquals("503 adds no history",originalHistory,new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());
        while(SystemClock.elapsedRealtime()-stopped<15000)Thread.sleep(100);
        assertEquals("拒絕期間原剪貼簿",negativeClipboard.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(originalHistory,new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());assertEquals(initialEditor,editor());
        String persisted=shell("cat /data/data/com.simon.voiceime/files/voice_pending/"+id+".json");assertFalse(new JSONObject(persisted).has("result_text"));
        rejectAll=false;until=SystemClock.elapsedRealtime()+25000;
        while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("automatic recovery without user retry",q.delivered(id));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("old recovery does not edit current field",initialEditor,editor());
        receipt(modern?"official-200-automatic":"official-503-automatic");
    }
    public void testNonzeroEmptyArchiveRetriesThenDeliversActualClipboard()throws Exception {
        emptyArchiveFirst=true;guardErrorFirst=true;startVoice();String initialEditor=editor();record(1600);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);String id=lastRecordedId;
        long until=SystemClock.elapsedRealtime()+25000;while((!emptyArchiveIssued||q.attempts(id)<1||!q.pendingOldestFirst().contains(id))&&SystemClock.elapsedRealtime()<until)Thread.sleep(20);
        assertTrue("actual HTTP 200 empty transcript was returned",emptyArchiveIssued);assertFalse("empty text does not settle audio",q.delivered(id));assertTrue(q.pendingOldestFirst().contains(id));
        assertTrue("server retains exact original custody",q.receiptConfirmed(id));boolean nonzero=false;for(byte sample:audio.get(id))if(sample!=0){nonzero=true;break;}assertTrue("original has a nonzero sample without loudness threshold",nonzero);
        until=SystemClock.elapsedRealtime()+25000;while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);assertTrue("automatic retry delivers without manual action",q.delivered(id));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(initialEditor,editor());
        receipt("nonzero-empty-archive-auto-recovery");
    }
    public void testAsrFallbackDeliversWithoutSuccessfulCorrection()throws Exception {
        asrFallbackResponse=true;guardErrorFirst=true;startVoice();String initialEditor=editor();record(1600);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        String id=lastRecordedId;long until=SystemClock.elapsedRealtime()+20000;
        while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("valid ASR reaches clipboard despite explicit correction failure",q.delivered(id));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(initialEditor,editor());
        assertEquals(1,recovered.size());assertTrue(httpResponses("POST /v1/audio-archive/transcribe",200)>0);
        receipt("asr-fallback-clipboard-no-quality-block");
    }
    public void testGuardError1013SameProcessArchiveRecovery()throws Exception {
        guardErrorFirst=true;rejectAll=true;startVoice();String initialEditor=editor();record(1600);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        String id=lastRecordedId;long until=SystemClock.elapsedRealtime()+20000;
        while(!q.receiptConfirmed(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("durable original server custody confirmed",q.receiptConfirmed(id));assertFalse(q.delivered(id));
        assertTrue(receipts.get(id).getLong("byte_count")>0);assertEquals(initialEditor,editor());
        rejectAll=false;until=SystemClock.elapsedRealtime()+15000;
        while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        save("guard-same-process-state",new JSONObject().put("session",id).put("receipt_confirmed",q.receiptConfirmed(id)).put("delivered",q.delivered(id)).put("http_transcribe_success_200",httpResponses("POST /v1/audio-archive/transcribe",200)).put("http_transcribe_rejected_503",httpResponses("POST /v1/audio-archive/transcribe",503)).put("local_pcm_present",q.pcmFile(id).exists()));
        assertTrue("same process must deliver corrected server-retained transcript without restart or user retry",q.delivered(id));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(initialEditor,editor());assertEquals(1,recovered.size());
        receipt("guard-error1013-same-process-automatic");
    }
    public void testGuardError1013LeavesServerCustodyForRestart()throws Exception {
        guardErrorFirst=true;rejectAll=true;startVoice();String initialEditor=editor();record(1600);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        String id=lastRecordedId;long until=SystemClock.elapsedRealtime()+20000;
        while(!q.receiptConfirmed(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("original WS custody confirmed through GET",q.receiptConfirmed(id));assertFalse(q.delivered(id));
        JSONObject original=receipts.get(id);assertNotNull(original);assertTrue(original.getLong("byte_count")>0);
        assertTrue(new File(out,"guard-server-receipt.json").exists());
        String diagnostics=shell("cat /data/data/com.simon.voiceime/files/ime-diagnostics.jsonl");
        assertTrue(diagnostics.contains("audio_correction_rejected"));assertTrue(diagnostics.contains("\"close_code\":1013"));
        assertEquals(initialEditor,editor());
        save("guard-before-restart",new JSONObject().put("session",id).put("receipt",original).put("delivered",q.delivered(id)).put("local_pcm_present",q.pcmFile(id).exists()).put("verdict","PASS").put("pid",android.os.Process.myPid()));
    }
    public void testGuardError1013ServerCustodyRecoveryAfterRestart()throws Exception {
        JSONObject receipt=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(out,"guard-server-receipt.json").toPath()),StandardCharsets.UTF_8));
        assertEquals("custody fixture belongs to this exact APK",androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("apk_sha256"),receipt.getString("apk_sha256"));
        receipt.remove("apk_sha256");String id=receipt.getString("client_session_id");receipts.put(id,receipt);sessions.put(id,1);
        int rawHistoryBefore=Collections.frequency(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory(),"未經校正原文");
        startVoice();String initialEditor=editor();VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long until=SystemClock.elapsedRealtime()+30000;while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("restart automatically fetches corrected server-retained PCM transcript",q.delivered(id));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(initialEditor,editor());
        assertEquals("recovery adds no rejected text",rawHistoryBefore,Collections.frequency(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory(),"未經校正原文"));
        assertEquals(1,recovered.size());receipt("guard-error1013-restart-automatic");
    }
    public void testOfficial503Rejection()throws Exception {officialRejection(false);}
    public void testOfficial200Attention()throws Exception {officialRejection(true);}
    public void testLegacyArchiveSuccess()throws Exception {legacySuccess=true;officialRejection(false);}
    public void testCloseMillisecondsAfterStop()throws Exception {
        closeOnStop=true;startVoice();record(1600);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long until=SystemClock.elapsedRealtime()+20000;
        while(recovered.isEmpty()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals(1,recovered.size());assertEquals(lastRecordedId,recovered.get(0));
        assertTrue(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory().contains("段1"));
        String diagnostics=shell("cat /data/data/com.simon.voiceime/files/ime-diagnostics.jsonl");
        assertTrue("actual close callback exercised",diagnostics.contains("ws_closed_without_final")||diagnostics.contains("ws_closing_without_final"));
        save("close-timing",new JSONObject().put("stop_close_write_ms",stopCloseDelayMs));receipt("close-milliseconds-after-stop");
    }

    public void testLowSilence()throws Exception{
        emptyFirst=true;injectRequired=false;startVoice();
        String before=editor();android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("fixture","靜音前剪貼簿"));Thread.sleep(400);List<String> priorHistory=new ArrayList<>(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());
        ui.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS");
        ((android.media.AudioManager)getInstrumentation().getTargetContext().getSystemService(Context.AUDIO_SERVICE)).setMicrophoneMute(true);
        ui.dropShellPermissionIdentity();record(900);Thread.sleep(15000);
        save("low-silence-observation",new JSONObject().put("peaks",new JSONArray(peaks)).put("recovered",new JSONArray(recovered)).put("clipboard",cm.getPrimaryClip().getItemAt(0).getText().toString()).put("http_audit",new JSONArray(httpAudit)));
        assertEquals(before,editor());assertEquals(0,recovered.size());assertEquals(1,audio.size());
        assertTrue(peaks.stream().allMatch(p->p<800));assertEquals("靜音前剪貼簿",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(priorHistory,new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory());receipt("low-silence");
    }

    public void testLongDisconnectThenTwoShort()throws Exception {
        omitFirst=true;closeOnStop=true;startVoice();record(51000);
        String longId=lastRecordedId;
        closeOnStop=false;
        record(1600);record(1600);
        waitEditor("段2段3",15000);
        long until=SystemClock.elapsedRealtime()+15000;
        while(recovered.size()<1&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertEquals(1,recovered.size());assertEquals(longId,recovered.get(0));
        assertTrue("long capture is at least 50 seconds",audio.get(longId).length/32>=50000);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long deliveredUntil=SystemClock.elapsedRealtime()+15000;
        while(!q.delivered(longId)&&SystemClock.elapsedRealtime()<deliveredUntil)Thread.sleep(100);
        assertTrue("long transcript reached the phone delivery acknowledgement",q.delivered(longId));
        assertTrue(new ClipboardHelper(getInstrumentation().getTargetContext()).getHistory().contains("段1"));
        Thread.sleep(6000);assertEquals("no duplicate recovery",1,recovered.size());
        assertEquals("後兩段出字不重複","段2段3",editor());
        save("long-stop-timing",new JSONObject().put("stop_close_write_ms",stopCloseDelayMs));
        receipt("long-disconnect-two-short");
    }

    public void testOfflineLeavesDurableRecordingForRestart()throws Exception {
        rejectAll=true;openDelay=2500;authDelay=500;startVoice();record(900);
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long until=SystemClock.elapsedRealtime()+15000;while(q.attempts(lastRecordedId)<1&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertFalse(q.delivered(lastRecordedId));assertTrue(q.pcmFile(lastRecordedId).exists());
        java.nio.file.Files.write(new File(out,"restart-session").toPath(),lastRecordedId.getBytes(StandardCharsets.UTF_8));
        save("restart-before",new JSONObject().put("session",lastRecordedId).put("bytes",q.pcmFile(lastRecordedId).length()).put("verdict","PASS"));
    }
    public void testAutomaticRecoveryAfterProcessRestart()throws Exception {
        String id=new String(java.nio.file.Files.readAllBytes(new File(out,"restart-session").toPath()),StandardCharsets.UTF_8);
        startVoice();String initialEditor=editor();VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        long until=SystemClock.elapsedRealtime()+30000;while(!q.delivered(id)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        assertTrue("persisted recording recovered on new process",q.delivered(id));
        android.content.ClipboardManager cm=(android.content.ClipboardManager)getInstrumentation().getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("段1",cm.getPrimaryClip().getItemAt(0).getText().toString());assertEquals(initialEditor,editor());
        Thread.sleep(6000);assertEquals(1,recovered.size());receipt("restart-after");
    }

}
