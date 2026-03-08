package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 英文詞彙本地映射 v3.3
 *
 * SenseVoice 辨識中文時，英文單字常被聽成中文諧音。
 * 此類在辨識結果送出到伺服器之前，先做第一道本地替換。
 * 伺服器端的 _PHONETIC_TO_ENGLISH 是第二道防線。
 *
 * 支援：
 * - 內建映射表（硬編碼常見詞彙）
 * - 使用者自訂映射（SharedPreferences + 檔案備份）
 */
public class EnglishMapper {

    private static final String TAG = "EnglishMapper";
    private static final String PREFS_NAME = "simon_ime_english_mapper";
    private static final String KEY_CUSTOM = "custom_mappings";
    private static final String BACKUP_FILENAME = "english_mapper_backup.json";

    // 內建映射表：中文諧音 → 英文
    private static final Map<String, String> BUILTIN = new HashMap<>();
    static {
        // === 品牌/產品 ===
        BUILTIN.put("校門", "Gemini");
        BUILTIN.put("捷門你", "Gemini");
        BUILTIN.put("潔門你", "Gemini");
        BUILTIN.put("傑門你", "Gemini");
        BUILTIN.put("扣的", "Claude");
        BUILTIN.put("可勞的", "Claude");
        BUILTIN.put("克勞德", "Claude");
        BUILTIN.put("克勞的", "Claude");
        BUILTIN.put("歐噴", "Open");
        BUILTIN.put("歐噴a愛", "OpenAI");
        BUILTIN.put("歐噴AI", "OpenAI");
        BUILTIN.put("查特GPT", "ChatGPT");
        BUILTIN.put("查特吉皮踢", "ChatGPT");
        BUILTIN.put("安卓", "Android");
        BUILTIN.put("蘋果", "Apple");
        BUILTIN.put("谷歌", "Google");
        BUILTIN.put("微軟", "Microsoft");

        // === 技術詞彙 ===
        BUILTIN.put("a劈愛", "API");
        BUILTIN.put("挨批愛", "API");
        BUILTIN.put("a批i", "API");
        BUILTIN.put("AP I", "API");
        BUILTIN.put("外斯批", "Whisper");
        BUILTIN.put("喂斯帕", "Whisper");
        BUILTIN.put("威斯帕", "Whisper");
        BUILTIN.put("迷你麥克斯", "MiniMax");
        BUILTIN.put("AG卷", "Agent");
        BUILTIN.put("ag卷", "Agent");
        BUILTIN.put("愛卷", "Agent");
        BUILTIN.put("艾真特", "Agent");
        BUILTIN.put("LL M", "LLM");
        BUILTIN.put("LLM", "LLM");
        BUILTIN.put("GPT", "GPT");
        BUILTIN.put("吉皮踢", "GPT");
        BUILTIN.put("拖肯", "token");
        BUILTIN.put("拓肯", "token");
        BUILTIN.put("普朗普特", "prompt");
        BUILTIN.put("普龍特", "prompt");
        BUILTIN.put("模嗯", "model");
        BUILTIN.put("嵌入", "embedding");
        BUILTIN.put("馬斯", "MARS");
        BUILTIN.put("塞門", "Cymon");
        BUILTIN.put("森斯沃斯", "SenseVoice");

        // === 程式/開發 ===
        BUILTIN.put("拍森", "Python");
        BUILTIN.put("派森", "Python");
        BUILTIN.put("爪哇", "Java");
        BUILTIN.put("賈瓦", "Java");
        BUILTIN.put("踢杯思可瑞", "TypeScript");
        BUILTIN.put("吉特", "Git");
        BUILTIN.put("吉特哈布", "GitHub");
        BUILTIN.put("踢杯", "Type");

        // === 日常/通用 ===
        BUILTIN.put("ok", "OK");
        BUILTIN.put("歐kei", "OK");
        BUILTIN.put("塔斯克", "task");
        BUILTIN.put("得把可", "debug");
        BUILTIN.put("得爸可", "debug");
    }

    private final Context context;
    private final Map<String, String> customMappings;

    public EnglishMapper(Context context) {
        this.context = context;
        this.customMappings = loadCustomMappings();
        if (customMappings.isEmpty()) {
            Map<String, String> restored = loadFromFileBackup();
            if (!restored.isEmpty()) {
                Log.i(TAG, "Restored " + restored.size() + " custom mappings from backup");
                customMappings.putAll(restored);
                saveCustomMappings();
            }
        }
    }

    /**
     * 對辨識結果做英文映射替換。
     * 先查自訂映射（優先），再查內建映射。
     */
    public String apply(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;

        // 自訂映射優先
        for (Map.Entry<String, String> entry : customMappings.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        // 內建映射
        for (Map.Entry<String, String> entry : BUILTIN.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        if (!result.equals(text)) {
            Log.d(TAG, "Mapped: '" + text + "' -> '" + result + "'");
        }

        return result;
    }

    /**
     * 新增自訂映射
     */
    public void addMapping(String phonetic, String english) {
        customMappings.put(phonetic, english);
        saveCustomMappings();
    }

    /**
     * 移除自訂映射
     */
    public void removeMapping(String phonetic) {
        customMappings.remove(phonetic);
        saveCustomMappings();
    }

    /**
     * 取得所有自訂映射
     */
    public Map<String, String> getCustomMappings() {
        return new HashMap<>(customMappings);
    }

    /**
     * 匯出全部映射為 JSON（含內建+自訂）
     */
    public String exportToJson() {
        JSONObject json = new JSONObject();
        try {
            JSONObject builtinJson = new JSONObject();
            for (Map.Entry<String, String> e : BUILTIN.entrySet()) {
                builtinJson.put(e.getKey(), e.getValue());
            }
            json.put("builtin", builtinJson);

            JSONObject customJson = new JSONObject();
            for (Map.Entry<String, String> e : customMappings.entrySet()) {
                customJson.put(e.getKey(), e.getValue());
            }
            json.put("custom", customJson);
        } catch (JSONException e) {
            Log.e(TAG, "Export error", e);
        }
        return json.toString();
    }

    /**
     * 匯入自訂映射（JSON 格式 {"phonetic": "english", ...}）
     */
    public boolean importCustomMappings(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                customMappings.put(key, obj.getString(key));
            }
            saveCustomMappings();
            return true;
        } catch (JSONException e) {
            Log.e(TAG, "Import error", e);
            return false;
        }
    }

    // ==================== Persistence ====================

    private void saveCustomMappings() {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, String> e : customMappings.entrySet()) {
                json.put(e.getKey(), e.getValue());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Save error", e);
        }
        String jsonStr = json.toString();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CUSTOM, jsonStr)
                .apply();
        saveToFileBackup(jsonStr);
    }

    private Map<String, String> loadCustomMappings() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CUSTOM, "{}");
        return parseJson(json);
    }

    private void saveToFileBackup(String json) {
        try {
            File backupDir = new File(context.getExternalFilesDir(null), "backup");
            if (!backupDir.exists()) backupDir.mkdirs();
            File backup = new File(backupDir, BACKUP_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(backup)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save backup", e);
        }
    }

    private Map<String, String> loadFromFileBackup() {
        File backup = new File(new File(context.getExternalFilesDir(null), "backup"), BACKUP_FILENAME);
        if (!backup.exists()) return new HashMap<>();
        try (FileInputStream fis = new FileInputStream(backup)) {
            byte[] data = new byte[(int) backup.length()];
            fis.read(data);
            return parseJson(new String(data, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "Failed to load backup", e);
            return new HashMap<>();
        }
    }

    private Map<String, String> parseJson(String json) {
        Map<String, String> map = new HashMap<>();
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, obj.getString(key));
            }
        } catch (JSONException e) {
            // ignore
        }
        return map;
    }
}
