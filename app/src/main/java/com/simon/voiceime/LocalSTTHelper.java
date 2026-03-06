package com.simon.voiceime;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 本機端 Sherpa-ONNX SenseVoice STT 封裝。
 * 用於 REPLACE 模式，在手機端辨識語音後只傳文字到伺服器。
 */
public class LocalSTTHelper {
    private static final String TAG = "LocalSTT";
    private static final String MODEL_DIR = "sherpa-onnx-sensevoice";
    private static final String MODEL_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx";
    private static final String TOKENS_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt";

    private OfflineRecognizer recognizer;
    private volatile boolean isReady = false;
    private volatile boolean isDownloading = false;
    private final Context context;
    private StatusCallback statusCallback;

    public interface StatusCallback {
        void onStatus(String message);
    }

    public LocalSTTHelper(Context context) {
        this.context = context;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    /**
     * 背景初始化。首次需下載模型（~220MB），之後直接載入。
     */
    public void init() {
        File modelDir = new File(context.getFilesDir(), MODEL_DIR);
        File modelFile = new File(modelDir, "model.int8.onnx");
        File tokensFile = new File(modelDir, "tokens.txt");

        if (!modelFile.exists() || !tokensFile.exists()) {
            isDownloading = true;
            reportStatus("首次使用，下載語音模型中...");
            if (!downloadModels(modelDir, modelFile, tokensFile)) {
                reportStatus("模型下載失敗，換字模式使用伺服器辨識");
                isDownloading = false;
                return;
            }
            isDownloading = false;
        }

        try {
            initRecognizer(modelFile.getAbsolutePath(), tokensFile.getAbsolutePath());
            isReady = true;
            Log.i(TAG, "Sherpa-ONNX SenseVoice 初始化完成");
            reportStatus("本機語音引擎就緒");
        } catch (Exception e) {
            Log.e(TAG, "Sherpa-ONNX 初始化失敗", e);
            reportStatus("本機語音引擎初始化失敗");
        }
    }

    private void initRecognizer(String modelPath, String tokensPath) {
        // SenseVoice 模型設定
        OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
        senseVoice.setModel(modelPath);
        senseVoice.setLanguage("zh");
        senseVoice.setUseInverseTextNormalization(true);

        // 模型主設定
        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setSenseVoice(senseVoice);
        modelConfig.setTokens(tokensPath);
        modelConfig.setNumThreads(4);
        modelConfig.setProvider("cpu");

        // 辨識器設定
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(modelConfig);
        config.setDecodingMethod("greedy_search");

        // 建立辨識器（不使用 AssetManager，從檔案載入）
        recognizer = new OfflineRecognizer(null, config);
    }

    /**
     * 辨識 PCM 音訊。
     *
     * @param pcmData 原始 16-bit PCM bytes (little-endian)
     * @param sampleRate 取樣率（16000）
     * @return 辨識結果文字，失敗回空字串
     */
    public String recognize(byte[] pcmData, int sampleRate) {
        if (!isReady || recognizer == null) return "";

        try {
            // byte[] → float[] (16-bit PCM → [-1.0, 1.0] float)
            int numSamples = pcmData.length / 2;
            float[] floatSamples = new float[numSamples];
            for (int i = 0; i < numSamples; i++) {
                short sample = (short) ((pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8));
                floatSamples[i] = sample / 32768.0f;
            }

            OfflineStream stream = recognizer.createStream();
            stream.acceptWaveform(floatSamples, sampleRate);
            recognizer.decode(stream);
            OfflineRecognizerResult result = recognizer.getResult(stream);

            String text = result != null ? result.getText() : "";
            if (text != null) {
                text = text.trim();
            }

            Log.i(TAG, "辨識結果: '" + text + "'");
            return text != null ? text : "";
        } catch (Exception e) {
            Log.e(TAG, "辨識失敗", e);
            return "";
        }
    }

    public boolean isReady() {
        return isReady;
    }

    public boolean isDownloading() {
        return isDownloading;
    }

    private boolean downloadModels(File modelDir, File modelFile, File tokensFile) {
        modelDir.mkdirs();
        try {
            // tokens.txt 很小（~50KB），先下載
            reportStatus("下載 tokens.txt...");
            if (!downloadFile(TOKENS_URL, tokensFile)) return false;

            // model.int8.onnx 約 220MB
            reportStatus("下載語音模型（約 220MB）...");
            if (!downloadFile(MODEL_URL, modelFile)) {
                tokensFile.delete();
                return false;
            }

            reportStatus("模型下載完成");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "模型下載失敗", e);
            return false;
        }
    }

    private boolean downloadFile(String urlStr, File dest) {
        File tmpFile = new File(dest.getAbsolutePath() + ".tmp");
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Log.e(TAG, "下載失敗 HTTP " + conn.getResponseCode() + ": " + urlStr);
                return false;
            }

            long totalSize = conn.getContentLengthLong();
            long downloaded = 0;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmpFile)) {
                byte[] buf = new byte[65536];
                int n;
                int lastPercent = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (totalSize > 0) {
                        int percent = (int) (downloaded * 100 / totalSize);
                        if (percent != lastPercent && percent % 10 == 0) {
                            lastPercent = percent;
                            reportStatus("下載中... " + percent + "%");
                        }
                    }
                }
            }

            // 下載完成，重新命名
            if (tmpFile.renameTo(dest)) {
                Log.i(TAG, "下載完成: " + dest.getName() + " (" + downloaded + " bytes)");
                return true;
            } else {
                Log.e(TAG, "重新命名失敗: " + tmpFile + " → " + dest);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "下載失敗: " + urlStr, e);
            tmpFile.delete();
            return false;
        }
    }

    private void reportStatus(String message) {
        Log.i(TAG, message);
        if (statusCallback != null) {
            statusCallback.onStatus(message);
        }
    }

    public void release() {
        if (recognizer != null) {
            recognizer.release();
            recognizer = null;
        }
        isReady = false;
    }
}
