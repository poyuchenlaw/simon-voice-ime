package com.simon.voiceime;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 串流上傳助手 v3.3-ws
 *
 * 負責將 VAD 分段辨識的文字逐段上傳到伺服器：
 * - 優先使用 WebSocket（ws://{server}/ws/stream）
 * - WebSocket 不可用時自動降級回 HTTP
 *
 * WebSocket 協議：
 * → {"type": "auth", "password": "xxx"}
 * ← {"type": "auth_ok"}
 * → {"type": "chunk", "text": "辨識文字", "index": 0}
 * ← {"type": "chunk_ok", "index": 0}
 * → {"type": "finalize"}
 * ← {"type": "result", "text": "最終校正文字"}
 *
 * HTTP 降級：
 * - stream-chunk: POST /v1/stream-chunk（fire-and-forget）
 * - stream-finalize: POST /v1/stream-finalize（等回應）
 *
 * 流程：
 * 1. startSession() → 嘗試 WebSocket，失敗則 HTTP 模式
 * 2. sendChunk(text) → WS: send JSON / HTTP: POST
 * 3. finalize(callback) → WS: send finalize + 等 result / HTTP: POST
 * 4. endSession() → 關閉 WebSocket
 */
public class StreamingUploadHelper {

    private static final String TAG = "StreamUpload";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    // WebSocket 連線超時
    private static final long WS_CONNECT_TIMEOUT_MS = 3000;
    // WebSocket finalize 等待結果超時
    private static final long WS_FINALIZE_TIMEOUT_S = 15;

    private final OkHttpClient chunkClient;
    private final OkHttpClient finalizeClient;
    private final OkHttpClient wsClient;

    private String sessionId;
    private final AtomicInteger chunkIndex = new AtomicInteger(0);
    private volatile boolean sessionActive = false;
    private volatile boolean streamingSupported = true; // assume yes until 404

    private String serverUrl;
    private String authPassword;

    // === WebSocket 狀態 ===
    private volatile WebSocket webSocket;
    private volatile boolean wsMode = false;           // 當前 session 是否使用 WS
    private volatile boolean wsAuthenticated = false;   // WS auth 是否已確認
    private volatile boolean wsAvailable = true;        // WS 是否可用（失敗後設 false，之後 session 不再嘗試 WS）

    // finalize 結果同步
    private volatile String finalResultText;
    private volatile String finalErrorText;
    private CountDownLatch resultLatch;

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

        // WebSocket client with ping keep-alive
        wsClient = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // no read timeout for WS
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 開始新的串流 session。
     * 優先嘗試 WebSocket 連線；失敗則自動降級到 HTTP 模式。
     */
    public void startSession(String serverUrl, String authPassword) {
        this.serverUrl = serverUrl;
        this.authPassword = authPassword;
        this.sessionId = UUID.randomUUID().toString();
        this.chunkIndex.set(0);
        this.sessionActive = true;
        this.wsMode = false;
        this.wsAuthenticated = false;
        this.finalResultText = null;
        this.finalErrorText = null;

        // 嘗試 WebSocket 連線
        if (wsAvailable) {
            try {
                String wsUrl = buildWsUrl(serverUrl);
                CountDownLatch authLatch = new CountDownLatch(1);

                Request wsRequest = new Request.Builder().url(wsUrl).build();

                webSocket = wsClient.newWebSocket(wsRequest, new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocket ws, Response response) {
                        Log.i(TAG, "WebSocket connected: " + wsUrl);
                        // 發送 auth
                        try {
                            JSONObject auth = new JSONObject();
                            auth.put("type", "auth");
                            if (authPassword != null && !authPassword.isEmpty()) {
                                auth.put("password", authPassword);
                            }
                            ws.send(auth.toString());
                        } catch (Exception e) {
                            Log.e(TAG, "Error sending WS auth", e);
                            authLatch.countDown();
                        }
                    }

                    @Override
                    public void onMessage(WebSocket ws, String text) {
                        try {
                            JSONObject msg = new JSONObject(text);
                            String type = msg.optString("type", "");

                            switch (type) {
                                case "auth_ok":
                                    wsAuthenticated = true;
                                    Log.i(TAG, "WebSocket auth OK");
                                    authLatch.countDown();
                                    break;

                                case "auth_fail":
                                    Log.w(TAG, "WebSocket auth failed");
                                    wsAuthenticated = false;
                                    authLatch.countDown();
                                    break;

                                case "chunk_ok":
                                    int idx = msg.optInt("index", -1);
                                    Log.d(TAG, "WS chunk_ok: " + idx);
                                    break;

                                case "result":
                                    String resultText = msg.optString("text", "").trim();
                                    Log.i(TAG, "WS result: '" + resultText + "'");
                                    if (!resultText.isEmpty()) {
                                        finalResultText = resultText;
                                    } else {
                                        finalErrorText = "伺服器回傳空文字";
                                    }
                                    if (resultLatch != null) resultLatch.countDown();
                                    break;

                                case "error":
                                    String errMsg = msg.optString("message", "unknown error");
                                    Log.w(TAG, "WS error: " + errMsg);
                                    finalErrorText = errMsg;
                                    if (resultLatch != null) resultLatch.countDown();
                                    break;

                                default:
                                    Log.d(TAG, "WS unknown message type: " + type);
                                    break;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing WS message", e);
                        }
                    }

                    @Override
                    public void onFailure(WebSocket ws, Throwable t, @Nullable Response response) {
                        Log.w(TAG, "WebSocket failure: " + t.getMessage());
                        wsAuthenticated = false;
                        // 如果在 auth 階段失敗，解鎖 authLatch
                        authLatch.countDown();
                        // 如果在 finalize 等待階段失敗，解鎖 resultLatch
                        finalErrorText = "WebSocket 連線中斷: " + t.getMessage();
                        if (resultLatch != null) resultLatch.countDown();
                    }

                    @Override
                    public void onClosing(WebSocket ws, int code, String reason) {
                        Log.i(TAG, "WebSocket closing: " + code + " " + reason);
                        ws.close(1000, null);
                    }

                    @Override
                    public void onClosed(WebSocket ws, int code, String reason) {
                        Log.i(TAG, "WebSocket closed: " + code + " " + reason);
                        // 如果在 finalize 等待階段關閉，解鎖 resultLatch
                        if (resultLatch != null && finalResultText == null && finalErrorText == null) {
                            finalErrorText = "WebSocket 已關閉";
                            resultLatch.countDown();
                        }
                    }
                });

                // 等待 auth 回應，最多 3 秒
                boolean authDone = authLatch.await(WS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

                if (authDone && wsAuthenticated) {
                    wsMode = true;
                    Log.i(TAG, "Session started (WebSocket): " + sessionId);
                } else {
                    // auth 超時或失敗 → 降級 HTTP
                    Log.w(TAG, "WebSocket auth timeout/failed, falling back to HTTP");
                    closeWebSocket();
                    wsMode = false;
                }
            } catch (Exception e) {
                Log.w(TAG, "WebSocket connect error, falling back to HTTP: " + e.getMessage());
                closeWebSocket();
                wsMode = false;
            }
        }

        if (!wsMode) {
            Log.i(TAG, "Session started (HTTP): " + sessionId);
        }
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
     * 當前 session 是否使用 WebSocket 模式。
     */
    public boolean isWebSocketMode() {
        return wsMode;
    }

    /**
     * 傳送一段文字到伺服器（fire-and-forget）。
     * 在 VAD 偵測到語音停頓、SenseVoice 辨識完該段後呼叫。
     */
    public void sendChunk(String chunkText) {
        if (!sessionActive || sessionId == null) return;
        if (chunkText == null || chunkText.trim().isEmpty()) return;

        int idx = chunkIndex.getAndIncrement();

        if (wsMode && webSocket != null) {
            // === WebSocket 模式 ===
            sendChunkWs(chunkText.trim(), idx);
        } else {
            // === HTTP 模式 ===
            sendChunkHttp(chunkText.trim(), idx);
        }
    }

    /**
     * WebSocket 模式發送 chunk（fire-and-forget）。
     */
    private void sendChunkWs(String text, int idx) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "chunk");
            msg.put("text", text);
            msg.put("index", idx);

            boolean sent = webSocket.send(msg.toString());
            if (sent) {
                Log.d(TAG, "WS chunk " + idx + " sent: '" + text + "'");
            } else {
                Log.w(TAG, "WS chunk " + idx + " send failed (queue full/closed), fallback to HTTP");
                // WebSocket 發送失敗 → 這個 chunk 用 HTTP 補發
                sendChunkHttp(text, idx);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending WS chunk " + idx, e);
            sendChunkHttp(text, idx);
        }
    }

    /**
     * HTTP 模式發送 chunk（fire-and-forget）。
     */
    private void sendChunkHttp(String text, int idx) {
        try {
            JSONObject body = new JSONObject();
            body.put("session_id", sessionId);
            body.put("chunk_text", text);
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
                    Log.w(TAG, "HTTP Chunk " + idx + " send failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.code() == 404) {
                        Log.w(TAG, "Server does not support streaming (404)");
                        streamingSupported = false;
                    } else {
                        Log.d(TAG, "HTTP Chunk " + idx + " sent: HTTP " + response.code());
                    }
                    if (response.body() != null) response.body().close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error sending HTTP chunk " + idx, e);
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

        if (wsMode && webSocket != null) {
            // === WebSocket 模式 ===
            finalizeWs(callback, totalChunks);
        } else {
            // === HTTP 模式 ===
            finalizeHttp(callback, totalChunks);
        }
    }

    /**
     * WebSocket 模式 finalize：發送 finalize 訊息，等待 result。
     */
    private void finalizeWs(FinalizeCallback callback, int totalChunks) {
        try {
            // 準備等待 result
            finalResultText = null;
            finalErrorText = null;
            resultLatch = new CountDownLatch(1);

            JSONObject msg = new JSONObject();
            msg.put("type", "finalize");

            boolean sent = webSocket.send(msg.toString());
            if (!sent) {
                Log.w(TAG, "WS finalize send failed, fallback to HTTP");
                finalizeHttp(callback, totalChunks);
                return;
            }

            Log.i(TAG, "WS finalize sent, waiting for result...");

            // 在背景執行緒等待結果，避免阻塞呼叫者
            new Thread(() -> {
                try {
                    boolean gotResult = resultLatch.await(WS_FINALIZE_TIMEOUT_S, TimeUnit.SECONDS);

                    if (gotResult && finalResultText != null) {
                        Log.i(TAG, "WS finalize success: '" + finalResultText + "'");
                        callback.onSuccess(finalResultText);
                    } else if (gotResult && finalErrorText != null) {
                        Log.w(TAG, "WS finalize error: " + finalErrorText);
                        callback.onError(finalErrorText);
                    } else {
                        // 超時
                        Log.w(TAG, "WS finalize timeout after " + WS_FINALIZE_TIMEOUT_S + "s, fallback to HTTP");
                        finalizeHttp(callback, totalChunks);
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "WS finalize interrupted", e);
                    callback.onError("等待結果被中斷");
                }
            }, "ws-finalize-wait").start();

        } catch (Exception e) {
            Log.e(TAG, "Error sending WS finalize", e);
            finalizeHttp(callback, totalChunks);
        }
    }

    /**
     * HTTP 模式 finalize。
     */
    private void finalizeHttp(FinalizeCallback callback, int totalChunks) {
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
                    Log.e(TAG, "HTTP Finalize failed", e);
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
                        Log.e(TAG, "Error parsing HTTP finalize response", e);
                        callback.onError("解析錯誤");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error creating HTTP finalize request", e);
            callback.onError("建立請求失敗");
        }
    }

    /**
     * 結束 session，關閉 WebSocket 連線。
     */
    public void endSession() {
        sessionActive = false;
        closeWebSocket();
        Log.i(TAG, "Session ended: " + sessionId);
    }

    /**
     * 取消當前 session（不呼叫 finalize）。
     */
    public void cancelSession() {
        sessionActive = false;
        closeWebSocket();
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

    /**
     * 重設 WebSocket 可用狀態（例如伺服器升級後可重新嘗試）。
     */
    public void resetWsAvailability() {
        wsAvailable = true;
        Log.i(TAG, "WebSocket availability reset");
    }

    // === Private helpers ===

    /**
     * 將 HTTP URL 轉為 WebSocket URL。
     * http://host:port → ws://host:port/ws/stream
     * https://host:port → wss://host:port/ws/stream
     */
    private String buildWsUrl(String httpUrl) {
        String wsUrl = httpUrl;
        if (wsUrl.startsWith("https://")) {
            wsUrl = "wss://" + wsUrl.substring(8);
        } else if (wsUrl.startsWith("http://")) {
            wsUrl = "ws://" + wsUrl.substring(7);
        }
        // 移除尾部斜線
        if (wsUrl.endsWith("/")) {
            wsUrl = wsUrl.substring(0, wsUrl.length() - 1);
        }
        return wsUrl + "/ws/stream";
    }

    /**
     * 安全關閉 WebSocket 連線。
     */
    private void closeWebSocket() {
        if (webSocket != null) {
            try {
                webSocket.close(1000, "session ended");
            } catch (Exception e) {
                Log.w(TAG, "Error closing WebSocket: " + e.getMessage());
                try {
                    webSocket.cancel();
                } catch (Exception ignored) {}
            }
            webSocket = null;
        }
        wsMode = false;
        wsAuthenticated = false;
    }
}
