package com.simon.voiceime;

/** Digital-zero detection, not voice activity detection. Times use a monotonic clock. */
public final class SilenceWatchdog {
    public enum Verdict { OK, READ_ERROR, SILENT_WARN, SILENT_RESTART }
    public static final double SILENT_RMS = 2.0 / 32768;
    public static final long SILENT_WARN_MS = 3000;
    public static final long SILENT_RESTART_MS = 6000;
    public static final int READ_ERROR_LIMIT = 3;
    private final double silentRms;
    private final long warnMs, restartMs;
    private final int errorLimit;
    private long silentSince;
    private int errors;
    private boolean warned, restarted;

    public SilenceWatchdog(long startedMs) {
        this(startedMs, SILENT_RMS, SILENT_WARN_MS, SILENT_RESTART_MS, READ_ERROR_LIMIT);
    }

    public SilenceWatchdog(long startedMs, double rms, long warn, long restart, int limit) {
        silentSince = startedMs;
        silentRms = Double.isFinite(rms) && rms > 0 ? rms : SILENT_RMS;
        warnMs = warn > 0 ? warn : SILENT_WARN_MS;
        restartMs = restart >= warnMs ? restart : Math.max(warnMs, SILENT_RESTART_MS);
        errorLimit = limit > 0 ? limit : READ_ERROR_LIMIT;
    }

    public Verdict feed(int readResult, double rms, long nowMs) {
        if (readResult <= 0) {
            if (++errors >= errorLimit) {
                errors = 0;
                return Verdict.READ_ERROR;
            }
            return Verdict.OK;
        }
        errors = 0;
        if (!Double.isFinite(rms) || rms < 0 || rms >= silentRms) {
            silentSince = -1;
            warned = false;
            return Verdict.OK;
        }
        if (silentSince < 0) silentSince = nowMs;
        long elapsed = nowMs - silentSince;
        if (!restarted && elapsed >= restartMs) {
            restarted = true; // At most one digital-silence restart per recording.
            warned = true;
            return Verdict.SILENT_RESTART;
        }
        if (!warned && elapsed >= warnMs) {
            warned = true;
            return Verdict.SILENT_WARN;
        }
        return Verdict.OK;
    }
}
