package com.simon.voiceime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SettingsActivity extends Activity {

    private EditText editServerUrl;
    private EditText editAuthPassword;
    private EditText editCommandsJson;
    private EditText editCorrections;
    private TextView testResult;
    private TextView tvUpdateStatus;
    private Button btnCheckUpdate;
    private CommandsHelper commandsHelper;
    private UpdateHelper updateHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        editServerUrl = findViewById(R.id.editServerUrl);
        editAuthPassword = findViewById(R.id.editAuthPassword);
        editCommandsJson = findViewById(R.id.editCommandsJson);
        editCorrections = findViewById(R.id.editCorrections);
        testResult = findViewById(R.id.testResult);
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        Button btnSave = findViewById(R.id.btnSave);
        Button btnTest = findViewById(R.id.btnTest);
        Button btnExportCmds = findViewById(R.id.btnExportCmds);
        Button btnImportCmds = findViewById(R.id.btnImportCmds);
        Button btnUploadCorrections = findViewById(R.id.btnUploadCorrections);
        Button btnOpenEditor = findViewById(R.id.btnOpenEditor);

        commandsHelper = new CommandsHelper(this);
        updateHelper = new UpdateHelper(this);

        // Open commands editor
        btnOpenEditor.setOnClickListener(v ->
                startActivity(new Intent(this, CommandsEditorActivity.class)));

        // Check for updates
        btnCheckUpdate.setOnClickListener(v -> checkForUpdate());

        // Load saved settings
        SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        editServerUrl.setText(prefs.getString("server_url", "http://100.84.86.128:8001"));
        editAuthPassword.setText(prefs.getString("auth_password", "guangxin_voice_2026"));

        // Save
        btnSave.setOnClickListener(v -> {
            String url = editServerUrl.getText().toString().trim();
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            String password = editAuthPassword.getText().toString().trim();

            prefs.edit()
                    .putString("server_url", url)
                    .putString("auth_password", password)
                    .apply();
            testResult.setText("已儲存 ✅");
            testResult.setTextColor(0xFF4ECCA3);
        });

        // Test connection
        btnTest.setOnClickListener(v -> {
            testResult.setText("測試中...");
            testResult.setTextColor(0xFF888888);
            testConnection();
        });

        // Upload corrections
        btnUploadCorrections.setOnClickListener(v -> {
            String input = editCorrections.getText().toString().trim();
            if (input.isEmpty()) {
                Toast.makeText(this, "請先輸入修正規則", Toast.LENGTH_SHORT).show();
                return;
            }
            uploadCorrections(input);
        });

        // Export commands
        btnExportCmds.setOnClickListener(v -> {
            String json = commandsHelper.exportToJson();
            editCommandsJson.setText(json);
            Toast.makeText(this, "已匯出到下方文字框", Toast.LENGTH_SHORT).show();
        });

        // Import commands
        btnImportCmds.setOnClickListener(v -> {
            String json = editCommandsJson.getText().toString().trim();
            if (json.isEmpty()) {
                Toast.makeText(this, "請先貼上 JSON", Toast.LENGTH_SHORT).show();
                return;
            }
            if (commandsHelper.importFromJson(json)) {
                Toast.makeText(this, "匯入成功 ✅", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "JSON 格式錯誤", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload commands after returning from editor
        commandsHelper = new CommandsHelper(this);
    }

    private void checkForUpdate() {
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("檢查中...");
        tvUpdateStatus.setVisibility(android.view.View.VISIBLE);
        tvUpdateStatus.setText("正在檢查 GitHub Releases...");
        tvUpdateStatus.setTextColor(0xFF888888);

        updateHelper.checkForUpdate(new UpdateHelper.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String version, String downloadUrl, String releaseNotes) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("檢查更新");
                tvUpdateStatus.setText("發現新版本 v" + version);
                tvUpdateStatus.setTextColor(0xFFF0A500);

                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("發現新版本 v" + version)
                        .setMessage("目前版本：v" + getAppVersion() + "\n最新版本：v" + version
                                + (releaseNotes.isEmpty() ? "" : "\n\n" + releaseNotes))
                        .setPositiveButton("下載更新", (d, w) -> downloadUpdate(downloadUrl))
                        .setNegativeButton("稍後", null)
                        .show();
            }

            @Override
            public void onNoUpdate(String currentVersion) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("檢查更新");
                tvUpdateStatus.setText("已是最新版本 v" + currentVersion);
                tvUpdateStatus.setTextColor(0xFF4ECCA3);
            }

            @Override
            public void onError(String message) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("檢查更新");
                tvUpdateStatus.setText("檢查失敗: " + message);
                tvUpdateStatus.setTextColor(0xFFE94560);
            }

            @Override
            public void onDownloadProgress(int percent) { }

            @Override
            public void onDownloadComplete(File apkFile) { }
        });
    }

    private void downloadUpdate(String downloadUrl) {
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("下載中...");
        tvUpdateStatus.setText("下載中 0%...");
        tvUpdateStatus.setTextColor(0xFF6bc5f0);

        updateHelper.downloadAndInstall(downloadUrl, new UpdateHelper.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String v, String u, String n) { }

            @Override
            public void onNoUpdate(String v) { }

            @Override
            public void onError(String message) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("檢查更新");
                tvUpdateStatus.setText("下載失敗: " + message);
                tvUpdateStatus.setTextColor(0xFFE94560);
            }

            @Override
            public void onDownloadProgress(int percent) {
                tvUpdateStatus.setText("下載中 " + percent + "%...");
            }

            @Override
            public void onDownloadComplete(File apkFile) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("檢查更新");
                tvUpdateStatus.setText("下載完成，正在安裝...");
                tvUpdateStatus.setTextColor(0xFF4ECCA3);
                updateHelper.installApk(apkFile);
            }
        });
    }

    private void uploadCorrections(String input) {
        String url = editServerUrl.getText().toString().trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        // Send raw text — server handles newline/comma/space formats
        String normalized = input;

        try {
            JSONObject body = new JSONObject();
            body.put("corrections", normalized);

            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            Request.Builder reqBuilder = new Request.Builder()
                    .url(url + "/v1/corrections")
                    .post(RequestBody.create(body.toString(),
                            MediaType.parse("application/json; charset=utf-8")));

            String auth = editAuthPassword.getText().toString().trim();
            if (!auth.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + auth);
            }

            client.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() ->
                        Toast.makeText(SettingsActivity.this,
                            "上傳失敗: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() != null ? response.body().string() : "";
                    runOnUiThread(() -> {
                        if (response.isSuccessful()) {
                            Toast.makeText(SettingsActivity.this,
                                "上傳成功 ✅ " + respBody, Toast.LENGTH_LONG).show();
                            editCorrections.setText("");
                        } else {
                            Toast.makeText(SettingsActivity.this,
                                "伺服器錯誤: " + response.code(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "格式錯誤: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getAppVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void testConnection() {
        String url = editServerUrl.getText().toString().trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(url + "/v1/models")
                .get();

        String auth = editAuthPassword.getText().toString().trim();
        if (!auth.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + auth);
        }

        client.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    testResult.setText("連線失敗: " + e.getMessage());
                    testResult.setTextColor(0xFFE94560);
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        testResult.setText("連線成功 ✅ (HTTP " + response.code() + ")");
                        testResult.setTextColor(0xFF4ECCA3);
                    } else {
                        testResult.setText("伺服器回應: HTTP " + response.code());
                        testResult.setTextColor(0xFFF0A500);
                    }
                });
            }
        });
    }
}
