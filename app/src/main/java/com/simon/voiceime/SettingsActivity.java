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

    private String exportRecordingId;
    private static final int EXPORT_RECORDING=679;
    private EditText editServerUrl;
    private EditText editAuthPassword;
    private EditText editCommandsJson;
    private EditText editCorrections;
    private TextView testResult;
    private TextView tvUpdateStatus;
    private Button btnCheckUpdate;
    private CommandsHelper commandsHelper;
    private UpdateHelper updateHelper;

    private static String size(long bytes){return String.format(java.util.Locale.ROOT,"%.2f MB",bytes/1000000.0);}
    private void showStorageDetails(){
        new Thread(()->{
            try{
                java.util.Map<String,File> roots=ImeTelemetry.storageRoots(this);JSONObject report=StorageDiagnostics.scan(roots);
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    android.widget.ScrollView scroll=new android.widget.ScrollView(this);android.widget.LinearLayout list=new android.widget.LinearLayout(this);list.setOrientation(android.widget.LinearLayout.VERTICAL);scroll.addView(list);
                    TextView total=new TextView(this);total.setText("合計 "+size(report.optLong("total_bytes"))+(report.optBoolean("over_500_mb")?"（超過 500 MB）":"")+"\n學習資料、個人詞庫與未保全錄音保留。錄音清理只處理有完整保全收據的上傳暫存；原音保留。");list.addView(total);
                    JSONObject categories=report.optJSONObject("categories");
                    for(String category:roots.keySet()){
                        android.widget.LinearLayout row=new android.widget.LinearLayout(this);row.setOrientation(android.widget.LinearLayout.HORIZONTAL);row.setBaselineAligned(false);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setLayoutParams(new android.widget.LinearLayout.LayoutParams(-1,-2));TextView label=new TextView(this);label.setText(category+"\n"+size(categories.optLong(category)));row.addView(label,new android.widget.LinearLayout.LayoutParams(0,-2,1));
                        Button clean=new Button(this);clean.setText("清理");clean.setEnabled(StorageDiagnostics.cleanable(category)||"files/voice_pending".equals(category));row.addView(clean,new android.widget.LinearLayout.LayoutParams(-2,-2));list.addView(row);
                        clean.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("確認清理 "+category).setMessage("只清理已知舊模型、閒置超過 24 小時的更新暫存，或有完整保全收據的錄音上傳暫存。學習與原始錄音保留。").setNegativeButton("取消",null).setPositiveButton("清理",(dialog,which)->{
                            clean.setEnabled(false);
                            new Thread(()->{long removed=0;boolean ok=false;try{removed="files/voice_pending".equals(category)?VoicePendingQueue.getInstance(getFilesDir(),16000).clearArchivedScratch():StorageDiagnostics.clear(roots,category);ok=true;}catch(Exception error){android.util.Log.e("Settings","Storage cleanup incomplete",error);}final long bytes=removed;final boolean success=ok;
                                ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry!=null)try{telemetry.record("storage_cleanup","settings",new JSONObject().put("category",category).put("removed_bytes",bytes).put("ok",success).put("confirmed",true),false);}catch(Exception error){android.util.Log.e("Settings","Cleanup diagnostic failed",error);}
                                runOnUiThread(()->{if(!isFinishing()){Toast.makeText(this,success?"已清理 "+size(bytes):"清理未完成；保留資料，請再查明細",Toast.LENGTH_LONG).show();clean.setEnabled(true);}});
                            },"IME-Storage-Cleanup").start();
                        }).show());
                    }
                    TextView largest=new TextView(this);StringBuilder text=new StringBuilder("\n前 20 大檔案（只列大小與相對路徑）\n");org.json.JSONArray files=report.optJSONArray("largest_files");for(int n=0;n<files.length();n++){JSONObject file=files.optJSONObject(n);text.append(file.optString("path")).append("  ").append(size(file.optLong("bytes"))).append('\n');}largest.setText(text);list.addView(largest);
                    new AlertDialog.Builder(this).setTitle("空間明細").setView(scroll).setPositiveButton("關閉",null).show();
                });
            }catch(Exception error){android.util.Log.e("Settings","Storage scan failed",error);runOnUiThread(()->Toast.makeText(this,"無法讀取完整空間明細，請稍後再試",Toast.LENGTH_LONG).show());}
        },"IME-Storage-Scan").start();
    }
    private void chooseRecordingExport(){
        VoicePendingQueue queue=VoicePendingQueue.getInstance(getFilesDir(),16000);java.util.List<String> ids=queue.needsAttentionSessions();
        if(ids.isEmpty()){Toast.makeText(this,"沒有需處理的保留錄音",Toast.LENGTH_LONG).show();return;}
        String[] labels=new String[ids.size()];for(int n=0;n<labels.length;n++)labels[n]="錄音 "+(n+1)+"（"+(queue.audioMs(ids.get(n))/1000)+" 秒）";
        new AlertDialog.Builder(this).setTitle("選擇匯出的保留錄音").setItems(labels,(dialog,index)->{
            exportRecordingId=ids.get(index);Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/wav").putExtra(Intent.EXTRA_TITLE,"保留錄音-"+System.currentTimeMillis()+".wav");
            try{startActivityForResult(intent,EXPORT_RECORDING);}catch(android.content.ActivityNotFoundException unavailable){Toast.makeText(this,"此裝置沒有檔案儲存介面，原音仍保留",Toast.LENGTH_LONG).show();}
        }).setNegativeButton("取消",null).show();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("export_recording_id",exportRecordingId);}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=EXPORT_RECORDING||result!=RESULT_OK||data==null||data.getData()==null||exportRecordingId==null)return;
        String id=exportRecordingId;exportRecordingId=null;android.net.Uri uri=data.getData();VoicePendingQueue queue=VoicePendingQueue.getInstance(getFilesDir(),16000);
        queue.execute(()->{
            boolean ok=false;try{queue.runIO(()->{
                long length=queue.pcmFile(id).length();if(length<=0||length>0xffffffffL-36)throw new IOException("recording unavailable or too large for WAV");
                byte[] header=SimonIMEService.pcmToWav(new byte[0],16000,1,16);java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);buffer.putInt(4,(int)(length+36));buffer.putInt(40,(int)length);
                try(java.io.OutputStream output=getContentResolver().openOutputStream(uri)){if(output==null)throw new IOException("export destination unavailable");output.write(header);queue.exportPcm(id,output);}return null;
            });ok=true;}catch(Exception error){android.util.Log.e("Settings","Recording export failed; original retained",error);}final boolean success=ok;
            ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry!=null)try{telemetry.record("recording_export","settings",new JSONObject().put("ok",success).put("audio_ms",queue.audioMs(id)),false);}catch(Exception error){android.util.Log.e("Settings","Export diagnostic failed",error);}
            runOnUiThread(()->Toast.makeText(this,success?"錄音已匯出；原音仍保留":"匯出未完成，原音仍保留，請重新選擇位置",Toast.LENGTH_LONG).show());
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if("phone".equals(BuildConfig.FLAVOR))AiSentencePhone.migrateAutoApply(this);
        setContentView(R.layout.activity_settings);

        if("phone".equals(BuildConfig.FLAVOR)){
            android.view.ViewGroup content=findViewById(android.R.id.content);
            android.widget.ScrollView scroll=(android.widget.ScrollView)content.getChildAt(0);
            android.widget.LinearLayout options=(android.widget.LinearLayout)scroll.getChildAt(0);
            SharedPreferences p=getSharedPreferences("simon_ime_prefs",MODE_PRIVATE);
            CheckBox local=new CheckBox(this);local.setText("本機對話脈絡（不儲存、不上傳）");local.setTextColor(0xffcccccc);
            local.setChecked(p.getBoolean("local_screen_context",false));
            local.setOnCheckedChangeListener((v,enabled)->{p.edit().putBoolean("local_screen_context",enabled).apply();LocalConversationContext.clear();});
            options.addView(local,1);
            Button localAccess=new Button(this);localAccess.setText("本機對話脈絡無障礙設定");
            localAccess.setOnClickListener(v->startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)));options.addView(localAccess,2);
            Button access=new Button(this);access.setText("授權 Simon LINE 語音嘴替（讀取可見對話）");
            access.setOnClickListener(v->{
                Intent detail=new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                detail.putExtra(Intent.EXTRA_COMPONENT_NAME,new android.content.ComponentName(this,LineContextAccessibilityService.class).flattenToString());
                try {startActivity(detail);} catch(android.content.ActivityNotFoundException | SecurityException unavailable) {startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            });options.addView(access,3);
        }

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
        android.widget.Button retryVoice=new android.widget.Button(this);
        retryVoice.setText("重試需處理的語音");
        android.view.ViewGroup voiceSettings=(android.view.ViewGroup)voiceCap.getParent();
        voiceSettings.addView(retryVoice,voiceSettings.indexOfChild(voiceCap)+1);
        retryVoice.setOnClickListener(v -> {
            VoicePendingQueue q=VoicePendingQueue.getInstance(getFilesDir(),16000);
            q.execute(() -> {
                java.util.List<String> ids=q.needsAttentionSessions();
                for(String id:ids)q.retryForUser(id);
                runOnUiThread(() -> Toast.makeText(this,ids.isEmpty()?"沒有需處理語音":"已安排重試；返回鍵盤即可補傳",Toast.LENGTH_LONG).show());
            });
        });


        Button storage=new Button(this);storage.setText("空間明細");voiceSettings.addView(storage,voiceSettings.indexOfChild(retryVoice)+1);storage.setOnClickListener(v->showStorageDetails());
        Button export=new Button(this);export.setText("匯出需處理的保留錄音");voiceSettings.addView(export,voiceSettings.indexOfChild(storage)+1);export.setOnClickListener(v->chooseRecordingExport());
        if(savedInstanceState!=null)exportRecordingId=savedInstanceState.getString("export_recording_id");

        // v6.20: 複製自動記詞開關（預設開；即時持久化）
        CheckBox checkAutoVocab = findViewById(R.id.checkAutoVocab);
        if (checkAutoVocab != null) {
            checkAutoVocab.setChecked(prefs.getBoolean("auto_vocab_enabled", true));
            checkAutoVocab.setOnCheckedChangeListener((btn, isChecked) ->
                    prefs.edit().putBoolean("auto_vocab_enabled", isChecked).apply());
        }

        CheckBox autoCorrection=findViewById(R.id.checkAutoCorrection);
        if(autoCorrection!=null){autoCorrection.setChecked(prefs.getBoolean("ai_sentence_auto_apply",false));autoCorrection.setOnCheckedChangeListener((button,checked)->prefs.edit().putBoolean("ai_sentence_auto_apply",checked).apply());}
        Button resetWords=findViewById(R.id.btnResetZhuyinLearning);
        if(resetWords!=null)resetWords.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("重置注音選字學習")
            .setMessage("清除已學習的選字與連續選字詞；已安裝的個人詞彙保留。")
            .setNegativeButton("取消",null).setPositiveButton("重置",(dialog,which)->{
                try{RimeVocabularyInstaller.resetLearned(new java.io.File(getFilesDir(),"rime/user"));Toast.makeText(this,"已重置注音選字學習",Toast.LENGTH_SHORT).show();}
                catch(Exception failure){android.util.Log.e("Settings","reset learning failed",failure);Toast.makeText(this,"重置未完成，請再試一次",Toast.LENGTH_SHORT).show();}
            }).show());
        android.widget.Spinner sentenceMode=findViewById(R.id.aiSentenceMode);
        if(sentenceMode!=null){
            String[] modes={"off","shadow","suggestions","live"};String[] labels={"關閉","背景比對","顯示選項（預設）","預覽即時校正"};
            sentenceMode.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
            String saved=prefs.getString("ai_sentence_mode","suggestions");int selected=2;for(int i=0;i<modes.length;i++)if(modes[i].equals(saved))selected=i;
            sentenceMode.setSelection(selected);sentenceMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onNothingSelected(android.widget.AdapterView<?> parent){}
                public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id){prefs.edit().putString("ai_sentence_mode",modes[position]).apply();}
            });
        }
        android.widget.Spinner layoutMode=findViewById(R.id.textLayoutMode);
        if(layoutMode!=null){String[] modes={"text_word_char","legacy_zhuyin"};layoutMode.setAdapter(new android.widget.ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"文字／詞候選／字候選","舊注音列"}));layoutMode.setSelection("legacy_zhuyin".equals(prefs.getString("layout_mode","text_word_char"))?1:0);layoutMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,android.view.View v,int at,long id){prefs.edit().putString("layout_mode",modes[at]).apply();}});}
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
