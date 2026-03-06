# Simon Voice IME - Sherpa-ONNX 本機 STT 整合

## 目標

在 REPLACE（換字）模式下，使用 Sherpa-ONNX 在手機端進行語音辨識（STT），然後只傳**文字**到伺服器做 LLM 換字推理。其他模式（追加/拼字/翻譯）維持現有音訊上傳流程不變。

### 預期效果
- 換字延遲：1.5 秒 → ~0.6 秒
- 原因：省掉音訊上傳（~400ms）+ 伺服器端 STT（~200ms）

## 建構環境
- JDK：`/home/simon/.local/jdk`（Temurin 17.0.12）
- Android SDK：`/home/simon/android-sdk`（platform-34, build-tools 34.0.0）
- Gradle：8.5（wrapper）
- 建構指令：
```bash
bash -c 'export JAVA_HOME=/home/simon/.local/jdk; export PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH"; export GRADLE_USER_HOME=/home/simon/.gradle_clean; export ANDROID_HOME=/home/simon/android-sdk; cd /home/simon/simon-voice-ime && ./gradlew assembleDebug --project-cache-dir /home/simon/simon-voice-ime/.gradle_user --no-daemon'
```
- APK 輸出：`app/build/outputs/apk/debug/app-debug.apk`

## 步驟總覽

共 4 步：
1. 修改 `app/build.gradle` 加入 Sherpa-ONNX 依賴
2. 新增 `LocalSTTHelper.java` — Sherpa-ONNX 封裝（含模型下載）
3. 修改 `SimonIMEService.java` — REPLACE 模式改用本機 STT + 文字端點
4. Build + 發佈 GitHub Release

## 步驟 1：修改 app/build.gradle

在 `dependencies` 區塊加入 Sherpa-ONNX：

```gradle
dependencies {
    implementation 'com.k2fsa.sherpa:onnx:1.10.35'
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'org.json:json:20231013'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
}
```

在 `android` 區塊加入 packaging 設定（避免 .so 衝突）：

```gradle
android {
    // ... 現有設定 ...

    packagingOptions {
        pickFirst '**/*.so'
    }
}
```

## 步驟 2：新增 LocalSTTHelper.java

路徑：`app/src/main/java/com/simon/voiceime/LocalSTTHelper.java`

功能：
- 封裝 Sherpa-ONNX OfflineRecognizer（SenseVoice 模型）
- 首次使用時自動從 GitHub 下載模型到 App 內部儲存
- 提供 `recognize(short[] pcmSamples, int sampleRate)` 方法

### 模型檔案

使用 SenseVoice 中文模型（sherpa-onnx 格式）：
- 模型下載 URL：`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2`
- 解壓後需要的檔案：
  - `model.int8.onnx`（~220MB）
  - `tokens.txt`（~50KB）
- 存放位置：`context.getFilesDir() + "/sherpa-onnx-models/"`

### 關鍵程式碼結構

```java
package com.simon.voiceime;

import android.content.Context;
import android.util.Log;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.net.URL;

public class LocalSTTHelper {
    private static final String TAG = "LocalSTT";
    private static final String MODEL_DIR = "sherpa-onnx-models";
    private OfflineRecognizer recognizer;
    private boolean isReady = false;
    private Context context;

    public LocalSTTHelper(Context context) {
        this.context = context;
    }

    /**
     * 初始化（在背景執行緒調用，因為首次要下載模型 ~220MB）
     */
    public void init() {
        File modelDir = new File(context.getFilesDir(), MODEL_DIR);
        File modelFile = new File(modelDir, "model.int8.onnx");
        File tokensFile = new File(modelDir, "tokens.txt");

        if (!modelFile.exists() || !tokensFile.exists()) {
            // 下載模型（顯示進度通知）
            downloadModel(modelDir);
        }

        if (modelFile.exists() && tokensFile.exists()) {
            // 初始化 Recognizer
            OfflineModelConfig modelConfig = new OfflineModelConfig();
            // SenseVoice 設定
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel(modelFile.getAbsolutePath());
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens(tokensFile.getAbsolutePath());
            modelConfig.setNumThreads(4);

            OfflineRecognizerConfig config = new OfflineRecognizerConfig();
            config.setModelConfig(modelConfig);

            recognizer = new OfflineRecognizer(config);
            isReady = true;
            Log.i(TAG, "Sherpa-ONNX 初始化完成");
        }
    }

    /**
     * 辨識 PCM 音訊
     * @param samples 16-bit PCM 樣本（轉為 float）
     * @param sampleRate 取樣率（16000）
     * @return 辨識結果文字，失敗回空字串
     */
    public String recognize(short[] samples, int sampleRate) {
        if (!isReady || recognizer == null) return "";

        // short[] → float[]
        float[] floatSamples = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            floatSamples[i] = samples[i] / 32768.0f;
        }

        OfflineStream stream = recognizer.createStream();
        stream.acceptWaveform(floatSamples, sampleRate);
        recognizer.decode(stream);
        String result = recognizer.getResult(stream).getText();
        return result != null ? result.trim() : "";
    }

    public boolean isReady() {
        return isReady;
    }

    private void downloadModel(File modelDir) {
        // 從 GitHub releases 下載 tar.bz2，解壓取出 model.int8.onnx 和 tokens.txt
        // 實作細節：用 OkHttp 或 HttpURLConnection 下載
        // 解壓：用 Apache Commons Compress 或手動 BZip2 + Tar
        // 注意：下載 ~220MB，必須在背景執行緒 + 顯示進度
        // 建議先用簡單的 HttpURLConnection 分塊下載
        //
        // 替代方案（更簡單）：不下載 tar.bz2，直接下載單檔：
        // model: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx
        // tokens: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt
        Log.i(TAG, "開始下載模型...");
        modelDir.mkdirs();
        try {
            downloadFile(
                "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                new File(modelDir, "model.int8.onnx")
            );
            downloadFile(
                "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                new File(modelDir, "tokens.txt")
            );
            Log.i(TAG, "模型下載完成");
        } catch (Exception e) {
            Log.e(TAG, "模型下載失敗", e);
        }
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        try (InputStream in = url.openStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
```

**注意**：以上是參考結構。Sherpa-ONNX Java API 可能有些不同，請參考 sherpa-onnx 官方 Android 範例確認正確的 class/method 名稱：
- GitHub: k2-fsa/sherpa-onnx → android/SherpaOnnxOffline/
- 主要 class: `OfflineRecognizer`, `OfflineRecognizerConfig`, `OfflineModelConfig`, `OfflineSenseVoiceModelConfig`, `OfflineStream`

## 步驟 3：修改 SimonIMEService.java

### 3.1 新增成員變數

在 class 開頭加入：

```java
private LocalSTTHelper localSTT;
private boolean localSTTReady = false;
```

### 3.2 在 onCreate 初始化

在 `onCreate()` 中加入背景初始化：

```java
localSTT = new LocalSTTHelper(this);
new Thread(() -> {
    localSTT.init();
    localSTTReady = localSTT.isReady();
    if (localSTTReady) {
        mainHandler.post(() -> Log.i(TAG, "本機 STT 就緒"));
    }
}).start();
```

### 3.3 修改 stopRecording() — REPLACE 模式走本機 STT

在 `stopRecording()` 方法中，當 `currentMode == Mode.REPLACE` 且 `localSTTReady` 時：
1. 將 PCM buffer 轉為 `short[]`
2. 調用 `localSTT.recognize(samples, SAMPLE_RATE)` 取得文字
3. 不上傳音訊，改呼叫 `/v1/replace-text`（文字端點）
4. 如果本機 STT 失敗（空字串），fallback 回原有音訊上傳流程

```java
private void stopRecording() {
    // ... 現有的停止錄音邏輯 ...
    byte[] pcmData = pcmBuffer.toByteArray();

    if (pcmData.length < SAMPLE_RATE * 2 * 0.3) {
        mainHandler.post(() -> updateStatus("錄音太短，請再試一次"));
        return;
    }

    // REPLACE 模式 + 本機 STT 就緒 → 走本機辨識
    if (currentMode == Mode.REPLACE && localSTTReady) {
        new Thread(() -> {
            // byte[] → short[]
            short[] samples = new short[pcmData.length / 2];
            ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);

            String spokenText = localSTT.recognize(samples, SAMPLE_RATE);

            if (spokenText != null && !spokenText.isEmpty()) {
                // 本機 STT 成功 → 只傳文字到伺服器
                mainHandler.post(() -> updateStatus("本機辨識: " + spokenText));
                sendTextReplace(spokenText);
            } else {
                // 本機 STT 失敗 → fallback 上傳音訊
                mainHandler.post(() -> updateStatus("本機辨識失敗，上傳中..."));
                byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
                sendToWTI(wavData, currentMode);
            }
        }).start();
        return;
    }

    // 其他模式：維持原有流程
    byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
    sendToWTI(wavData, currentMode);
}
```

### 3.4 新增 sendTextReplace() 方法

這個方法只傳文字（不傳音訊）到伺服器 `/v1/replace-text`：

```java
private void sendTextReplace(String spokenText) {
    String serverUrl = getServerUrl();
    InputConnection ic = getCurrentInputConnection();

    String beforeCursor = "";
    String afterCursor = "";
    if (ic != null) {
        CharSequence before = ic.getTextBeforeCursor(50, 0);
        CharSequence after = ic.getTextAfterCursor(50, 0);
        beforeCursor = before != null ? before.toString() : "";
        afterCursor = after != null ? after.toString() : "";
    }

    MultipartBody body = new MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("spoken_text", spokenText)
            .addFormDataPart("before_cursor", beforeCursor)
            .addFormDataPart("after_cursor", afterCursor)
            .build();

    Request.Builder reqBuilder = new Request.Builder()
            .url(serverUrl + "/v1/replace-text")
            .post(body);

    String auth = getAuthPassword();
    if (auth != null && !auth.isEmpty()) {
        reqBuilder.addHeader("Authorization", "Bearer " + auth);
    }

    httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
        @Override
        public void onFailure(Call call, IOException e) {
            Log.e(TAG, "Replace-text request failed", e);
            mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
        }

        @Override
        public void onResponse(Call call, Response response) throws IOException {
            try {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                    return;
                }
                JSONObject json = new JSONObject(responseBody);
                handleWTIResponse(json, Mode.REPLACE);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing replace-text response", e);
                mainHandler.post(() -> updateStatus("解析錯誤"));
            }
        }
    });
}
```

## 步驟 4：Build + Release

```bash
# 1. Build
bash -c 'export JAVA_HOME=/home/simon/.local/jdk; export PATH="/home/simon/.local/jdk/bin:/usr/bin:/bin:$PATH"; export GRADLE_USER_HOME=/home/simon/.gradle_clean; export ANDROID_HOME=/home/simon/android-sdk; cd /home/simon/simon-voice-ime && ./gradlew assembleDebug --project-cache-dir /home/simon/simon-voice-ime/.gradle_user --no-daemon'

# 2. Commit + Push
cd /home/simon/simon-voice-ime
git add -A && git commit -m "v2.6: 本機 STT (Sherpa-ONNX SenseVoice) 加速換字模式"
gh auth setup-git && git push origin main

# 3. GitHub Release
gh release create v2.6 app/build/outputs/apk/debug/app-debug.apk#simon-voice-ime-v2.6.apk \
  --repo poyuchenlaw/simon-voice-ime \
  --title "v2.6 — 本機 STT 加速換字" \
  --notes "換字模式使用手機端 Sherpa-ONNX SenseVoice 辨識，只傳文字到伺服器。延遲 1.5s → ~0.6s。首次使用需下載模型 (~220MB)。"
```

## 伺服器端（已完成）

WTI 伺服器已新增 `/v1/replace-text` 端點，接受：
- `spoken_text`：本機 STT 辨識結果（文字）
- `before_cursor`：游標前 50 字
- `after_cursor`：游標後 50 字

回傳格式與 `/v1/replace` 相同：
```json
{"text": "...", "delete_before": N, "delete_after": N, "insert": "..."}
```

## 注意事項

1. **模型大小**：SenseVoice int8 約 220MB，首次下載需 Wi-Fi。下載完存在 App 內部儲存，之後不再下載。
2. **Sherpa-ONNX API**：以上程式碼是參考結構，實際 API 請查 k2-fsa/sherpa-onnx 的 Android 範例。版本 1.10.35 若不存在，用 Maven Central 上最新的 `com.k2fsa.sherpa:onnx` 版本。
3. **Fallback**：如果本機 STT 辨識失敗（空字串），自動 fallback 回原有的音訊上傳流程（`/v1/replace`）。
4. **其他模式不受影響**：APPEND、SPELL、TRANSLATE 三個模式完全不變，依然上傳音訊到 `/v1/audio/transcriptions`。
5. **Fold6 效能**：Snapdragon 8 Gen 3 + 12GB RAM，SenseVoice int8 預計 ~80-100ms 辨識速度。
6. **`.gradle/` 問題**：建構時用 `--project-cache-dir .gradle_user` 繞過 root 殘留物。
