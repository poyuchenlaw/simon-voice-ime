package com.simon.voiceime;

/** Phone-only local STT seam; the watch build intentionally has no implementation. */
public interface LocalSTT {
    interface StreamingCallback {
        void onSegmentResult(String text);
    }

    void init();
    void feedAudioChunk(float[] samples, StreamingCallback callback);
    void flushVad(StreamingCallback callback);
    void waitForPendingSegments();
    String recognize(byte[] pcmData, int sampleRate);
    boolean isReady();
    boolean isStreamingReady();
    void resetStreamingState();
    void release();
}
