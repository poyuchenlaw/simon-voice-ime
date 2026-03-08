package com.simon.voiceime;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 串流上傳助手 v3.3
 *
 * 負責將 VAD 分段辨識的文字逐段上傳到伺服器：
 * - stream-chunk: fire-and-forget，每段 VAD 偵測到的文字
 * - stream-finalize: 等待回應，取得 LLM 語義校正後的最終文字
 *
 * 流程：
 * 1. startSession() → 產生 session_id
 * 2. sendChunk(text) → POST /v1/stream-chunk（不等回應）
 * 3. finalize(callback) → POST /v1/stream-finalize（等回應）
 */
public class StreamingUploadHelper {

    private static final String TAG = "StreamUpload";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient chunkClient;
    private final OkHttpClient finalizeClient;

    private String sessionId;
    private final AtomicInteger chunkIndex = new AtomicInteger(0);
    private volatile boolean sessionActive = false;
    private volatile boolean streamingSupported = true; // assume yes until 404

    private String serverUrl;
    private String authPassword;

    public interface FinalizeCallback {
        void onSuccess(String finalText);
        void onError(String error);
    }

    public StreamingUploadHelper() {
        // chunk: short timeout, fire-and-forget
        chunkClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build();

        // finalize: longer timeout for LLM processing
        finalizeClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 開始新的串流 session。
     */
    public void startSession(String serverUrl, String authPassword) {
        this.serverUrl = serverUrl;
        this.authPassword = authPassword;
        this.sessionId = UUID.randomUUID().toString();
        this.chunkIndex.set(0);
        this.sessionActive = true;
        Log.i(TAG, "Session started: " + sessionId);
    }

    /**
     * 是否有活躍的 session。
     */
    public boolean isSessionActive() {
        return sessionActive;
    }

    /**
     * 串流上傳是否受伺服器支援（非 404）。
     */
    public boolean isStreamingSupported() {
        return streamingSupported;
    }

    /**
     * 傳送一段文字到伺服器（fire-and-forget）。
     * 在 VAD 偵測到語音停頓、SenseVoice 辨識完該段後呼叫。
     */
    public void sendChunk(String chunkText) {
        if (!sessionActive || sessionId == null) return;
        if (chunkText == null || chunkText.trim().isEmpty()) return;

        int idx = chunkIndex.getAndIncrement();

        try {
            JSONObject body = new JSONObject();
            body.put("session_id", sessionId);
            body.put("chunk_text", chunkText.trim());
            body.put("chunk_index", idx);
            if (authPassword != null && !authPassword.isEmpty()) {
                body.put("password", authPassword);
            }

            Request.Builder reqBuilder = new Request.Builder()
                    .url(serverUrl + "/v1/stream-chunk")
                    .post(RequestBody.create(body.toString(), JSON_TYPE));

            if (authPassword != null && !authPassword.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + authPassword);
            }

            chunkClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "Chunk " + idx + " send failed: " + e.getMessage());
                    // Don't set streamingSupported=false on network errors
                    // Only on 404 (endpoint not found)
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.code() == 404) {
                        Log.w(TAG, "Server does not support streaming (404)");
                        streamingSupported = false;
                    } else {
                        Log.d(TAG, "Chunk " + idx + " sent: HTTP " + response.code());
                    }
                    if (response.body() != null) response.body().close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error sending chunk " + idx, e);
        }
    }

    /**
     * 結束 session，請求伺服器做 LLM 語義校正並回傳最終文字。
     * 這個呼叫會等待回應。
     */
    public void finalize(FinalizeCallback callback) {
        if (!sessionActive || sessionId == null) {
            callback.onError("No active session");
            return;
        }

        sessionActive = false;
        int totalChunks = chunkIndex.get();

        if (totalChunks == 0) {
            callback.onError("No chunks sent");
            return;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("session_id", sessionId);
            body.put("total_chunks", totalChunks);
            if (authPassword != null && !authPassword.isEmpty()) {
                body.put("password", authPassword);
            }

            Request.Builder reqBuilder = new Request.Builder()
                    .url(serverUrl + "/v1/stream-finalize")
                    .post(RequestBody.create(body.toString(), JSON_TYPE));

            if (authPassword != null && !authPassword.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + authPassword);
            }

            finalizeClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Finalize failed", e);
                    callback.onError("連線失敗: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        String respBody = response.body() != null ? response.body().string() : "";

                        if (response.code() == 404) {
                            streamingSupported = false;
                            callback.onError("STREAMING_NOT_SUPPORTED");
                            return;
                        }

                        if (!response.isSuccessful()) {
                            callback.onError("伺服器錯誤: " + response.code());
                            return;
                        }

                        JSONObject json = new JSONObject(respBody);
                        String finalText = json.optString("text", "").trim();
                        if (!finalText.isEmpty()) {
                            callback.onSuccess(finalText);
                        } else {
                            callback.onError("伺服器回傳空文字");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing finalize response", e);
                        callback.onError("解析錯誤");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating finalize request", e);
            callback.onError("建立請求失敗");
        }
    }

    /**
     * 取消當前 session（不呼叫 finalize）。
     */
    public void cancelSession() {
        sessionActive = false;
        Log.i(TAG, "Session cancelled: " + sessionId);
    }

    /**
     * 取得當前 session 已傳送的 chunk 數。
     */
    public int getChunkCount() {
        return chunkIndex.get();
    }

    /**
     * 取得當前 session ID。
     */
    public String getSessionId() {
        return sessionId;
    }
}
