package com.simon.voiceime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * 剪貼簿歷史管理 — 保存最近 50 則剪貼內容
 */
public class ClipboardHelper {

    private static final String PREFS_NAME = "simon_ime_clipboard";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_ITEMS = 50;

    private final Context context;
    private final List<String> history;

    public ClipboardHelper(Context context) {
        this.context = context;
        this.history = loadHistory();
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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, arr.toString())
                .apply();
    }
}
