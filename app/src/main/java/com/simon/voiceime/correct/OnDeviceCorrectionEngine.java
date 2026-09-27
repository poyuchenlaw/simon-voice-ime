package com.simon.voiceime.correct;

/** Phone-only correction seam; the watch build intentionally has no implementation. */
public interface OnDeviceCorrectionEngine {
    void init();
    boolean isCorrectorReady();
    boolean isPunctuationReady();
    String correct(String senseVoiceText);
    String correct(String senseVoiceText, String precedingContext);
    String correctDeterministic(String senseVoiceText);
    void release();
}
