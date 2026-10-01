package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
public class TelemetryFlushPolicyTest {
    @Test public void defaultWaitsUntilOneMinuteAfterLastInput() {
        assertFalse(TelemetryFlushPolicy.eligible("idle",120000,0,60001,false,false));
        assertTrue(TelemetryFlushPolicy.eligible("idle",120000,0,60000,false,false));
        assertFalse(TelemetryFlushPolicy.eligible("idle",120000,90000,0,false,false));
        assertFalse(TelemetryFlushPolicy.eligible("unknown",120000,0,60001,false,false));
    }
    @Test public void realtimeAndHourlyDoNotDependOnIdleAndDoNotFlushFromBatchPressure() {
        assertFalse(TelemetryFlushPolicy.eligible("realtime",59999,0,59999,false,false));
        assertTrue(TelemetryFlushPolicy.eligible("realtime",60000,0,60000,false,false));
        assertFalse(TelemetryFlushPolicy.eligible("hourly",3599999,0,0,true,false));
        assertTrue(TelemetryFlushPolicy.eligible("hourly",3600000,0,3600000,false,false));
    }
    @Test public void wifiRestrictionAlsoAppliesToManualUpload() {
        assertFalse(TelemetryFlushPolicy.eligible("wifi",3600000,0,0,false,false));
        assertFalse(TelemetryFlushPolicy.eligible("wifi",3600000,0,0,false,true));
        assertTrue(TelemetryFlushPolicy.eligible("wifi",60000,0,60000,true,false));
        assertTrue(TelemetryFlushPolicy.eligible("wifi",1,0,1,true,true));
        assertTrue(TelemetryFlushPolicy.eligible("idle",1,0,1,false,true));
    }
}
