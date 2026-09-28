package com.simon.voiceime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
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
        Button btnClearEnglishLearned = findViewById(R.id.btnClearEnglishLearned);

        commandsHelper = new CommandsHelper(this);
        updateHelper = new UpdateHelper(this);

        // 動態顯示實際版本號
        TextView tvVersion = findViewById(R.id.tvVersion);
        tvVersion.setText("Simon Voice IME v" + getAppVersion());

        // Open commands editor
        btnOpenEditor.setOnClickListener(v ->
                startActivity(new Intent(this, CommandsEditorActivity.class)));

        // Check for updates
        btnCheckUpdate.setOnClickListener(v -> checkForUpdate());

        // v6.23: 清除已學英文字
        if (btnClearEnglishLearned != null) {
            btnClearEnglishLearned.setOnClickListener(v -> {
                EnglishDictionary dict = new EnglishDictionary(this);
                dict.clearLearned();
                Toast.makeText(this, "已清除已學英文字 ✅", Toast.LENGTH_SHORT).show();
            });
        }

        // Load saved settings
        SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        editServerUrl.setText(prefs.getString("server_url", "http://100.84.86.128:8001"));
        editAuthPassword.setText(prefs.getString("auth_password", "guangxin_voice_2026"));

        // v6.20: 複製自動記詞開關（預設開；即時持久化）
        CheckBox checkAutoVocab = findViewById(R.id.checkAutoVocab);
        if (checkAutoVocab != null) {
            checkAutoVocab.setChecked(prefs.getBoolean("auto_vocab_enabled", true));
            checkAutoVocab.setOnCheckedChangeListener((btn, isChecked) ->
                    prefs.edit().putBoolean("auto_vocab_enabled", isChecked).apply());
        }

        CheckBox checkT9Ai = findViewById(R.id.checkT9Ai);
        CheckBox checkT9Learning = findViewById(R.id.checkT9Learning);
        CheckBox checkImeAutoUpload = findViewById(R.id.checkImeAutoUpload);
        ImeTelemetry telemetry=ImeTelemetry.install(this);
        TextView uploadStatus=findViewById(R.id.tvImeUploadStatus);
        if(checkImeAutoUpload!=null){checkImeAutoUpload.setChecked(prefs.getBoolean("ime_auto_upload",true));checkImeAutoUpload.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean("ime_auto_upload",checked).apply());}
        Button uploadNow=findViewById(R.id.btnImeUploadNow);
        if(uploadNow!=null){uploadNow.setOnClickListener(v->{uploadStatus.setText("正在背景上傳…");telemetry.uploadNow();new android.os.Handler().postDelayed(()->{
                    long when=prefs.getLong("ime_last_upload_ms",0);String result=prefs.getString("ime_last_upload_result",telemetry.lastResult());
                    uploadStatus.setText((when==0?"尚無成功上傳":new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(when)))+"｜"+result);
                },1500);});}
        long uploaded=prefs.getLong("ime_last_upload_ms",0);
        uploadStatus.setText((uploaded==0?"最後上傳：尚未成功":("最後上傳："+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(uploaded))))+"｜"+prefs.getString("ime_last_upload_result","尚未上傳"));
        if (checkT9Ai != null) {
            checkT9Ai.setChecked(prefs.getBoolean("t9_ai_enabled", true));
            checkT9Ai.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("t9_ai_enabled", checked).apply());
        }
        if (checkT9Learning != null) {
            checkT9Learning.setChecked(prefs.getBoolean("t9_learning_enabled", true));
            checkT9Learning.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("t9_learning_enabled", checked).apply());
        }
        Button btnT9Help = findViewById(R.id.btnT9Help);
        if (btnT9Help != null) btnT9Help.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("注音九宮格")
                .setMessage("每字按兩下就好（聲母那格＋韻母那格）；不用打聲調。句子越長越準。選錯了點一下預覽列的字就能換。打字停一下，AI 會給整句建議，點它就採用。")
                .setPositiveButton("知道了", null).show());
        Button btnReenableT9 = findViewById(R.id.btnReenableT9);
        if (btnReenableT9 != null) btnReenableT9.setOnClickListener(v -> {
            boolean enabled = T9InitGuard.reenable(T9InitGuard.adapt(prefs));
            Toast.makeText(this, enabled ? "九宮格已重新啟用" : "設定未能儲存，請稍後重試",
                    Toast.LENGTH_SHORT).show();
        });
        Button btnClearT9 = findViewById(R.id.btnClearT9Learning);
        if (btnClearT9 != null) btnClearT9.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("清除輸入學習資料？")
                .setMessage("會刪除本機觸控記錄、送出紀錄及九宮格常用詞。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (d,w) -> { new T9LearningStore(this).clearLearning(); Toast.makeText(this,"已清除輸入學習資料",Toast.LENGTH_SHORT).show(); }).show());

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
            public void onUpdateAvailable(String version, String downloadUrl, String manifestUrl,
                                          String expectedFullSha256, String releaseNotes) {
                btnCheckUpdate.setEnabled(true);
                btnCheckUpdate.setText("檢查更新");
                tvUpdateStatus.setText("發現新版本 v" + version);
                tvUpdateStatus.setTextColor(0xFFF0A500);

                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("發現新版本 v" + version)
                        .setMessage("目前版本：v" + getAppVersion() + "\n最新版本：v" + version
                                + (releaseNotes.isEmpty() ? "" : "\n\n" + releaseNotes))
                        .setPositiveButton("下載更新", (d, w) -> downloadUpdate(
                                downloadUrl, manifestUrl, expectedFullSha256))
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

    private void downloadUpdate(String downloadUrl, String manifestUrl, String expectedFullSha256) {
        final String[] downloadMode = {"下載中"};
        btnCheckUpdate.setEnabled(false);
        btnCheckUpdate.setText("下載中...");
        tvUpdateStatus.setText("下載中 0%...");
        tvUpdateStatus.setTextColor(0xFF6bc5f0);

        updateHelper.downloadAndInstall(downloadUrl, manifestUrl, expectedFullSha256,
                new UpdateHelper.UpdateCallback() {
            @Override
            public void onUpdateAvailable(String v, String u, String m, String h, String n) { }

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
                tvUpdateStatus.setText(downloadMode[0] + " " + percent + "%...");
            }

            @Override
            public void onDownloadMode(String mode, long bytes) {
                downloadMode[0] = mode + " " + String.format(java.util.Locale.US,
                        "%.1f MB", bytes / (1024.0 * 1024.0));
                tvUpdateStatus.setText(downloadMode[0] + " 0%...");
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
            JSONObject body = AppVersion.withAppVersion(new JSONObject());
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
                .url(AppVersion.withAppVersion(url + "/v1/models"))
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
