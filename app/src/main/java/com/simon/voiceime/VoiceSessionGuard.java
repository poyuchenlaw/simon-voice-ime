package com.simon.voiceime;

/** Monotonic, per-recording limits. A successful socket send is not server progress. */
final class VoiceSessionGuard {
    static final long STALL_MS = 20_000, SCREEN_OFF_IDLE_MS = 60_000;
    static final int DEFAULT_CAP_MINUTES = 10;
    private final long started, capMs;
    private long progress, speech, screenOffSince = -1;
    private int lastChunkIndex = -1;
    private String lastText = "";
    private boolean stopped;

    VoiceSessionGuard(long now, int capMinutes) {
        started = progress = speech = now;
        capMs = Math.max(1, Math.min(30, capMinutes)) * 60_000L;
    }
    synchronized void speech(long now) { speech = now; }
    synchronized void serverProgress(long now, int chunkIndex, String text) {
        boolean newText = text != null && !VoiceResultText.isSilence(text) && !text.equals(lastText);
        if (chunkIndex > lastChunkIndex || newText) progress = now;
        if (chunkIndex > lastChunkIndex) lastChunkIndex = chunkIndex;
        if (newText) { lastText = text; speech = now; }
    }
    synchronized String check(long now, boolean screenOn, boolean needsServer) {
        if (stopped) return "";
        if (screenOn) screenOffSince = -1;
        else if (screenOffSince < 0) screenOffSince = now;
        String reason = "";
        if (now - started >= capMs) reason = "cap_stop";
        else if (!screenOn && now - Math.max(screenOffSince, speech) >= SCREEN_OFF_IDLE_MS)
            reason = "screen_off_idle_stop";
        else if (needsServer && now - progress >= STALL_MS) reason = "stall_stop";
        if (!reason.isEmpty()) stopped = true;
        return reason;
    }
}
