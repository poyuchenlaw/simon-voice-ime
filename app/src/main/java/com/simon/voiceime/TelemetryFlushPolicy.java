package com.simon.voiceime;

/** Monotonic-clock scheduling policy; spool pressure never bypasses user preferences. */
final class TelemetryFlushPolicy {
    static boolean eligible(String mode,long now,long lastAttempt,long lastInput,boolean wifi,boolean manual) {
        if("wifi".equals(mode)&&!wifi)return false;
        if(manual)return true;
        long interval="hourly".equals(mode)?3_600_000L:60_000L;
        if(now-lastAttempt<interval)return false;
        if(!"realtime".equals(mode)&&!"hourly".equals(mode)&&!"wifi".equals(mode))
            return now-lastInput>=60_000L;
        return true;
    }
}
