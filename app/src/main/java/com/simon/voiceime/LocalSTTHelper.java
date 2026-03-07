package com.simon.voiceime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本機端 STT 引擎 — 三層架構 v3.0
 *
 * Layer 1: Silero VAD — 語音活動偵測，自動斷句（~2MB）
 * Layer 2: Streaming Zipformer — 即時串流辨識，邊說邊出字（~45MB）
 * Layer 3: Offline SenseVoice — 高精度最終辨識（~220MB）
 *
 * 流程：
 *  錄音中 → VAD 偵測語音 → Streaming 即時預覽 → VAD 偵測句尾
 *  → SenseVoice 精確辨識該句 → 送伺服器校正 → 下一句
 */
public class LocalSTTHelper {
    private static final String TAG = "LocalSTT";
    private static final int SAMPLE_RATE = 16000;

    // Model directories
    private static final String SENSEVOICE_DIR = "sherpa-onnx-sensevoice";
    private static final String STREAMING_DIR = "sherpa-onnx-streaming";

    // SenseVoice model URLs
    private static final String SENSEVOICE_MODEL_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx";
    private static final String SENSEVOICE_TOKENS_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt";

    // Silero VAD model URL
    private static final String VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    // Streaming Zipformer model URLs (bilingual zh-en, int8)
    private static final String STREAMING_BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/";
    private static final String[] STREAMING_FILES = {
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
    };

    // Notification
    private static final String NOTIF_CHANNEL = "stt_model";
    private static final int NOTIF_ID = 9001;

    // Engines
    private OfflineRecognizer offlineRecognizer;  // SenseVoice (high accuracy)
    private Vad vad;                               // Silero VAD (sentence detection)
    private OnlineRecognizer onlineRecognizer;     // Streaming Zipformer (real-time preview)
    private OnlineStream onlineStream;             // Current streaming session

    // State
    private volatile boolean offlineReady = false;
    private volatile boolean vadReady = false;
    private volatile boolean onlineReady = false;
    private volatile boolean isDownloading = false;

    // SenseVoice per-segment executor (ensures sequential processing)
    private ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingSegments = new AtomicInteger(0);

    private final Context context;
    private NotificationManager notifManager;
    private StatusCallback statusCallback;

    // Hotwords for streaming recognizer (simplified Chinese, matching model vocabulary)
    private static final String HOTWORDS_CONTENT =
            "庭期 5.0\n起诉 5.0\n诉讼 5.0\n判决 5.0\n裁定 5.0\n驳回 5.0\n" +
            "上诉 5.0\n抗告 5.0\n再审 5.0\n债权 5.0\n债务 5.0\n担保 5.0\n" +
            "抵押 5.0\n契约 5.0\n违约 5.0\n赔偿 5.0\n损害赔偿 5.0\n" +
            "消灭时效 5.0\n争执事项 5.0\n不争执事项 5.0\n争点整理 5.0\n" +
            "持分 5.0\n分割 5.0\n继承 5.0\n遗嘱 5.0\n侵权 5.0\n请求权 5.0\n" +
            "假扣押 5.0\n假处分 5.0\n假执行 5.0\n保全 5.0\n释明 5.0\n提存 5.0\n" +
            "律师 5.0\n法官 5.0\n当事人 5.0\n代理人 5.0\n证人 5.0\n书记官 5.0\n" +
            "原告 5.0\n被告 5.0\n声请 5.0\n撤销 5.0\n善意 5.0\n过失 5.0\n故意 5.0\n" +
            "不当得利 5.0\n辩论意旨状 5.0\n诉之声明 5.0\n刑事 5.0\n民事 5.0\n" +
            "答辩状 5.0\n起诉状 5.0\n声请状 5.0\n委任状 5.0\n准备状 5.0\n" +
            "强制执行 5.0\n支付命令 5.0\n调解 5.0\n阅卷 5.0\n� 5.0\n" +
            "法顾案 5.0\n劳雇 5.0\n解雇 5.0\n资遣 5.0\n" +
            "陈柏谕 8.0\n";  // 本所律師名（高權重）

    public interface StatusCallback {
        void onStatus(String message);
    }

    public interface StreamingCallback {
        /** 串流即時預覽（OnlineRecognizer 的 partial result） */
        void onPartialResult(String text);
        /** VAD 偵測到句尾，SenseVoice 完成精確辨識 */
        void onSegmentResult(String text);
    }

    public LocalSTTHelper(Context context) {
        this.context = context;
        setupNotificationChannel();
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    // ==================== Initialization ====================

    /**
     * 背景初始化。按優先順序下載並初始化三個引擎。
     * Phase 1: SenseVoice（必要，~220MB）
     * Phase 2: Silero VAD（重要，~2MB）
     * Phase 3: Streaming Zipformer（可選，~45MB）
     */
    public void init() {
        isDownloading = true;

        // Phase 1: SenseVoice (critical)
        if (!initSenseVoice()) {
            reportStatus("語音引擎初始化失敗");
            isDownloading = false;
            return;
        }

        // Phase 2: Silero VAD (important for streaming)
        try {
            initVad();
        } catch (Throwable t) {
            Log.e(TAG, "VAD 初始化異常", t);
        }

        // Phase 3: Streaming Zipformer — 暫時停用（部分裝置閃退）
        // VAD + SenseVoice 已足夠支撐串流逐句辨識，串流預覽日後再啟用
        // initStreaming();

        // 清理舊版 v2.7 模型目錄（節省空間）
        cleanupOldModels();

        isDownloading = false;

        String status = "本機語音引擎就緒";
        if (vadReady && onlineReady) {
            status += "（串流+VAD+精確辨識）";
        } else if (vadReady) {
            status += "（VAD+精確辨識）";
        } else {
            status += "（精確辨識）";
        }
        showDownloadNotification(status, 100);
        reportStatus(status);

        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        dismissNotification();
    }

    private boolean initSenseVoice() {
        File modelDir = new File(context.getFilesDir(), SENSEVOICE_DIR);
        File modelFile = new File(modelDir, "model.int8.onnx");
        File tokensFile = new File(modelDir, "tokens.txt");

        if (!modelFile.exists() || !tokensFile.exists()) {
            showDownloadNotification("下載語音模型 (1/3): SenseVoice...", 0);
            reportStatus("下載語音模型 (1/3): SenseVoice...");
            modelDir.mkdirs();

            if (!downloadFile(SENSEVOICE_TOKENS_URL, new File(modelDir, "tokens.txt"), "tokens", 0, 5)) {
                return false;
            }
            if (!downloadFile(SENSEVOICE_MODEL_URL, modelFile, "SenseVoice", 5, 70)) {
                new File(modelDir, "tokens.txt").delete();
                return false;
            }
        }

        try {
            reportStatus("載入 SenseVoice 引擎...");
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel(modelFile.getAbsolutePath());
            senseVoice.setLanguage("zh");
            senseVoice.setUseInverseTextNormalization(true);

            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens(tokensFile.getAbsolutePath());
            modelConfig.setNumThreads(4);
            modelConfig.setProvider("cpu");

            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(modelConfig);
            config.setDecodingMethod("greedy_search");

            offlineRecognizer = new OfflineRecognizer(null, config);
            offlineReady = true;
            Log.i(TAG, "SenseVoice 初始化完成");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "SenseVoice 初始化失敗", e);
            return false;
        }
    }

    private void initVad() {
        File vadFile = new File(context.getFilesDir(), "silero_vad.onnx");

        if (!vadFile.exists()) {
            showDownloadNotification("下載語音模型 (2/3): Silero VAD...", 72);
            reportStatus("下載 VAD 模型...");
            if (!downloadFile(VAD_URL, vadFile, "VAD", 70, 75)) {
                Log.w(TAG, "VAD 模型下載失敗，串流功能停用");
                return;
            }
        }

        try {
            SileroVadModelConfig sileroConfig = new SileroVadModelConfig();
            sileroConfig.setModel(vadFile.getAbsolutePath());
            sileroConfig.setThreshold(0.5f);
            sileroConfig.setMinSilenceDuration(0.8f);  // 0.8 秒靜音→斷句（律師口述節奏）
            sileroConfig.setMinSpeechDuration(0.3f);
            sileroConfig.setWindowSize(512);
            sileroConfig.setMaxSpeechDuration(30.0f);

            VadModelConfig vadConfig = new VadModelConfig();
            vadConfig.setSileroVadModelConfig(sileroConfig);
            vadConfig.setSampleRate(SAMPLE_RATE);
            vadConfig.setNumThreads(1);
            vadConfig.setProvider("cpu");

            vad = new Vad(null, vadConfig);
            vadReady = true;
            Log.i(TAG, "Silero VAD 初始化完成");
        } catch (Exception e) {
            Log.e(TAG, "VAD 初始化失敗", e);
        }
    }

    private void initStreaming() {
        File streamDir = new File(context.getFilesDir(), STREAMING_DIR);
        File encoderFile = new File(streamDir, STREAMING_FILES[0]);
        File decoderFile = new File(streamDir, STREAMING_FILES[1]);
        File joinerFile = new File(streamDir, STREAMING_FILES[2]);
        File tokensFile = new File(streamDir, STREAMING_FILES[3]);

        boolean allExist = encoderFile.exists() && decoderFile.exists()
                && joinerFile.exists() && tokensFile.exists();

        if (!allExist) {
            showDownloadNotification("下載語音模型 (3/3): 串流引擎...", 76);
            reportStatus("下載串流辨識引擎...");
            streamDir.mkdirs();

            for (int i = 0; i < STREAMING_FILES.length; i++) {
                File destFile = new File(streamDir, STREAMING_FILES[i]);
                if (destFile.exists()) continue;
                int startPct = 76 + (i * 6);  // 76-100%
                int endPct = 76 + ((i + 1) * 6);
                if (!downloadFile(STREAMING_BASE_URL + STREAMING_FILES[i], destFile,
                        STREAMING_FILES[i], startPct, endPct)) {
                    Log.w(TAG, "串流模型下載失敗: " + STREAMING_FILES[i] + "，即時預覽停用");
                    return;
                }
            }
        }

        // Generate hotwords file
        File hotwordsFile = new File(context.getFilesDir(), "hotwords.txt");
        try (FileWriter writer = new FileWriter(hotwordsFile)) {
            writer.write(HOTWORDS_CONTENT);
        } catch (Exception e) {
            Log.w(TAG, "Hotwords 寫入失敗", e);
        }

        try {
            reportStatus("載入串流辨識引擎...");

            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(encoderFile.getAbsolutePath());
            transducer.setDecoder(decoderFile.getAbsolutePath());
            transducer.setJoiner(joinerFile.getAbsolutePath());

            OnlineModelConfig modelConfig = new OnlineModelConfig();
            modelConfig.setTransducer(transducer);
            modelConfig.setTokens(tokensFile.getAbsolutePath());
            modelConfig.setNumThreads(2);  // 串流用 2 線程（SenseVoice 用 4）
            modelConfig.setProvider("cpu");

            // Endpoint detection: 自動偵測說話停頓
            EndpointRule rule1 = new EndpointRule(false, 2.4f, 0);  // 2.4s 純靜音
            EndpointRule rule2 = new EndpointRule(true, 0.8f, 0);   // 說話後 0.8s 靜音→句尾
            EndpointRule rule3 = new EndpointRule(false, 0, 20.0f);  // 20s 強制斷句
            EndpointConfig endpointConfig = new EndpointConfig();
            endpointConfig.setRule1(rule1);
            endpointConfig.setRule2(rule2);
            endpointConfig.setRule3(rule3);

            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setModelConfig(modelConfig);
            config.setEnableEndpoint(true);
            config.setEndpointConfig(endpointConfig);
            config.setDecodingMethod("greedy_search");

            // Hotwords: 法律術語加權
            if (hotwordsFile.exists()) {
                config.setHotwordsFile(hotwordsFile.getAbsolutePath());
                config.setHotwordsScore(3.0f);
                Log.i(TAG, "已載入法律熱詞 hotwords.txt");
            }

            onlineRecognizer = new OnlineRecognizer(null, config);
            onlineStream = onlineRecognizer.createStream("");
            onlineReady = true;
            Log.i(TAG, "Streaming Zipformer 初始化完成（含法律熱詞）");
        } catch (Exception e) {
            Log.e(TAG, "Streaming 初始化失敗", e);
        }
    }

    // ==================== Streaming Processing ====================

    /**
     * 餵入音訊片段（從錄音執行緒呼叫）。
     * 同時驅動 VAD 斷句 + Streaming 即時預覽 + SenseVoice 精確辨識。
     *
     * 僅在 APPEND 模式使用。REPLACE/SPELL/TRANSLATE 用 recognize() 單次辨識。
     */
    public void feedAudioChunk(float[] samples, StreamingCallback callback) {
        if (!vadReady || !offlineReady) return;

        // 1) Feed to VAD
        vad.acceptWaveform(samples);

        // 2) Feed to streaming recognizer for real-time preview
        if (onlineReady && onlineStream != null) {
            try {
                onlineStream.acceptWaveform(samples, SAMPLE_RATE);
                while (onlineRecognizer.isReady(onlineStream)) {
                    onlineRecognizer.decode(onlineStream);
                }
                OnlineRecognizerResult partialResult = onlineRecognizer.getResult(onlineStream);
                if (partialResult != null) {
                    String partial = partialResult.getText();
                    if (partial != null && !partial.trim().isEmpty()) {
                        callback.onPartialResult(partial.trim());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Streaming decode error", e);
            }
        }

        // 3) Check for completed speech segments from VAD
        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            vad.pop();

            float[] segSamples = segment.getSamples();
            if (segSamples == null || segSamples.length < (int)(SAMPLE_RATE * 0.3f)) {
                continue;  // Skip too-short segments (< 0.3s)
            }

            // Process with SenseVoice on sequential executor (preserves order)
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

            // Reset streaming recognizer for next segment
            if (onlineReady && onlineStream != null) {
                try {
                    onlineRecognizer.reset(onlineStream);
                } catch (Exception e) {
                    Log.w(TAG, "Online reset error", e);
                }
            }
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
        } catch (Exception e) {
            Log.w(TAG, "VAD flush error", e);
        }

        // Reset streaming for next session
        if (onlineReady && onlineStream != null) {
            try {
                onlineRecognizer.reset(onlineStream);
            } catch (Exception e) {
                Log.w(TAG, "Online reset error", e);
            }
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
            Log.e(TAG, "辨識失敗", e);
            return "";
        }
    }

    /**
     * SenseVoice 辨識 float 音訊。
     */
    private String recognizeOffline(float[] samples, int sampleRate) {
        if (!offlineReady || offlineRecognizer == null) return "";
        try {
            OfflineStream stream = offlineRecognizer.createStream();
            stream.acceptWaveform(samples, sampleRate);
            offlineRecognizer.decode(stream);
            OfflineRecognizerResult result = offlineRecognizer.getResult(stream);
            String text = result != null ? result.getText() : "";
            if (text != null) text = text.trim();
            Log.i(TAG, "SenseVoice 辨識: '" + (text != null ? text : "") + "'");
            return text != null ? text : "";
        } catch (Exception e) {
            Log.e(TAG, "SenseVoice 辨識失敗", e);
            return "";
        }
    }

    // ==================== State Queries ====================

    public boolean isReady() { return offlineReady; }
    public boolean isStreamingReady() { return vadReady && offlineReady; }
    public boolean isPreviewReady() { return onlineReady; }
    public boolean isDownloading() { return isDownloading; }
    public int getPendingSegmentCount() { return pendingSegments.get(); }

    // ==================== Download Utilities ====================

    private boolean downloadFile(String urlStr, File dest, String label, int startPct, int endPct) {
        File tmpFile = new File(dest.getAbsolutePath() + ".tmp");
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "下載失敗 HTTP " + conn.getResponseCode() + ": " + urlStr);
                return false;
            }

            long totalSize = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmpFile)) {
                byte[] buf = new byte[65536];
                int n;
                int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (totalSize > 0) {
                        int pct = startPct + (int)((downloaded * (endPct - startPct)) / totalSize);
                        if (pct != lastPct && pct % 2 == 0) {
                            lastPct = pct;
                            String msg = "下載 " + label + "... " + (downloaded / 1024 / 1024) + "MB";
                            showDownloadNotification(msg, pct);
                            reportStatus(msg);
                        }
                    }
                }
            }

            if (tmpFile.renameTo(dest)) {
                Log.i(TAG, "下載完成: " + dest.getName() + " (" + downloaded + " bytes)");
                return true;
            } else {
                Log.e(TAG, "重新命名失敗: " + tmpFile + " → " + dest);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "下載失敗: " + urlStr, e);
            tmpFile.delete();
            return false;
        }
    }

    // ==================== Notification ====================

    private void setupNotificationChannel() {
        notifManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                NOTIF_CHANNEL, "語音模型下載", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("顯示語音模型下載進度");
        notifManager.createNotificationChannel(channel);
    }

    private void showDownloadNotification(String text, int progress) {
        Notification.Builder builder = new Notification.Builder(context, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Simon Voice IME")
                .setContentText(text)
                .setOngoing(progress < 100);

        if (progress >= 0 && progress < 100) {
            builder.setProgress(100, progress, false);
        } else if (progress >= 100) {
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
            builder.setProgress(0, 0, false);
        }
        notifManager.notify(NOTIF_ID, builder.build());
    }

    private void dismissNotification() {
        if (notifManager != null) notifManager.cancel(NOTIF_ID);
    }

    private void reportStatus(String message) {
        Log.i(TAG, message);
        if (statusCallback != null) statusCallback.onStatus(message);
    }

    // ==================== Old Model Cleanup ====================

    /** 清理 v2.7 舊模型目錄（sherpa-onnx-models → 已改用 sherpa-onnx-sensevoice） */
    private void cleanupOldModels() {
        File oldDir = new File(context.getFilesDir(), "sherpa-onnx-models");
        if (oldDir.exists() && oldDir.isDirectory()) {
            File[] files = oldDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            oldDir.delete();
            Log.i(TAG, "已清理舊版模型目錄 sherpa-onnx-models/");
        }
    }

    // ==================== Cleanup ====================

    public void release() {
        if (offlineRecognizer != null) {
            offlineRecognizer.release();
            offlineRecognizer = null;
        }
        if (onlineStream != null) {
            onlineStream.release();
            onlineStream = null;
        }
        if (onlineRecognizer != null) {
            onlineRecognizer.release();
            onlineRecognizer = null;
        }
        if (vad != null) {
            vad.release();
            vad = null;
        }
        offlineReady = false;
        vadReady = false;
        onlineReady = false;

        segmentExecutor.shutdown();
        try { segmentExecutor.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException ignored) {}
    }
}
