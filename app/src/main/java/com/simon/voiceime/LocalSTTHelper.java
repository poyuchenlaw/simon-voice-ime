package com.simon.voiceime;

import android.content.Context;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 本機端 STT 引擎 v3.1 — 模型首次啟動下載版
 *
 * Layer 1: Silero VAD — 語音活動偵測，自動斷句（下載 ~632KB）
 * Layer 2: SenseVoice — 高精度離線辨識（下載 ~228MB）
 *
 * 模型存放在 app filesDir/models/，下載失敗時優雅降級為伺服器辨識。
 */
public class LocalSTTHelper {
    private static final String TAG = "LocalSTT";
    private static final int SAMPLE_RATE = 16000;
    private static final String MODEL_DOWNLOAD_CHANNEL_ID = "model_download";
    private static final int MODEL_DOWNLOAD_NOTIFICATION_ID = 6501;
    private static final int MAX_MODEL_DOWNLOAD_RETRIES = 3;
    private static final int DOWNLOAD_BUFFER_SIZE = 64 * 1024;

    private static final ModelFile SENSEVOICE_MODEL = new ModelFile(
            "models/sensevoice/model.int8.onnx",
            "https://github.com/poyuchenlaw/simon-voice-ime/releases/download/models-v1/model.int8.onnx",
            239233841L,
            "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
            true
    );
    private static final ModelFile SENSEVOICE_TOKENS = new ModelFile(
            "models/sensevoice/tokens.txt",
            "https://github.com/poyuchenlaw/simon-voice-ime/releases/download/models-v1/tokens.txt",
            315894L,
            "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
            false
    );
    private static final ModelFile SILERO_VAD = new ModelFile(
            "models/silero_vad.onnx",
            "https://github.com/poyuchenlaw/simon-voice-ime/releases/download/models-v1/silero_vad.onnx",
            643854L,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            false
    );
    private static final ModelFile[] REQUIRED_MODELS = {
            SENSEVOICE_MODEL,
            SENSEVOICE_TOKENS,
            SILERO_VAD
    };

    // Engines
    private OfflineRecognizer offlineRecognizer;
    private Vad vad;

    // v6.9.1: the AAR's OfflineRecognizer.decode() is NOT synchronized (verified by
    // javap on sherpa-onnx-1.12.28.aar: it calls native decode(JJ)V on the shared ptr
    // with no lock). Segment recognitions run on segmentExecutor while a single-shot
    // recognize() may fire from the stop thread → two threads decode on the ONE shared
    // recognizer concurrently. Guard every createStream/decode/getResult with this lock
    // so they never overlap on the shared native recognizer (defense-in-depth).
    private final Object recognizerLock = new Object();

    // State
    private volatile boolean offlineReady = false;
    private volatile boolean vadReady = false;

    // SenseVoice per-segment executor (ensures sequential processing)
    private ExecutorService segmentExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger pendingSegments = new AtomicInteger(0);
    private float[] lastTailSamples = null;
    private static final int OVERLAP_SAMPLES = 6400;   // 0.4s at 16kHz; preview-only context

    private final Context context;
    private final OkHttpClient modelDownloadClient;

    public interface StreamingCallback {
        /** VAD 偵測到句尾，SenseVoice 完成精確辨識 */
        void onSegmentResult(String text);
    }

    public LocalSTTHelper(Context context) {
        this.context = context;
        this.modelDownloadClient = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(2, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    // ==================== Initialization ====================

    /**
     * 初始化引擎（模型在首次啟動時下載到 filesDir/models/）。
     * 在背景執行緒呼叫。
     */
    public void init() {
        offlineReady = false;
        vadReady = false;

        if (!ensureModels()) {
            Log.w(TAG, "離線模型尚未就緒，on-device STT 暫停；伺服器辨識路徑維持可用");
            cleanupOldModels();
            return;
        }

        File senseVoiceModelFile = modelFile(SENSEVOICE_MODEL);
        File senseVoiceTokensFile = modelFile(SENSEVOICE_TOKENS);
        File sileroVadFile = modelFile(SILERO_VAD);

        // Phase 1: SenseVoice (critical)
        try {
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel(senseVoiceModelFile.getAbsolutePath());
            senseVoice.setLanguage("zh");
            senseVoice.setUseInverseTextNormalization(true);

            OfflineModelConfig modelConfig = new OfflineModelConfig();
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens(senseVoiceTokensFile.getAbsolutePath());
            modelConfig.setNumThreads(4);
            modelConfig.setProvider("cpu");

            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(modelConfig);
            config.setDecodingMethod("greedy_search");

            offlineRecognizer = createOfflineRecognizer(config);
            offlineReady = true;
            Log.i(TAG, "SenseVoice 就緒（從 filesDir 載入）");
        } catch (Throwable t) {
            Log.e(TAG, "SenseVoice 初始化失敗", t);
            return;
        }

        // Phase 2: Silero VAD
        try {
            SileroVadModelConfig sileroConfig = new SileroVadModelConfig();
            sileroConfig.setModel(sileroVadFile.getAbsolutePath());
            sileroConfig.setThreshold(0.5f);
            sileroConfig.setMinSilenceDuration(0.5f);  // 0.5 秒靜音斷句（串流模式需要快速分段）
            sileroConfig.setMinSpeechDuration(0.3f);
            sileroConfig.setWindowSize(512);
            sileroConfig.setMaxSpeechDuration(6.0f);  // v6.5 C5 3.0->6.0 reduce forced mid-word cut.

            VadModelConfig vadConfig = new VadModelConfig();
            vadConfig.setSileroVadModelConfig(sileroConfig);
            vadConfig.setSampleRate(SAMPLE_RATE);
            vadConfig.setNumThreads(1);
            vadConfig.setProvider("cpu");

            vad = createVad(vadConfig);
            vadReady = true;
            Log.i(TAG, "VAD 就緒（從 filesDir 載入）");
        } catch (Throwable t) {
            Log.e(TAG, "VAD 初始化失敗（串流辨識停用）", t);
        }

        // Clean up old downloaded models (v2.7/v3.0 remnants)
        cleanupOldModels();

        Log.i(TAG, "語音引擎就緒" + (vadReady ? "（VAD+精確辨識）" : "（精確辨識）"));
    }

    private boolean ensureModels() {
        createModelDownloadChannel();
        try {
            for (ModelFile model : REQUIRED_MODELS) {
                File finalFile = modelFile(model);
                if (isValidModelFile(finalFile, model)) {
                    continue;
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    Log.w(TAG, "無法刪除損壞模型檔: " + finalFile.getAbsolutePath());
                    return false;
                }
                if (!downloadModel(model, finalFile)) {
                    return false;
                }
            }
            return true;
        } finally {
            cancelModelDownloadNotification();
        }
    }

    private OfflineRecognizer createOfflineRecognizer(OfflineRecognizerConfig config) throws Exception {
        try {
            Constructor<OfflineRecognizer> constructor =
                    OfflineRecognizer.class.getConstructor(OfflineRecognizerConfig.class);
            return constructor.newInstance(config);
        } catch (NoSuchMethodException e) {
            // v6.6: sherpa-onnx 1.12.28 只有 (AssetManager, config) 建構子；傳 null →
            // 建構子 bytecode ifnull 分支走 newFromFile(config)，從絕對檔案路徑載入（已驗證）。
            return new OfflineRecognizer(null, config);
        }
    }

    private Vad createVad(VadModelConfig config) throws Exception {
        try {
            Constructor<Vad> constructor = Vad.class.getConstructor(VadModelConfig.class);
            return constructor.newInstance(config);
        } catch (NoSuchMethodException e) {
            // v6.6: 同理，傳 null → Vad newFromFile(config) 從檔案路徑載入。
            return new Vad(null, config);
        }
    }

    private boolean downloadModel(ModelFile model, File finalFile) {
        File parent = finalFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "無法建立模型目錄: " + parent.getAbsolutePath());
            return false;
        }

        File partFile = new File(finalFile.getAbsolutePath() + ".part");
        for (int attempt = 1; attempt <= MAX_MODEL_DOWNLOAD_RETRIES; attempt++) {
            if (partFile.exists() && !partFile.delete()) {
                Log.w(TAG, "無法刪除暫存模型檔: " + partFile.getAbsolutePath());
                return false;
            }

            try {
                Log.i(TAG, "下載離線模型 " + model.relativePath + " (" + attempt + "/" + MAX_MODEL_DOWNLOAD_RETRIES + ")");
                streamDownloadToPart(model, partFile);
                if (!isValidModelFile(partFile, model)) {
                    Log.w(TAG, "模型下載校驗失敗: " + model.relativePath);
                    if (partFile.exists() && !partFile.delete()) {
                        Log.w(TAG, "無法刪除校驗失敗的暫存檔: " + partFile.getAbsolutePath());
                    }
                    continue;
                }

                if (finalFile.exists() && !finalFile.delete()) {
                    Log.w(TAG, "無法替換既有模型檔: " + finalFile.getAbsolutePath());
                    return false;
                }
                if (!partFile.renameTo(finalFile)) {
                    Log.w(TAG, "模型暫存檔移動失敗: " + partFile.getAbsolutePath());
                    if (partFile.exists()) partFile.delete();
                    return false;
                }
                Log.i(TAG, "模型下載完成: " + finalFile.getAbsolutePath());
                return true;
            } catch (IOException e) {
                Log.w(TAG, "模型下載失敗: " + model.relativePath + " (" + attempt + "/" + MAX_MODEL_DOWNLOAD_RETRIES + ")", e);
                if (partFile.exists() && !partFile.delete()) {
                    Log.w(TAG, "無法刪除失敗暫存檔: " + partFile.getAbsolutePath());
                }
            }
        }
        return false;
    }

    private void streamDownloadToPart(ModelFile model, File partFile) throws IOException {
        Request request = new Request.Builder()
                .url(model.url)
                .header("User-Agent", "SimonVoiceIME/LocalSTT")
                .build();

        try (Response response = modelDownloadClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }

            long expectedBytes = response.body().contentLength() > 0 ? response.body().contentLength() : model.sizeBytes;
            long downloadedBytes = 0L;
            int lastProgress = -1;
            byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];

            try (InputStream input = response.body().byteStream();
                 FileOutputStream output = new FileOutputStream(partFile)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    downloadedBytes += read;
                    if (model.showProgress && expectedBytes > 0) {
                        int progress = (int) Math.min(100, (downloadedBytes * 100L) / expectedBytes);
                        if (progress != lastProgress) {
                            showModelDownloadProgress(progress);
                            lastProgress = progress;
                        }
                    }
                }
                output.getFD().sync();
            }
        }
    }

    private boolean isValidModelFile(File file, ModelFile model) {
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        if (file.length() != model.sizeBytes) {
            Log.w(TAG, "模型檔大小不符: " + file.getAbsolutePath() + " actual=" + file.length() + " expected=" + model.sizeBytes);
            return false;
        }
        try {
            String digest = sha256(file);
            boolean ok = model.sha256.equalsIgnoreCase(digest);
            if (!ok) {
                Log.w(TAG, "模型檔 sha256 不符: " + file.getAbsolutePath());
            }
            return ok;
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.w(TAG, "模型檔 sha256 計算失敗: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[DOWNLOAD_BUFFER_SIZE];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b & 0xFF));
        }
        return hex.toString();
    }

    private File modelFile(ModelFile model) {
        return new File(context.getFilesDir(), model.relativePath);
    }

    private void createModelDownloadChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationChannel channel = new NotificationChannel(
                    MODEL_DOWNLOAD_CHANNEL_ID,
                    "語音模型下載",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        } catch (Throwable t) {
            Log.d(TAG, "模型下載通知 channel 建立失敗，略過通知", t);
        }
    }

    private void showModelDownloadProgress(int progress) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MODEL_DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("下載離線語音模型 " + progress + "%")
                    .setContentText("首次啟動需要下載模型")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, progress, false);
            NotificationManagerCompat.from(context).notify(MODEL_DOWNLOAD_NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            Log.d(TAG, "模型下載通知更新失敗，下載繼續", t);
        }
    }

    private void cancelModelDownloadNotification() {
        try {
            NotificationManagerCompat.from(context).cancel(MODEL_DOWNLOAD_NOTIFICATION_ID);
        } catch (Throwable t) {
            Log.d(TAG, "模型下載通知取消失敗，略過", t);
        }
    }

    private static class ModelFile {
        final String relativePath;
        final String url;
        final long sizeBytes;
        final String sha256;
        final boolean showProgress;

        ModelFile(String relativePath, String url, long sizeBytes, String sha256, boolean showProgress) {
            this.relativePath = relativePath;
            this.url = url;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.showProgress = showProgress;
        }
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

            float[] recogInput;
            if (lastTailSamples != null && lastTailSamples.length > 0) {
                recogInput = new float[lastTailSamples.length + segSamples.length];
                System.arraycopy(lastTailSamples, 0, recogInput, 0, lastTailSamples.length);
                System.arraycopy(segSamples, 0, recogInput, lastTailSamples.length, segSamples.length);
            } else {
                recogInput = segSamples;
            }
            int ov = Math.min(OVERLAP_SAMPLES, segSamples.length);
            lastTailSamples = new float[ov];
            System.arraycopy(segSamples, segSamples.length - ov, lastTailSamples, 0, ov);

            pendingSegments.incrementAndGet();
            final float[] finalSamples = recogInput;
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
     * 等待所有 pending SenseVoice 段落處理完成。
     *
     * <p>v6.9.2: 預設上限自 5s 降到 1.2s，避免停止錄音時的等待主宰整段 stop-time pipeline。
     * 使用者在錄音過程中「已經」看到預覽列即時累積這些分段文字，所以即使少數尾段尚未 decode 完，
     * commit 已顯示的預覽文字也不會丟字；真正的硬保證在 SimonIMEService 的 ~2s 整體 timeout wall。
     */
    public void waitForPendingSegments() {
        waitForPendingSegments(1200);
    }

    /** 等待所有 pending SenseVoice 段落完成，最多 {@code maxWaitMs} 毫秒。 */
    public void waitForPendingSegments(long maxWaitMs) {
        if (pendingSegments.get() == 0) return;
        long deadline = System.currentTimeMillis() + Math.max(0, maxWaitMs);
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
        // v6.9.1: serialize createStream/decode/getResult on the shared recognizer.
        // The native OfflineRecognizer is not thread-safe; without this, a stop-time
        // single-shot can race still-pending segment decodes on segmentExecutor.
        synchronized (recognizerLock) {
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
    }

    // ==================== State Queries ====================

    public boolean isReady() { return offlineReady; }
    public boolean isStreamingReady() { return vadReady && offlineReady; }

    /**
     * 重置串流 VAD 狀態。APPEND 預覽每次錄音開始前呼叫，避免上一段殘留音訊跨 utterance。
     */
    public void resetStreamingState() {
        lastTailSamples = null;
        if (!vadReady || vad == null) return;
        try {
            vad.reset();
        } catch (Throwable t) {
            Log.w(TAG, "VAD reset error", t);
        }
    }

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
