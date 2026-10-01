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
        editAuthPassword.setText(AuthConfig.password(prefs));

        EditText voiceCap = findViewById(R.id.voiceSessionCapMinutes);
        voiceCap.setText(String.valueOf(prefs.getInt("voice_session_cap_minutes",VoiceSessionGuard.DEFAULT_CAP_MINUTES)));

        // v6.20: 複製自動記詞開關（預設開；即時持久化）
        CheckBox checkAutoVocab = findViewById(R.id.checkAutoVocab);
        if (checkAutoVocab != null) {
            checkAutoVocab.setChecked(prefs.getBoolean("auto_vocab_enabled", true));
            checkAutoVocab.setOnCheckedChangeListener((btn, isChecked) ->
                    prefs.edit().putBoolean("auto_vocab_enabled", isChecked).apply());
        }

        android.widget.Spinner sentenceMode=findViewById(R.id.aiSentenceMode);
        if(sentenceMode!=null){
            String[] modes={"off","shadow","suggestions"};String[] labels={"關閉","背景比對（預設）","顯示選項"};
            sentenceMode.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            String saved=prefs.getString("ai_sentence_mode","shadow");int selected=1;for(int i=0;i<modes.length;i++)if(modes[i].equals(saved))selected=i;
            sentenceMode.setSelection(selected);sentenceMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> parent){}
                public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){prefs.edit().putString("ai_sentence_mode",modes[position]).apply();}
            });
        }
        CheckBox checkImeAutoUpload = findViewById(R.id.checkImeAutoUpload);
        ImeTelemetry telemetry=ImeTelemetry.install(this);
        TextView uploadStatus=findViewById(R.id.tvImeUploadStatus);
        if(checkImeAutoUpload!=null){checkImeAutoUpload.setChecked(prefs.getBoolean("ime_auto_upload",true));checkImeAutoUpload.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean("ime_auto_upload",checked).apply());}
        android.widget.Spinner frequency=findViewById(R.id.imeUploadFrequency);
        if(frequency!=null){
            String[] labels={"即時約每分鐘","停止輸入 1 分鐘後（預設）","每小時","只在 Wi-Fi"};
            String[] modes={"realtime","idle","hourly","wifi"};
            frequency.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            String saved=prefs.getString("ime_upload_frequency","idle");int selected=1;
            for(int i=0;i<modes.length;i++)if(modes[i].equals(saved))selected=i;
            frequency.setSelection(selected);
            frequency.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> parent){}
                public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){
                    prefs.edit().putString("ime_upload_frequency",modes[position]).apply();
                }
            });
        }
        Button resetTouch=findViewById(R.id.btnResetTouchLearning);
        if(resetTouch!=null)resetTouch.setOnClickListener(v->new AlertDialog.Builder(this)
            .setTitle("重置落點學習").setMessage("將清除已學習的落點參數並恢復預設值。")
            .setNegativeButton("取消",null).setPositiveButton("重置",(dialog,which)->{
                TouchShadowLearning.resetDefaults(this);
                Toast.makeText(this,"已恢復預設落點參數",Toast.LENGTH_SHORT).show();
            }).show());
        Button uploadNow=findViewById(R.id.btnImeUploadNow);
        if(uploadNow!=null){uploadNow.setOnClickListener(v->{uploadStatus.setText("正在背景上傳…");telemetry.uploadNow();new android.os.Handler().postDelayed(()->{
                    long when=prefs.getLong("ime_last_upload_ms",0);String result=prefs.getString("ime_last_upload_result",telemetry.lastResult());
                    uploadStatus.setText((when==0?"尚無成功上傳":new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(when)))+"｜"+result);
                },1500);});}
        long uploaded=prefs.getLong("ime_last_upload_ms",0);
        uploadStatus.setText((uploaded==0?"最後上傳：尚未成功":("最後上傳："+new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(uploaded))))+"｜"+prefs.getString("ime_last_upload_result","尚未上傳"));
        // Save
        btnSave.setOnClickListener(v -> {
            int cap;
            try { cap=Integer.parseInt(voiceCap.getText().toString().trim()); }
            catch (NumberFormatException e) { voiceCap.setError("請輸入 1 到 30 分鐘"); return; }
            if(cap<1||cap>30){ voiceCap.setError("請輸入 1 到 30 分鐘"); return; }
            prefs.edit().putInt("voice_session_cap_minutes",cap).apply();
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
