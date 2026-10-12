package com.simon.voiceime;
import android.view.*;
import android.widget.*;
import android.os.SystemClock;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Real editor integration with a one-shot loopback gateway; never contacts the production service. */
public class DualHandTracks686AndroidTest extends DualHand686AndroidTest {
    private ServerSocket fake;
    private volatile JSONObject received;
    private volatile Throwable failure;
    private String previousServer;
    private Thread gatewayThread;
    private final java.util.concurrent.CountDownLatch responded=new java.util.concurrent.CountDownLatch(1);
    void gateway(String text,int status)throws Exception {
        inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
        var prefs=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);previousServer=prefs.getString("server_url","http://100.84.86.128:8001");
        fake=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));prefs.edit().putString("server_url","http://127.0.0.1:"+fake.getLocalPort()).commit();
        gatewayThread=new Thread(()->{try(Socket socket=fake.accept()){
            socket.setSoTimeout(5000);InputStream in=socket.getInputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();
            while(header.size()<8192){int value=in.read();if(value<0)throw new EOFException();header.write(value);if(header.toString("UTF-8").endsWith("\r\n\r\n"))break;}
            String headers=header.toString("UTF-8");assertTrue(headers.startsWith("POST /v1/ime/dual-hand/reconstruct HTTP/1.1"));
            int length=-1;for(String line:headers.split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
            assertTrue(length>0&&length<8192);byte[] input=new byte[length];int at=0;while(at<length){int count=in.read(input,at,length-at);if(count<0)throw new EOFException();at+=count;}received=new JSONObject(new String(input,StandardCharsets.UTF_8));
            byte[] body=new JSONObject().put("text",text).put("length_ok",true).put("latency_ms",1).put("model","fake-model").put("thinking",false).toString().getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 "+status+" Test\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        }catch(Throwable error){failure=error;}finally{responded.countDown();}},"DualHandFakeGateway");gatewayThread.start();
    }
    void awaitGateway()throws Exception {
        assertTrue("fake gateway completed an actual request",responded.await(5,java.util.concurrent.TimeUnit.SECONDS));
        assertNull("fake gateway failure",failure);assertNotNull("fake gateway received request",received);
    }
    @Override void preparePreferences(){super.preparePreferences();if(fake!=null)inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("server_url","http://127.0.0.1:"+fake.getLocalPort()).commit();}
    @Override protected void tearDown()throws Exception {
        try{if(fake!=null)fake.close();if(gatewayThread!=null)gatewayThread.join(5000);if(previousServer!=null)inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("server_url",previousServer).commit();}finally{super.tearDown();}
    }
    void confirmation()throws Exception{swipe("4","up");swipe("B","down");swipe("5","down");swipe("E","left");}
    void waitCandidates()throws Exception{long end=SystemClock.uptimeMillis()+5000;final boolean[] ready={false};do{inst.runOnMainSync(()->ready[0]=page.candidateRow().getChildCount()>0);if(ready[0])return;Thread.sleep(40);}while(SystemClock.uptimeMillis()<end);fail("local candidates ready");}
    public void testCandidatesPrefixAndGrid()throws Exception {
        startDual();confirmation();waitCandidates();screenshot("dh-candidates");
        swipe("1","tap");swipe("A","tap");waitCandidates();tap("確認");
        inst.runOnMainSync(()->{assertEquals(java.util.List.of("ㄅㄚ"),page.buffer().syllables());});assertEquals("確認八",editorText(await("test_input")));
        inst.runOnMainSync(()->page.requestGrid(0));Thread.sleep(150);screenshot("dh-grid");
        swipe("core","right");assertEquals("確認八",editorText(await("test_input")));
    }
    public void testManualMemoryDoesNotReachAiRequest()throws Exception {
        gateway("確認",200);
        File personal=new File(inst.getTargetContext().getFilesDir(),"dual_hand_personal.tsv");
        byte[] previous=personal.isFile()?java.nio.file.Files.readAllBytes(personal.toPath()):null;
        try{startDual();confirmation();waitCandidates();tap("確認");
        confirmation();waitCandidates();
        final String[] baseline={null};inst.runOnMainSync(()->{
            try{
                var f=DualHandKeyboard.class.getDeclaredField("hooks");f.setAccessible(true);var editor=(DualHandEditor)f.get(page);
                var m=DualHandEditor.class.getDeclaredField("memory");m.setAccessible(true);
                ((DualHandMemory)m.get(editor)).remember(java.util.List.of("ㄑㄩㄝ","ㄖㄣ"),"卻仁",false);
                baseline[0]=page.buffer().aiDraft();page.requestGrid(0);
            }catch(Exception e){throw new RuntimeException(e);}
        });
        tap("卻仁");assertEquals("確認卻仁",editorText(await("test_input")));swipe("core","down");awaitGateway();
        assertEquals(2,received.length());assertEquals("確認",received.getString("context"));assertEquals(baseline[0],received.getString("draft"));assertFalse(received.toString().contains("卻仁"));
        }finally{if(previous==null)java.nio.file.Files.deleteIfExists(personal.toPath());else java.nio.file.Files.write(personal.toPath(),previous);}
    }
    public void testAiEqualLengthMismatchIsKeptAndUndoPreservesSounds()throws Exception {
        gateway("確天",200);startDual();confirmation();String[] original={null};inst.runOnMainSync(()->original[0]=page.buffer().draft());swipe("core","down");
        long end=SystemClock.uptimeMillis()+5000;while(!"確天".equals(editorText(await("test_input")))&&SystemClock.uptimeMillis()<end)Thread.sleep(30);
        awaitGateway();assertEquals("確天",editorText(await("test_input")));assertNotNull(received);assertEquals(2,received.length());assertTrue(received.has("context")&&received.has("draft"));assertEquals(original[0],received.getString("draft"));screenshot("dh-ai-result");
        await("送出 up ⌫ left 還原 down AI right 送出");assertFunctionHints(true,false);
        swipe("core","left");assertEquals(original[0],editorText(await("test_input")));inst.runOnMainSync(()->assertEquals(java.util.List.of("ㄑㄩㄝ","ㄖㄣ"),page.buffer().syllables()));screenshot("dh-ai-undo");
        await("送出 up ⌫ left 刪字 down AI right 送出");assertFunctionHints(false,false);
    }
    public void testAiLengthRejectedPreservesDraft()throws Exception {
        gateway("確認了",200);startDual();confirmation();String[] original={null};inst.runOnMainSync(()->original[0]=page.buffer().draft());swipe("core","down");
        awaitGateway();await("AI 字數不符，保留原稿");assertEquals(original[0],editorText(await("test_input")));inst.runOnMainSync(()->assertFalse(page.buffer().aiShown()));
    }
    public void testAi504PreservesDraft()throws Exception {
        gateway("",504);startDual();confirmation();String[] original={null};inst.runOnMainSync(()->original[0]=page.buffer().draft());swipe("core","down");
        awaitGateway();await("AI 逾時，保留原稿");assertEquals(original[0],editorText(await("test_input")));inst.runOnMainSync(()->assertFalse(page.buffer().aiShown()));
    }
}
