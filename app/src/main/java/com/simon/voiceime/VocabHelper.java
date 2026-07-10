package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 詞彙同步助手 v6.20
 * 與伺服器 /v1/vocab/* 端點同步自訂詞彙，提升語音辨識準確率。
 */
public class VocabHelper {

    private static final String TAG = "VocabHelper";

    private final Context context;
    private final OkHttpClient client;

    // ===== Callbacks =====

    public interface SyncCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface ListCallback {
        void onSuccess(List<VocabItem> items);
        void onError(String message);
    }

    public interface DeleteCallback {
        void onSuccess();
        void onError(String message);
    }

    // ===== Data class =====

    public static class VocabItem {
        public final String word;
        public final String source;

        public VocabItem(String word, String source) {
            this.word = word;
            this.source = source;
        }
    }

    // ===== Constructor =====

    public VocabHelper(Context context) {
        this.context = context.getApplicationContext();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    // ===== Private helpers =====

    private String getServerUrl() {
        SharedPreferences prefs = context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE);
        return prefs.getString("server_url", "http://100.84.86.128:8001");
    }

    private String getAuth() {
        SharedPreferences prefs = context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE);
        return prefs.getString("auth_password", "guangxin_voice_2026");
    }

    private Request.Builder authorizedBuilder(String url) {
        Request.Builder rb = new Request.Builder().url(url);
        String auth = getAuth();
        if (auth != null && !auth.isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + auth);
        }
        return rb;
    }

    // ===== Public API =====

    /**
     * POST /v1/vocab/sync — 同步一個詞彙到伺服器。
     * @param word   要同步的詞彙
     * @param source 來源標籤（例如 "clipboard"、"manual"）
     */
    public void sync(String word, String source, SyncCallback callback) {
        if (word == null || word.trim().isEmpty()) {
            if (callback != null) callback.onError("empty word");
            return;
        }
        String url = getServerUrl() + "/v1/vocab/sync";
        RequestBody body = new FormBody.Builder()
                .add("word", word.trim())
                .add("source", source != null ? source : "manual")
                .build();

        Request request = authorizedBuilder(url).post(body).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[sync] network error: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful()) {
                        Log.i(TAG, "[sync] ok: " + word);
                        if (callback != null) callback.onSuccess();
                    } else {
                        String msg = "HTTP " + response.code();
                        Log.w(TAG, "[sync] " + msg + " for: " + word);
                        if (callback != null) callback.onError(msg);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * GET /v1/vocab/list — 取得伺服器詞彙列表。
     */
    public void list(ListCallback callback) {
        String url = getServerUrl() + "/v1/vocab/list";
        Request request = authorizedBuilder(url).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "[list] network error: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String bodyStr = response.body() != null ? response.body().string() : "{}";
                    if (!response.isSuccessful()) {
                        String msg = "HTTP " + response.code();
                        Log.w(TAG, "[list] " + msg);
                        if (callback != null) callback.onError(msg);
                        return;
                    }
                    List<VocabItem> items = new ArrayList<>();
                    try {
                        JSONObject root = new JSONObject(bodyStr);
                        JSONArray arr = root.optJSONArray("words");
                        if (arr != null) {
                            for (int i = 0; i < arr.length(); i++) {
                                Object elem = arr.get(i);
                                if (elem instanceof JSONObject) {
                                    JSONObject obj = (JSONObject) elem;
                                    items.add(new VocabItem(
                                            obj.optString("word", ""),
                                            obj.optString("source", "")));
                                } else {
                                    items.add(new VocabItem(arr.optString(i, ""), ""));
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "[list] parse error: " + e.getMessage());
                        if (callback != null) callback.onError(e.getMessage());
                        return;
                    }
                    if (callback != null) callback.onSuccess(items);
                } catch (IOException e) {
                    Log.w(TAG, "[list] read error: " + e.getMessage());
                    if (callback != null) callback.onError(e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * DELETE /v1/vocab/{word} — 刪除伺服器單一詞彙。
     */
    public void delete(String word, DeleteCallback callback) {
        if (word == null || word.trim().isEmpty()) {
            if (callback != null) callback.onError("empty word");
            return;
        }
        try {
            String encoded = URLEncoder.encode(word.trim(), StandardCharsets.UTF_8.name());
            String url = getServerUrl() + "/v1/vocab/" + encoded;
            Request request = authorizedBuilder(url).delete().build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "[delete] network error: " + e.getMessage());
                    if (callback != null) callback.onError(e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            Log.i(TAG, "[delete] ok: " + word);
                            if (callback != null) callback.onSuccess();
                        } else {
                            String msg = "HTTP " + response.code();
                            Log.w(TAG, "[delete] " + msg + " for: " + word);
                            if (callback != null) callback.onError(msg);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "[delete] encode error: " + e.getMessage());
            if (callback != null) callback.onError(e.getMessage());
        }
    }
}
