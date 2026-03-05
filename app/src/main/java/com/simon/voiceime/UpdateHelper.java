package com.simon.voiceime;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateHelper {
    private static final String TAG = "UpdateHelper";
    private static final String GITHUB_API =
            "https://api.github.com/repos/poyuchenlaw/simon-voice-ime/releases/latest";

    public interface UpdateCallback {
        void onUpdateAvailable(String version, String downloadUrl, String releaseNotes);
        void onNoUpdate(String currentVersion);
        void onError(String message);
        void onDownloadProgress(int percent);
        void onDownloadComplete(File apkFile);
    }

    private final Context context;
    private final OkHttpClient client;
    private final Handler mainHandler;

    public UpdateHelper(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void checkForUpdate(UpdateCallback callback) {
        Request request = new Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("network error: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String body = response.body().string();
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> callback.onError("GitHub API error: " + response.code()));
                        return;
                    }

                    JSONObject release = new JSONObject(body);
                    String tagName = release.getString("tag_name");
                    String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String currentVersion = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).versionName;
                    String notes = release.optString("body", "");

                    if (isNewer(latestVersion, currentVersion)) {
                        JSONArray assets = release.getJSONArray("assets");
                        String downloadUrl = null;
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            if (asset.getString("name").endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url");
                                break;
                            }
                        }

                        if (downloadUrl != null) {
                            final String url = downloadUrl;
                            final String ver = latestVersion;
                            mainHandler.post(() -> callback.onUpdateAvailable(ver, url, notes));
                        } else {
                            mainHandler.post(() -> callback.onNoUpdate(currentVersion));
                        }
                    } else {
                        mainHandler.post(() -> callback.onNoUpdate(currentVersion));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse error", e);
                    mainHandler.post(() -> callback.onError("parse error: " + e.getMessage()));
                }
            }
        });
    }

    public void downloadAndInstall(String downloadUrl, UpdateCallback callback) {
        Request request = new Request.Builder().url(downloadUrl).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("download failed: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> callback.onError("download error: " + response.code()));
                    return;
                }

                long contentLength = response.body().contentLength();
                InputStream is = response.body().byteStream();
                File apkFile = new File(context.getCacheDir(), "update.apk");

                try (FileOutputStream fos = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int read;
                    int lastPercent = 0;

                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                        downloaded += read;
                        if (contentLength > 0) {
                            int percent = (int) (downloaded * 100 / contentLength);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final int p = percent;
                                mainHandler.post(() -> callback.onDownloadProgress(p));
                            }
                        }
                    }
                }

                mainHandler.post(() -> callback.onDownloadComplete(apkFile));
            }
        });
    }

    public void installApk(File apkFile) {
        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".fileprovider", apkFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    static boolean isNewer(String remote, String local) {
        try {
            String[] r = remote.split("\\.");
            String[] l = local.split("\\.");
            int len = Math.max(r.length, l.length);
            for (int i = 0; i < len; i++) {
                int rv = i < r.length ? Integer.parseInt(r[i]) : 0;
                int lv = i < l.length ? Integer.parseInt(l[i]) : 0;
                if (rv > lv) return true;
                if (rv < lv) return false;
            }
        } catch (NumberFormatException e) {
            return !remote.equals(local);
        }
        return false;
    }
}
