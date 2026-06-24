package com.simon.voiceime.correct;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * On-device CT-Transformer punctuation, resident like the SenseVoice recognizer.
 *
 * <p>Mirrors {@link com.simon.voiceime.LocalSTTHelper}'s download/extract/init conventions:
 * the int8 CT-Transformer model is downloaded to {@code filesDir/models/punct/} on first run
 * (background thread). Until ready, the APPEND fast path falls back to the server.
 *
 * <p>Model: {@code sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8}
 * (k2-fsa release {@code punctuation-models}; extracted {@code model.int8.onnx} ~72MB).
 *
 * <p>Implements {@link OnDeviceCorrector.Punctuator}; the 改字 guard in OnDeviceCorrector ensures
 * this step can only add punctuation, never alter characters.
 */
public final class PunctuationHelper implements OnDeviceCorrector.Punctuator {

    private static final String TAG = "PunctHelper";
    private static final String CHANNEL_ID = "model_download";
    private static final int NOTIF_ID = 6502;
    private static final int MAX_RETRIES = 3;
    private static final int BUF = 64 * 1024;

    // int8 CT-Transformer punctuation model (~72MB extracted). Mirror the IME's own release host.
    private static final String MODEL_REL = "models/punct/model.int8.onnx";
    private static final String MODEL_URL =
            "https://github.com/poyuchenlaw/simon-voice-ime/releases/download/models-v1/punct-model.int8.onnx";
    // size/sha are verification metadata; 0 / "" => skip strict check (set once known-good).
    private static final long MODEL_SIZE = 0L;
    private static final String MODEL_SHA256 = "";

    private final Context context;
    private final OkHttpClient client;
    private volatile OfflinePunctuation punctuation;
    private volatile boolean ready = false;

    public PunctuationHelper(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(2, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public boolean isReady() {
        return ready;
    }

    /** Initialise on a background thread (downloads model on first run). */
    public void init() {
        ready = false;
        File modelFile = new File(context.getFilesDir(), MODEL_REL);
        try {
            if (!isValid(modelFile)) {
                createChannel();
                if (modelFile.exists() && !modelFile.delete()) {
                    Log.w(TAG, "無法刪除損壞標點模型: " + modelFile.getAbsolutePath());
                    return;
                }
                if (!download(modelFile)) {
                    Log.w(TAG, "標點模型下載失敗，APPEND 標點退回伺服器");
                    return;
                }
            }
        } finally {
            cancelChannel();
        }

        try {
            OfflinePunctuationModelConfig modelConfig = new OfflinePunctuationModelConfig();
            modelConfig.setCtTransformer(modelFile.getAbsolutePath());
            modelConfig.setNumThreads(2);
            modelConfig.setProvider("cpu");
            modelConfig.setDebug(false);

            OfflinePunctuationConfig config = new OfflinePunctuationConfig(modelConfig);
            punctuation = createPunctuation(config);
            ready = true;
            Log.i(TAG, "CT-Transformer 標點就緒（從 filesDir 載入）");
        } catch (Throwable t) {
            Log.e(TAG, "標點模型初始化失敗，APPEND 標點退回伺服器", t);
            ready = false;
        }
    }

    private OfflinePunctuation createPunctuation(OfflinePunctuationConfig config) throws Exception {
        try {
            Constructor<OfflinePunctuation> c =
                    OfflinePunctuation.class.getConstructor(OfflinePunctuationConfig.class);
            return c.newInstance(config);
        } catch (NoSuchMethodException e) {
            // sherpa-onnx 1.12.28: (AssetManager, config); passing null → newFromFile(config).
            // Same trick LocalSTTHelper uses for OfflineRecognizer/Vad.
            return new OfflinePunctuation(null, config);
        }
    }

    @Override
    public String addPunctuation(String text) {
        OfflinePunctuation p = punctuation;
        if (!ready || p == null || text == null || text.isEmpty()) return null;
        try {
            return p.addPunctuation(text);
        } catch (Throwable t) {
            Log.w(TAG, "addPunctuation 失敗", t);
            return null;
        }
    }

    public void release() {
        ready = false;
        OfflinePunctuation p = punctuation;
        punctuation = null;
        if (p != null) {
            try { p.release(); } catch (Throwable ignored) {}
        }
    }

    // ==================== Download / verify (mirrors LocalSTTHelper) ====================

    private boolean isValid(File file) {
        if (!file.exists() || !file.isFile() || file.length() == 0) return false;
        if (MODEL_SIZE > 0 && file.length() != MODEL_SIZE) {
            Log.w(TAG, "標點模型大小不符 actual=" + file.length() + " expected=" + MODEL_SIZE);
            return false;
        }
        if (MODEL_SHA256 != null && !MODEL_SHA256.isEmpty()) {
            try {
                return MODEL_SHA256.equalsIgnoreCase(sha256(file));
            } catch (IOException | NoSuchAlgorithmException e) {
                return false;
            }
        }
        return true;
    }

    private boolean download(File finalFile) {
        File parent = finalFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "無法建立標點模型目錄: " + parent.getAbsolutePath());
            return false;
        }
        File part = new File(finalFile.getAbsolutePath() + ".part");
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (part.exists() && !part.delete()) return false;
            try {
                Request req = new Request.Builder()
                        .url(MODEL_URL)
                        .header("User-Agent", "SimonVoiceIME/Punct")
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new IOException("HTTP " + resp.code());
                    }
                    long expected = resp.body().contentLength();
                    long got = 0;
                    int lastPct = -1;
                    byte[] buf = new byte[BUF];
                    try (InputStream in = resp.body().byteStream();
                         FileOutputStream out = new FileOutputStream(part)) {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            got += n;
                            if (expected > 0) {
                                int pct = (int) Math.min(100, got * 100L / expected);
                                if (pct != lastPct) { showProgress(pct); lastPct = pct; }
                            }
                        }
                        out.getFD().sync();
                    }
                }
                if (!isValid(part)) {
                    Log.w(TAG, "標點模型校驗失敗");
                    part.delete();
                    continue;
                }
                if (finalFile.exists() && !finalFile.delete()) return false;
                if (!part.renameTo(finalFile)) { part.delete(); return false; }
                Log.i(TAG, "標點模型下載完成: " + finalFile.getAbsolutePath());
                return true;
            } catch (IOException e) {
                Log.w(TAG, "標點模型下載失敗 (" + attempt + "/" + MAX_RETRIES + ")", e);
                if (part.exists()) part.delete();
            }
        }
        return false;
    }

    private String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUF];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(String.format("%02x", b & 0xFF));
        return hex.toString();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "語音模型下載", NotificationManager.IMPORTANCE_LOW);
            NotificationManager mgr = context.getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.d(TAG, "標點下載通知 channel 建立失敗，略過", t);
        }
    }

    private void showProgress(int pct) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("下載標點模型 " + pct + "%")
                    .setContentText("首次啟動需要下載標點模型")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, pct, false);
            NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            Log.d(TAG, "標點下載通知更新失敗", t);
        }
    }

    private void cancelChannel() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID);
        } catch (Throwable ignored) {}
    }
}
