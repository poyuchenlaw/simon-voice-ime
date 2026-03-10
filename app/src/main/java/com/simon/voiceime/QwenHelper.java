package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Qwen 本地後處理器 v1.0
 *
 * 透過 Termux Ollama (localhost:11434) 呼叫 Qwen 0.8B 做語音辨識後處理：
 * 1. 加入標點符號
 * 2. 在語句邊界斷句
 * 3. 修正明顯同音錯字
 *
 * 特性：
 * - 5 秒超時，Ollama 不可用時回傳原始文字（graceful fallback）
 * - 溫度 0.1，純機械處理，不改變語意
 * - 自動剝離 Qwen3 的 <think>...</think> 標籤
 * - 模型名稱可透過 SharedPreferences 設定
 */
public class QwenHelper {

    private static final String TAG = "QwenHelper";
    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String PREF_KEY_MODEL = "qwen_model_name";
    private static final String DEFAULT_MODEL = "qwen3:0.6b";

    // Qwen3 thinking tag pattern: <think>...</think>
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile(
            "<think>[\\s\\S]*?</think>\\s*", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT =
            "你是語音辨識後處理器。只做以下三件事，不做其他任何修改：\n" +
            "1. 加入適當的標點符號（逗號、句號、問號、驚嘆號）\n" +
            "2. 在語句邊界處斷句\n" +
            "3. 修正明顯的同音錯字\n" +
            "不要改變原意、不要增減內容、不要潤飾文字。直接輸出處理後的文字，不要加任何解釋。";

    private final OkHttpClient client;
    private final Context context;
    private volatile boolean available = true; // assume available until proven otherwise

    public QwenHelper(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 取得目前設定的模型名稱。
     */
    public String getModelName() {
        SharedPreferences prefs = context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE);
        return prefs.getString(PREF_KEY_MODEL, DEFAULT_MODEL);
    }

    /**
     * 設定模型名稱。
     */
    public void setModelName(String modelName) {
        context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_MODEL, modelName)
                .apply();
    }

    /**
     * 後處理語音辨識結果：加標點、斷句、修正同音錯字。
     *
     * 同步呼叫，必須在背景執行緒使用。
     * 如果 Ollama 不可用或超時，回傳原始文字。
     *
     * @param rawText SenseVoice 辨識的原始文字（無標點）
     * @return 處理後的文字，或失敗時回傳原始文字
     */
    public String preprocess(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return rawText;
        }

        // 短文字（< 4 字）不值得跑 LLM
        if (rawText.trim().length() < 4) {
            return rawText;
        }

        try {
            String model = getModelName();

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("prompt", rawText);
            body.put("system", SYSTEM_PROMPT);
            body.put("stream", false);

            JSONObject options = new JSONObject();
            options.put("temperature", 0.1);
            options.put("num_predict", 512);
            body.put("options", options);

            Request request = new Request.Builder()
                    .url(OLLAMA_URL)
                    .post(RequestBody.create(body.toString(), JSON_TYPE))
                    .build();

            long t0 = System.currentTimeMillis();
            Response response = client.newCall(request).execute();
            long elapsed = System.currentTimeMillis() - t0;

            if (!response.isSuccessful()) {
                Log.w(TAG, "Ollama HTTP " + response.code() + " (" + elapsed + "ms)");
                if (response.body() != null) response.body().close();
                return rawText;
            }

            String respBody = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(respBody);
            String result = json.optString("response", "").trim();

            if (result.isEmpty()) {
                Log.w(TAG, "Ollama returned empty response (" + elapsed + "ms)");
                return rawText;
            }

            // Strip <think>...</think> tags (Qwen3 thinking model)
            result = stripThinkTags(result);

            if (result.isEmpty()) {
                Log.w(TAG, "Ollama response empty after stripping think tags (" + elapsed + "ms)");
                return rawText;
            }

            available = true;
            Log.i(TAG, "Preprocessed in " + elapsed + "ms: '" + rawText + "' -> '" + result + "'");
            return result;

        } catch (IOException e) {
            Log.w(TAG, "Ollama unavailable: " + e.getMessage());
            available = false;
            return rawText;
        } catch (Exception e) {
            Log.e(TAG, "Preprocess error", e);
            return rawText;
        }
    }

    /**
     * 剝離 Qwen3 的 thinking 標籤。
     * 如果回應包含 <think>...</think>，只取 </think> 之後的文字。
     * 如果沒有 think 標籤，回傳原文。
     */
    static String stripThinkTags(String text) {
        if (text == null) return "";

        // Fast path: no think tag
        if (!text.contains("<think>")) {
            return text.trim();
        }

        // Remove all <think>...</think> blocks
        String cleaned = THINK_TAG_PATTERN.matcher(text).replaceAll("");
        return cleaned.trim();
    }

    /**
     * 檢查 Ollama 是否可用（上次呼叫是否成功）。
     * 這是一個 hint，不保證下次呼叫一定成功/失敗。
     */
    public boolean isAvailable() {
        return available;
    }
}
