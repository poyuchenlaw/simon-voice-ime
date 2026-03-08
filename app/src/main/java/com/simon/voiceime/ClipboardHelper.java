package com.simon.voiceime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 剪貼簿歷史管理 — 保存最近 50 則剪貼內容
 */
public class ClipboardHelper {

    private static final String TAG = "ClipboardHelper";
    private static final String PREFS_NAME = "simon_ime_clipboard";
    private static final String KEY_HISTORY = "history";
    private static final String BACKUP_FILENAME = "clipboard_backup.json";
    private static final int MAX_ITEMS = 50;

    private final Context context;
    private final List<String> history;

    public ClipboardHelper(Context context) {
        this.context = context;
        this.history = loadHistory();
        if (history.isEmpty()) {
            List<String> restored = loadFromFileBackup();
            if (!restored.isEmpty()) {
                Log.i(TAG, "Restored " + restored.size() + " clipboard items from file backup");
                history.addAll(restored);
                saveHistory();
            }
        }
        listenClipboard();
    }

    private void listenClipboard() {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.addPrimaryClipChangedListener(() -> {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null && text.length() > 0) {
                        addToHistory(text.toString());
                    }
                }
            });
        }
    }

    public void addToHistory(String text) {
        if (text == null || text.trim().isEmpty()) return;
        // Remove if already exists (move to top)
        history.remove(text);
        history.add(0, text);
        // Trim to max
        while (history.size() > MAX_ITEMS) {
            history.remove(history.size() - 1);
        }
        saveHistory();
    }

    public List<String> getHistory() {
        return new ArrayList<>(history);
    }

    public void remove(int index) {
        if (index >= 0 && index < history.size()) {
            history.remove(index);
            saveHistory();
        }
    }

    public void clear() {
        history.clear();
        saveHistory();
    }

    private List<String> loadHistory() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (JSONException e) {
            // ignore
        }
        return list;
    }

    private void saveHistory() {
        JSONArray arr = new JSONArray();
        for (String s : history) {
            arr.put(s);
        }
        String json = arr.toString();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, json)
                .apply();
        saveToFileBackup(json);
    }

    private void saveToFileBackup(String json) {
        // Internal backup (original location)
        try {
            File backup = new File(context.getFilesDir(), BACKUP_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(backup)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save internal clipboard backup", e);
        }
        // External backup (survives APK reinstall if not uninstalled)
        try {
            File extDir = new File(context.getExternalFilesDir(null), "backup");
            if (!extDir.exists()) extDir.mkdirs();
            File extBackup = new File(extDir, BACKUP_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(extBackup)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save external clipboard backup", e);
        }
    }

    private List<String> loadFromFileBackup() {
        // Try internal first
        File backup = new File(context.getFilesDir(), BACKUP_FILENAME);
        if (backup.exists()) {
            List<String> result = loadBackupFile(backup);
            if (!result.isEmpty()) return result;
        }
        // Try external backup
        File extBackup = new File(new File(context.getExternalFilesDir(null), "backup"), BACKUP_FILENAME);
        if (extBackup.exists()) {
            return loadBackupFile(extBackup);
        }
        return new ArrayList<>();
    }

    private List<String> loadBackupFile(File backup) {
        try (FileInputStream fis = new FileInputStream(backup)) {
            byte[] data = new byte[(int) backup.length()];
            fis.read(data);
            String json = new String(data, StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
            return list;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load clipboard backup: " + backup.getPath(), e);
            return new ArrayList<>();
        }
    }
}
