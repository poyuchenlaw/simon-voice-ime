package com.simon.voiceime;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Runs source-exact service component tests with host fakes; no new Android test dependency. */
public class VoiceServiceHostTest {
    @Test public void serviceAudioDeliveryAndSilenceContract() throws Exception {
        File root=new File(System.getProperty("user.dir"));
        if(!new File(root,"scripts/test_voice_service_host.py").isFile())root=root.getParentFile();
        Process process=new ProcessBuilder("python3","scripts/test_voice_service_host.py","--out","out/phone-suite-host")
                .directory(root).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        int code=process.waitFor();System.out.println(output);
        assertEquals(output,0,code);
    }
}
