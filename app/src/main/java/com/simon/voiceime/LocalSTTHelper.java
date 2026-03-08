package com.simon.voiceime;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本機端 STT 引擎 v3.1 — 模型內建版
 *
 * Layer 1: Silero VAD — 語音活動偵測，自動斷句（內建 ~632KB）
 * Layer 2: SenseVoice — 高精度離線辨識（內建 ~229MB）
 *
 * 模型打包在 APK assets/ 中，安裝即可用，無需下載。
 */
public class LocalSTTHelper {
    private static final String TAG = "LocalSTT";
    private static final int SAMPLE_RATE = 16000;

    // Engines
    private OfflineRecognizer offlineRecognizer;
    private Vad vad;

    // State
    private volatile boolean offlineReady = false;
    private volatile boolean vadReady = false;

    // SenseVoice per-segment executor (ensures sequential processing)
    private ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingSegments = new AtomicInteger(0);

    private final Context context;

    public interface StreamingCallback {
        /** VAD 偵測到句尾，SenseVoice 完成精確辨識 */
        void onSegmentResult(String text);
    }

    public LocalSTTHelper(Context context) {
        this.context = context;
    }

    // ==================== Initialization ====================

    /**
     * 初始化引擎（從 APK assets 載入，不需網路）。
     * 在背景執行緒呼叫。
     */
    public void init() {
        AssetManager am = context.getAssets();

        // Phase 1: SenseVoice (critical)
        try {
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel("sensevoice/model.int8.onnx");
            senseVoice.setLanguage("zh");
            senseVoice.setUseInverseTextNormalization(true);

            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens("sensevoice/tokens.txt");
            modelConfig.setNumThreads(4);
            modelConfig.setProvider("cpu");

            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(modelConfig);
            config.setDecodingMethod("greedy_search");

            offlineRecognizer = new OfflineRecognizer(am, config);
            offlineReady = true;
            Log.i(TAG, "SenseVoice 就緒（從 assets 載入）");
        } catch (Throwable t) {
            Log.e(TAG, "SenseVoice 初始化失敗", t);
            return;
        }

        // Phase 2: Silero VAD
        try {
            SileroVadModelConfig sileroConfig = new SileroVadModelConfig();
            sileroConfig.setModel("silero_vad.onnx");
            sileroConfig.setThreshold(0.5f);
            sileroConfig.setMinSilenceDuration(0.5f);  // 0.5 秒靜音斷句（串流模式需要快速分段）
            sileroConfig.setMinSpeechDuration(0.3f);
            sileroConfig.setWindowSize(512);
            sileroConfig.setMaxSpeechDuration(3.0f);  // 最長 3 秒強制切割（~10 字），確保串流均勻上傳

            VadModelConfig vadConfig = new VadModelConfig();
            vadConfig.setSileroVadModelConfig(sileroConfig);
            vadConfig.setSampleRate(SAMPLE_RATE);
            vadConfig.setNumThreads(1);
            vadConfig.setProvider("cpu");

            vad = new Vad(am, vadConfig);
            vadReady = true;
            Log.i(TAG, "VAD 就緒（從 assets 載入）");
        } catch (Throwable t) {
            Log.e(TAG, "VAD 初始化失敗（串流辨識停用）", t);
        }

        // Clean up old downloaded models (v2.7/v3.0 remnants)
        cleanupOldModels();

        Log.i(TAG, "語音引擎就緒" + (vadReady ? "（VAD+精確辨識）" : "（精確辨識）"));
    }

    // ==================== Streaming Processing ====================

    /**
     * 餵入音訊片段（從錄音執行緒呼叫）。
     * VAD 偵測句尾 → SenseVoice 精確辨識。
     * 僅在 APPEND 模式使用。
     */
    public void feedAudioChunk(float[] samples, StreamingCallback callback) {
        if (!vadReady || !offlineReady) return;

        vad.acceptWaveform(samples);

        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            vad.pop();

            float[] segSamples = segment.getSamples();
            if (segSamples == null || segSamples.length < (int)(SAMPLE_RATE * 0.3f)) {
                continue;
            }

            pendingSegments.incrementAndGet();
            final float[] finalSamples = segSamples;
            segmentExecutor.submit(() -> {
                try {
                    String text = recognizeOffline(finalSamples, SAMPLE_RATE);
                    if (text != null && !text.isEmpty()) {
                        callback.onSegmentResult(text);
                    }
                } finally {
                    pendingSegments.decrementAndGet();
                }
            });
        }
    }

    /**
     * 錄音結束時呼叫，刷出 VAD 緩衝區中剩餘的語音。
     */
    public void flushVad(StreamingCallback callback) {
        if (!vadReady || !offlineReady) return;

        try {
            vad.flush();

            while (!vad.empty()) {
                SpeechSegment segment = vad.front();
                vad.pop();

                float[] segSamples = segment.getSamples();
                if (segSamples == null || segSamples.length < (int)(SAMPLE_RATE * 0.2f)) {
                    continue;
                }

                pendingSegments.incrementAndGet();
                final float[] finalSamples = segSamples;
                segmentExecutor.submit(() -> {
                    try {
                        String text = recognizeOffline(finalSamples, SAMPLE_RATE);
                        if (text != null && !text.isEmpty()) {
                            callback.onSegmentResult(text);
                        }
                    } finally {
                        pendingSegments.decrementAndGet();
                    }
                });
            }

            vad.reset();
        } catch (Throwable t) {
            Log.w(TAG, "VAD flush error", t);
        }
    }

    /**
     * 等待所有 pending SenseVoice 段落處理完成（最多 5 秒）。
     */
    public void waitForPendingSegments() {
        if (pendingSegments.get() == 0) return;
        long deadline = System.currentTimeMillis() + 5000;
        while (pendingSegments.get() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
        }
    }

    // ==================== Single-Shot Recognition ====================

    /**
     * 單次辨識完整 PCM 音訊（REPLACE/SPELL/TRANSLATE 模式使用）。
     */
    public String recognize(byte[] pcmData, int sampleRate) {
        if (!offlineReady || offlineRecognizer == null) return "";

        try {
            int numSamples = pcmData.length / 2;
            float[] floatSamples = new float[numSamples];
            for (int i = 0; i < numSamples; i++) {
                short sample = (short) ((pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8));
                floatSamples[i] = sample / 32768.0f;
            }
            return recognizeOffline(floatSamples, sampleRate);
        } catch (Exception e) {
            Log.e(TAG, "Recognize failed", e);
            return "";
        }
    }

    private String recognizeOffline(float[] samples, int sampleRate) {
        if (!offlineReady || offlineRecognizer == null) return "";
        try {
            OfflineStream stream = offlineRecognizer.createStream();
            stream.acceptWaveform(samples, sampleRate);
            offlineRecognizer.decode(stream);
            OfflineRecognizerResult result = offlineRecognizer.getResult(stream);
            String text = result != null ? result.getText() : "";
            if (text != null) text = text.trim();
            return text != null ? text : "";
        } catch (Exception e) {
            Log.e(TAG, "SenseVoice decode failed", e);
            return "";
        }
    }

    // ==================== State Queries ====================

    public boolean isReady() { return offlineReady; }
    public boolean isStreamingReady() { return vadReady && offlineReady; }

    // ==================== Cleanup ====================

    /** 清理舊版下載的模型目錄（v2.7/v3.0 殘留） */
    private void cleanupOldModels() {
        String[] oldDirs = {"sherpa-onnx-models", "sherpa-onnx-sensevoice", "sherpa-onnx-streaming"};
        for (String dirName : oldDirs) {
            File dir = new File(context.getFilesDir(), dirName);
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) f.delete();
                }
                dir.delete();
                Log.i(TAG, "Cleaned up old model dir: " + dirName);
            }
        }
        // Also clean standalone VAD file
        File oldVad = new File(context.getFilesDir(), "silero_vad.onnx");
        if (oldVad.exists()) {
            oldVad.delete();
            Log.i(TAG, "Cleaned up old silero_vad.onnx");
        }
    }

    public void release() {
        if (offlineRecognizer != null) {
            offlineRecognizer.release();
            offlineRecognizer = null;
        }
        if (vad != null) {
            vad.release();
            vad = null;
        }
        offlineReady = false;
        vadReady = false;

        segmentExecutor.shutdown();
        try { segmentExecutor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) {}
    }
}
