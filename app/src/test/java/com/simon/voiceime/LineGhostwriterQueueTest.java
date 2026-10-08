package com.simon.voiceime;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class LineGhostwriterQueueTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void spokenDirectiveNeverBecomesAutomaticTranscriptionAfterRestart() throws Exception {
        VoicePendingQueue q=new VoicePendingQueue(temp.getRoot(),16000);
        String id=q.begin();q.append(id,new byte[8000],8000);q.retainDirective(id);
        q.finishRecording(id);q.runIO(()->null);q.markPending(id,"network_available");q.releaseReceiptWait(id);
        assertFalse(q.pendingOldestFirst().contains(id));assertTrue(q.pcmFile(id).exists());
        q.persistResult(id,"generated reply whose chat became stale",true,false);
        q.retryForUser(id);assertFalse(q.pendingOldestFirst().contains(id));
        VoicePendingQueue restarted=new VoicePendingQueue(temp.getRoot(),16000);
        restarted.retryForUser(id);
        assertFalse(restarted.pendingOldestFirst().contains(id));
        assertTrue(restarted.needsAttentionSessions().contains(id));
    }
}
