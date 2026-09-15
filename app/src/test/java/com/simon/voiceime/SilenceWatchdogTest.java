package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.*;

public class SilenceWatchdogTest {
    @Test public void normalAudioIsAlwaysOk() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        for (long t = 0; t <= 20000; t += 1000)
            assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0.1, t));
    }
    @Test public void twoReadErrorsDoNotRestart() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(0, 0, 0));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(-3, 0, 10));
        assertEquals(SilenceWatchdog.Verdict.READ_ERROR, w.feed(-3, 0, 20));
    }
    @Test public void goodReadResetsErrorStreak() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        w.feed(-1, 0, 0); w.feed(-1, 0, 1);
        w.feed(320, 0.1, 2);
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(-1, 0, 3));
    }
    @Test public void silenceWarnsAndRestartsOnlyOnce() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 2900));
        assertEquals(SilenceWatchdog.Verdict.SILENT_WARN, w.feed(320, 0, 3000));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 4000));
        assertEquals(SilenceWatchdog.Verdict.SILENT_RESTART, w.feed(320, 0, 6000));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 12000));
    }
    @Test public void soundResetsSilenceTimer() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        w.feed(320, 0, 2000); w.feed(320, 0.1, 2500);
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 3000));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 5900));
        assertEquals(SilenceWatchdog.Verdict.SILENT_WARN, w.feed(320, 0, 6000));
    }
    @Test public void quietRoomIsNotDigitalSilence() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 30.0 / 32768, 10000));
    }
    @Test public void laterSilenceCannotRestartTwice() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        assertEquals(SilenceWatchdog.Verdict.SILENT_RESTART, w.feed(320, 0, 6000));
        w.feed(320, 0.1, 7000);
        w.feed(320, 0, 8000);
        assertEquals(SilenceWatchdog.Verdict.SILENT_WARN, w.feed(320, 0, 11000));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 0, 14000));
    }
    @Test public void configuredThresholdsAreUsed() {
        SilenceWatchdog w = new SilenceWatchdog(1000, 0.001, 100, 200, 2);
        assertEquals(SilenceWatchdog.Verdict.SILENT_WARN, w.feed(320, 0.0005, 1100));
        assertEquals(SilenceWatchdog.Verdict.SILENT_RESTART, w.feed(320, 0.0005, 1200));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(-1, 0, 1210));
        assertEquals(SilenceWatchdog.Verdict.READ_ERROR, w.feed(-1, 0, 1220));
    }
    @Test public void exactRmsThresholdAndUnknownAreNotSilence() {
        SilenceWatchdog w = new SilenceWatchdog(0);
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(320, 2.0 / 32768, 6000));
        assertEquals(SilenceWatchdog.Verdict.OK, w.feed(1, Double.NaN, 7000));
    }
}
