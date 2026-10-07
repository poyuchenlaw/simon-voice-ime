package com.simon.voiceime;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;
import android.os.SystemClock;
import org.json.*;

/** Isolated A4 public-UI probe. It is not part of the locked release observer/matrix. */
public class ModeBoundary6721AndroidTest extends VoicePhone671AndroidTest {
    final CountDownLatch replaceReply=new CountDownLatch(1);
    volatile boolean modeTapped;
    volatile Throwable modeFailure;
    @Override void respond(Socket socket,int code,String text)throws Exception {
        if("POST /v1/replace".equals(requestRoutes.get(socket)))replaceReply.await(20,TimeUnit.SECONDS);
        super.respond(socket,code,text);
    }
    @Override protected void tearDown()throws Exception {replaceReply.countDown();super.tearDown();}
    public void testQuietPrefixModeChangeCannotSettleAsDigitalSilence()throws Exception {
        startVoice();
        Thread switcher=new Thread(()->{
            try {
                Thread.sleep(3000);
                ui.adoptShellPermissionIdentity("android.permission.MODIFY_AUDIO_SETTINGS");
                ((android.media.AudioManager)getInstrumentation().getTargetContext().getSystemService(android.content.Context.AUDIO_SERVICE)).setMicrophoneMute(true);
                ui.dropShellPermissionIdentity();
                Thread.sleep(1500);tap("com.simon.voiceime:id/btnMode");modeTapped=true;
            }
            catch(Throwable error){modeFailure=error;}
        });
        switcher.start();record(6500);switcher.join(3000);
        assertNull(modeFailure);assertTrue(modeTapped);assertEquals("換",await("com.simon.voiceime:id/btnMode").getText().toString());
        VoicePendingQueue q=VoicePendingQueue.getInstance(getInstrumentation().getTargetContext().getFilesDir(),16000);
        String id=lastRecordedId;byte[] original=q.pcmBytes(id);int originalPeak=peak(original);
        assertTrue("synthetic low amplitude actually reached original AudioRecord PCM",originalPeak>0&&originalPeak<800);
        int tailPeak=peak(java.util.Arrays.copyOfRange(original,Math.max(0,original.length-32000),original.length));
        save("mode-input-proof",new JSONObject().put("original_peak",originalPeak).put("tail_peak",tailPeak).put("original_bytes",original.length));
        assertEquals("explicit zero tail reached actual AudioRecord",0,tailPeak);
        assertTrue(original.length>=160000);assertTrue(protocol.stream().anyMatch(x->x.endsWith(":binary")));assertNotNull(firstOutput);
        sendFrame(firstOutput,new JSONObject().put("type","final").put("text",""));Thread.sleep(1200);
        save("mode-empty-state",new JSONObject().put("original_peak",originalPeak).put("original_bytes",original.length).put("tail_peak",tailPeak).put("delivered",q.delivered(id)).put("receipt_confirmed",q.receiptConfirmed(id)).put("local_pcm",q.pcmFile(id).exists()).put("mode",await("com.simon.voiceime:id/btnMode").getText()).put("protocol",new JSONArray(protocol)));
        assertFalse("nonzero original audio may not be settled from a zero rolling tail",q.delivered(id));
        assertTrue("original recording remains available for recovery",q.pcmFile(id).exists()||q.receiptConfirmed(id));
    }
}
