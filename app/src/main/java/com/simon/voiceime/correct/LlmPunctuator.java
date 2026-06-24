package com.simon.voiceime.correct;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * On-device LLM punctuator — the PRIMARY (smart, context-aware) step-5 {@link OnDeviceCorrector.Punctuator}.
 *
 * <p>Backed by a small (~1.5B) GGUF Q4_K_M model running locally via llama.cpp (JNI native lib
 * {@code libsmpunctllm.so}). Reads the user's 前文 (preceding typed text) as context so that
 * punctuation at the junction with already-typed text is correct, and inserts semantically-correct
 * punctuation into the newly-dictated text.
 *
 * <p><b>Cage.</b> This punctuator is CAGED by the 改字 GUARD in {@link OnDeviceCorrector}: the prompt
 * instructs the model to ONLY insert punctuation and never change/add/remove a character, but the
 * guarantee does NOT rely on the model obeying — after the model runs, the guard strips punctuation
 * and compares char-by-char to the input; if a single non-punctuation char changed, the output is
 * DISCARDED. KEY consequence: the model physically cannot introduce simplified characters or alter
 * legal terms, regardless of base model, because any such change is rejected.
 *
 * <p><b>Graceful degradation.</b> {@link #addPunctuation} returns {@code null} whenever the model is
 * not downloaded/ready, on any error, or when the per-call latency budget is exceeded. The engine
 * ({@link OnDeviceCorrectionEngine}) treats {@code null} as "fall back" and tries the CT-Transformer
 * tagger next, then deterministic (no punctuation). The complete v6.9 always has smart punctuation
 * available with a fast floor underneath.
 *
 * <p>§37: runs entirely on-device. No network for inference; the only network use is the one-time
 * model download (Wi-Fi), mirroring {@link com.simon.voiceime.LocalSTTHelper} / {@link PunctuationHelper}.
 */
public final class LlmPunctuator implements OnDeviceCorrector.Punctuator {

    private static final String TAG = "LlmPunct";
    private static final String CHANNEL_ID = "model_download";
    private static final int NOTIF_ID = 6503;
    private static final int MAX_RETRIES = 3;
    private static final int BUF = 64 * 1024;

    // Bounded 前文 window: enough for junction context, small enough to stay fast.
    private static final int CONTEXT_CHARS = 60;
    // Output cap: punctuation never grows token count much beyond the input.
    private static final int MAX_OUTPUT_TOKENS = 96;
    // Per-call latency budget; over budget => return null => tagger fallback.
    private static final long LATENCY_BUDGET_MS = 2500L;
    private static final int NUM_THREADS = 4;

    // Qwen2.5-1.5B-Instruct Q4_K_M GGUF (converted from the local ~/models/Qwen2.5-1.5B-Instruct
    // safetensors on GB10; llama.cpp convert_hf_to_gguf -> llama-quantize Q4_K_M). Hosted on the
    // IME's own release. Metadata below is REAL for the produced artifact; the upload itself is the
    // orchestrator's final step (see report — model staged, not yet uploaded).
    private static final String MODEL_REL = "models/llm/qwen2.5-1.5b-instruct-q4_k_m.gguf";
    private static final String MODEL_URL =
            "https://github.com/poyuchenlaw/simon-voice-ime/releases/download/models-v1/qwen2.5-1.5b-instruct-q4_k_m.gguf";
    private static final long MODEL_SIZE = 986048608L; // 986,048,608 bytes (~940 MiB)
    private static final String MODEL_SHA256 =
            "2d5d69eb34f5de205d78d722dc66c3be9d8a499d7a47b8f08930c2642edc2ad2";

    private static volatile boolean nativeAvailable = false;
    static {
        try {
            System.loadLibrary("smpunctllm");
            nativeAvailable = true;
        } catch (Throwable t) {
            Log.w(TAG, "libsmpunctllm 載入失敗，LLM 標點停用（退 CT-Transformer）", t);
            nativeAvailable = false;
        }
    }

    private final Context context;
    private final OkHttpClient client;
    private volatile boolean ready = false;

    public LlmPunctuator(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.MINUTES)
                .writeTimeout(2, TimeUnit.MINUTES)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    public boolean isReady() {
        return ready && nativeAvailable;
    }

    /** Initialise on a background thread (downloads ~1GB model on first run, then loads it). */
    public void init() {
        ready = false;
        if (!nativeAvailable) return;
        File modelFile = new File(context.getFilesDir(), MODEL_REL);
        try {
            if (!isValid(modelFile)) {
                createChannel();
                if (modelFile.exists() && !modelFile.delete()) {
                    Log.w(TAG, "無法刪除損壞 LLM 模型: " + modelFile.getAbsolutePath());
                    return;
                }
                if (!download(modelFile)) {
                    Log.w(TAG, "LLM 標點模型下載失敗，退回 CT-Transformer 標點");
                    return;
                }
            }
        } finally {
            cancelChannel();
        }
        try {
            if (nativeLoad(modelFile.getAbsolutePath(), NUM_THREADS)) {
                ready = true;
                Log.i(TAG, "LLM 標點就緒（端上 Qwen2.5-1.5B Q4_K_M）");
            } else {
                Log.w(TAG, "LLM 模型載入失敗，退回 CT-Transformer 標點");
            }
        } catch (Throwable t) {
            Log.e(TAG, "LLM 模型載入拋例外，退回 CT-Transformer 標點", t);
            ready = false;
        }
    }

    @Override
    public String addPunctuation(String text) {
        return addPunctuation(text, null);
    }

    @Override
    public String addPunctuation(String text, String precedingContext) {
        if (!isReady() || text == null || text.isEmpty()) return null;
        try {
            String prompt = buildPrompt(text, precedingContext);
            long t0 = System.currentTimeMillis();
            String raw = nativeComplete(prompt, MAX_OUTPUT_TOKENS, NUM_THREADS);
            long ms = System.currentTimeMillis() - t0;
            if (ms > LATENCY_BUDGET_MS) {
                Log.i(TAG, "LLM 標點超出延遲預算 " + ms + "ms，本次退 CT-Transformer");
                return null;
            }
            if (raw == null || raw.isEmpty()) return null;
            String out = postProcess(raw);
            // Defensive: empty after cleanup => fall back. The 改字 guard downstream is the real
            // safety net; this just avoids handing it junk.
            if (out.isEmpty()) return null;
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "LLM 標點推理失敗，退 CT-Transformer", t);
            return null;
        }
    }

    public void release() {
        ready = false;
        if (nativeAvailable) {
            try { nativeFree(); } catch (Throwable ignored) {}
        }
    }

    // ==================== Prompt building ====================

    /**
     * Qwen2.5 ChatML prompt: a system message stating the punctuation-only contract, a bounded 前文
     * window for junction context, then the sentence to punctuate. The model is asked to output ONLY
     * the punctuated sentence (no explanation, no 前文 echo). "thinking" is off (Qwen2.5-Instruct is a
     * non-reasoning model and the native layer enables no thinking mode).
     */
    private static String buildPrompt(String text, String precedingContext) {
        String ctx = boundedContext(precedingContext);
        StringBuilder user = new StringBuilder();
        if (!ctx.isEmpty()) {
            user.append("前文（已輸入，僅供參考，不要重複輸出）：").append(ctx).append('\n');
        }
        user.append("句子：").append(text);

        return "<|im_start|>system\n"
                + "你是中文標點符號工具。在使用者提供的「句子」中插入適當的標點符號（，。？！、；：）。"
                + "鐵則：不可改變、增加或刪除任何一個文字，只能插入標點符號。"
                + "直接輸出加好標點的句子本身，不要解釋、不要重複前文、不要加引號。"
                + "<|im_end|>\n"
                + "<|im_start|>user\n" + user + "<|im_end|>\n"
                + "<|im_start|>assistant\n";
    }

    private static String boundedContext(String ctx) {
        if (ctx == null) return "";
        String c = ctx.trim();
        if (c.length() > CONTEXT_CHARS) {
            c = c.substring(c.length() - CONTEXT_CHARS);
        }
        return c;
    }

    /** Trim whitespace and any stray ChatML markers / surrounding quotes the model may emit. */
    private static String postProcess(String raw) {
        String s = raw.trim();
        int im = s.indexOf("<|im_end|>");
        if (im >= 0) s = s.substring(0, im);
        im = s.indexOf("<|im_start|>");
        if (im >= 0) s = s.substring(0, im);
        s = s.trim();
        // Strip a single pair of wrapping quotes if present.
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '「' && b == '」') || (a == '"' && b == '"')
                    || (a == '“' && b == '”') || (a == '『' && b == '』')) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    // ==================== Native ====================

    private native boolean nativeLoad(String modelPath, int nThreads);
    private native String nativeComplete(String prompt, int maxTokens, int nThreads);
    private native void nativeFree();

    // ==================== Download / verify (mirrors PunctuationHelper) ====================

    private boolean isValid(File file) {
        if (!file.exists() || !file.isFile() || file.length() == 0) return false;
        if (MODEL_SIZE > 0 && file.length() != MODEL_SIZE) {
            Log.w(TAG, "LLM 模型大小不符 actual=" + file.length() + " expected=" + MODEL_SIZE);
            return false;
        }
        if (MODEL_SHA256 != null && !MODEL_SHA256.isEmpty()
                && !MODEL_SHA256.startsWith("PLACEHOLDER")) {
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
            Log.w(TAG, "無法建立 LLM 模型目錄: " + parent.getAbsolutePath());
            return false;
        }
        File part = new File(finalFile.getAbsolutePath() + ".part");
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            if (part.exists() && !part.delete()) return false;
            try {
                Request req = new Request.Builder()
                        .url(MODEL_URL)
                        .header("User-Agent", "SimonVoiceIME/LlmPunct")
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
                    Log.w(TAG, "LLM 模型校驗失敗");
                    part.delete();
                    continue;
                }
                if (finalFile.exists() && !finalFile.delete()) return false;
                if (!part.renameTo(finalFile)) { part.delete(); return false; }
                Log.i(TAG, "LLM 模型下載完成: " + finalFile.getAbsolutePath());
                return true;
            } catch (IOException e) {
                Log.w(TAG, "LLM 模型下載失敗 (" + attempt + "/" + MAX_RETRIES + ")", e);
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
            Log.d(TAG, "LLM 下載通知 channel 建立失敗，略過", t);
        }
    }

    private void showProgress(int pct) {
        try {
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("下載智慧標點模型 " + pct + "%")
                    .setContentText("首次啟動需下載一次（約 1GB，建議 Wi-Fi）")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, pct, false);
            NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build());
        } catch (Throwable t) {
            Log.d(TAG, "LLM 下載通知更新失敗", t);
        }
    }

    private void cancelChannel() {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIF_ID);
        } catch (Throwable ignored) {}
    }
}
