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
import java.util.concurrent.TimeUnit;

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
        void onUpdateAvailable(String version, String downloadUrl, String manifestUrl,
                               String expectedFullSha256, String releaseNotes);
        void onNoUpdate(String currentVersion);
        void onError(String message);
        void onDownloadProgress(int percent);
        void onDownloadComplete(File apkFile);
        default void onDownloadMode(String mode, long bytes) { }
    }

    private final Context context;
    private final OkHttpClient client;
    private final Handler mainHandler;

    public UpdateHelper(Context context) {
        this.context = context;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
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
                        String manifestUrl = null;
                        String fullSha256 = null;
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String assetName = asset.getString("name");
                            if (assetName.equals("update-manifest.json")) {
                                manifestUrl = asset.getString("browser_download_url");
                            } else if (assetName.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url");
                                fullSha256 = asset.optString("digest", "");
                                if (fullSha256.startsWith("sha256:")) {
                                    fullSha256 = fullSha256.substring("sha256:".length());
                                }
                            }
                        }

                        if (downloadUrl != null) {
                            final String url = downloadUrl;
                            final String manifest = manifestUrl;
                            final String fullHash = fullSha256;
                            final String ver = latestVersion;
                            mainHandler.post(() -> callback.onUpdateAvailable(
                                    ver, url, manifest, fullHash, notes));
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

    public void downloadAndInstall(String downloadUrl, String manifestUrl,
                                   String expectedFullSha256, UpdateCallback callback) {
        client.newCall(new Request.Builder().url(downloadUrl).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("download failed: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                File apkFile = new File(context.getCacheDir(), "update.apk");
                if (!PatchUpdateSupport.isAvailable()) {
                    final File fullDownloadFile = apkFile;
                    try {
                        if (!response.isSuccessful() || response.body() == null) {
                            throw new IOException("download error: " + response.code());
                        }
                        copyResponse(response.body().byteStream(), fullDownloadFile,
                                response.body().contentLength(), callback);
                        mainHandler.post(() -> callback.onDownloadComplete(fullDownloadFile));
                    } catch (Exception e) {
                        fullDownloadFile.delete();
                        mainHandler.post(() -> callback.onError("download failed: " + e.getMessage()));
                    } finally {
                        response.close();
                    }
                    return;
                }
                response.close();
                try {
                    UpdateManifest manifest = fetchManifest(manifestUrl);
                    int installedCode = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0).getLongVersionCode() > Integer.MAX_VALUE
                            ? -1 : (int) context.getPackageManager()
                                    .getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
                    UpdateManifest.Patch patch = manifest == null
                            || manifest.versionCode <= installedCode
                            || !isSha256(expectedFullSha256)
                            || !manifest.fullApkSha256.equalsIgnoreCase(expectedFullSha256) ? null
                            : manifest.findPatch(installedCode,
                                    FileHash.sha256(new File(context.getPackageCodePath())));
                    if (patch != null && patch.size < manifest.fullApkSize) {
                        String patchUrl = manifestUrl.substring(0,
                                manifestUrl.lastIndexOf('/') + 1) + patch.name;
                        File patchFile = new File(context.getCacheDir(), "update.patch");
                        File candidate = new File(context.getCacheDir(), "update-patched.apk");
                        longName(callback, "差異更新", patch.size);
                        if (downloadToFile(patchUrl, patchFile, callback)
                                && DeltaPatchRunner.apply(
                                        new File(context.getPackageCodePath()), patch.fromSha256,
                                        patchFile, patch.sha256, candidate,
                                        manifest.fullApkSha256, new File(context.getCacheDir(),
                                                "patch-uncompress.tmp"))
                                && FileHash.matches(candidate, expectedFullSha256)) {
                            apkFile = candidate;
                            patchFile.delete();
                            mainHandler.post(() -> callback.onDownloadComplete(candidate));
                            return;
                        }
                        patchFile.delete();
                        candidate.delete();
                    }
                } catch (Exception patchFailure) {
                    Log.w(TAG, "Delta update unavailable; falling back to full APK", patchFailure);
                }
                downloadFullApk(downloadUrl, expectedFullSha256, apkFile, callback);
            }
        });
    }

    private UpdateManifest fetchManifest(String manifestUrl) throws Exception {
        if (manifestUrl == null || manifestUrl.isEmpty()) return null;
        try (Response response = client.newCall(new Request.Builder().url(manifestUrl).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return UpdateManifest.parse(response.body().string());
        }
    }

    private boolean downloadToFile(String url, File destination, UpdateCallback callback) {
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            long contentLength = response.body().contentLength();
            mainHandler.post(() -> callback.onDownloadMode("差異更新", contentLength));
            copyResponse(response.body().byteStream(), destination, contentLength, callback);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Patch download failed", e);
            destination.delete();
            return false;
        }
    }

    private void downloadFullApk(String url, String expectedSha256, File destination,
                                 UpdateCallback callback) {
        try (Response response = client.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("download error: " + response.code());
            }
            long contentLength = response.body().contentLength();
            mainHandler.post(() -> callback.onDownloadMode("完整下載", contentLength));
            copyResponse(response.body().byteStream(), destination, contentLength, callback);
            if (!FileHash.matches(destination, expectedSha256)) {
                throw new IOException("full APK SHA-256 verification failed");
            }
            mainHandler.post(() -> callback.onDownloadComplete(destination));
        } catch (Exception e) {
            destination.delete();
            mainHandler.post(() -> callback.onError("download/verification failed: " + e.getMessage()));
        }
    }

    private void copyResponse(InputStream input, File destination, long contentLength,
                              UpdateCallback callback) throws IOException {
        try (InputStream is = input; FileOutputStream fos = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            long downloaded = 0;
            int read;
            int lastPercent = -1;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                downloaded += read;
                if (contentLength > 0) {
                    int percent = (int) (downloaded * 100 / contentLength);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        mainHandler.post(() -> callback.onDownloadProgress(percent));
                    }
                }
            }
            fos.getFD().sync();
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }

    private void longName(UpdateCallback callback, String name, long bytes) {
        mainHandler.post(() -> callback.onDownloadMode(name, bytes));
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
