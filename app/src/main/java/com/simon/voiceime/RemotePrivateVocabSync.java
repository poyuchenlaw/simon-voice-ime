package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded, asynchronous private-vocabulary cache; payload contents are never logged. */
final class RemotePrivateVocabSync {
    private static final String TAG = "RemotePrivateVocab";
    private static final int MAX_ENTRIES = 2000, MAX_BYTES = 512 * 1024;
    private static final AtomicInteger generation = new AtomicInteger();
    private RemotePrivateVocabSync() {}
    static void refreshOnce(Context context, ZhuyinWordIndex index) {
        final int requestGeneration = generation.get();
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_vocab_enabled", true) || prefs.getBoolean("private_vocab_refresh_started", false)) return;
        prefs.edit().putBoolean("private_vocab_refresh_started", true).apply();
        String base = prefs.getString("server_url", "http://100.84.86.128:8001");
        Request request = new Request.Builder().url(base + "/v1/ime/private-phrases")
                .header("Authorization", AuthConfig.authorizationHeader(AuthConfig.password(prefs))).get().build();
        new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
                .newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(Call call, java.io.IOException error) { Log.w(TAG, "private refresh unavailable"); }
                    @Override public void onResponse(Call call, Response response) {
                        try {
                            if (requestGeneration != generation.get()) return;
                            if (!response.isSuccessful() || response.body() == null) return;
                            String body = response.body().string();
                            if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return;
                            JSONObject root = new JSONObject(body); JSONArray entries = root.optJSONArray("entries");
                            if (entries == null || entries.length() > MAX_ENTRIES) return;
                            StringBuilder out = new StringBuilder(); HashSet<String> unique = new HashSet<>();
                            for (int i = 0; i < entries.length(); i++) {
                                JSONObject item = entries.optJSONObject(i); if (item == null) return;
                                String word = item.optString("word", ""), reading = item.optString("reading", "");
                                String key = keyFor(reading);
                                if (word.isEmpty() || reading.isEmpty() || key.isEmpty() || !unique.add(word + "\t" + reading)) continue;
                                String line = key + "\t" + word + "\t" + reading + "\n";
                                if (out.length() + line.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) return;
                                out.append(line);
                            }
                            if (requestGeneration != generation.get()) return;
                            File target = new File(app.getFilesDir(), "zhuyin_remote_private.tsv");
                            File temp = new File(app.getFilesDir(), "zhuyin_remote_private.tsv.tmp");
                            try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
                            if (requestGeneration != generation.get() || !temp.renameTo(target)) { temp.delete(); return; }
                            index.reloadRemote();
                        } catch (Exception ignored) { Log.w(TAG, "private refresh rejected"); }
                        finally { response.close(); }
                    }
                });
    }
    /** Invalidates any in-flight response before it can write private vocabulary. */
    static void cancelForProtectedField() { generation.incrementAndGet(); }
    private static String keyFor(String reading) {
        String symbols = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ";
        StringBuilder key = new StringBuilder();
        for (String syllable : reading.split("\\s+")) for (int i=0;i<syllable.length();i++) if (symbols.indexOf(syllable.charAt(i))>=0) { key.append(syllable.charAt(i)); break; }
        return key.toString();
    }
}
