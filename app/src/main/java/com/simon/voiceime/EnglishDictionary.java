package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * EnglishDictionary — on-device English predictive input + auto-learn.
 *
 * Base dictionary: loaded from res/raw/en_words.txt (one word per line, optionally "word\tfreq").
 * Learned dictionary: persisted Map<String,Integer> (word→count) via SharedPreferences + file backup.
 *
 * suggest(prefix, limit): prefix-insensitive match, ranked by learnedCount*BOOST + baseFreq.
 * learn(word): increment learnedCount; persist (simple, no debounce needed for low-frequency calls).
 * clearLearned(): wipes the learned dictionary.
 *
 * Fail-open: any load/parse error → empty base, never throw.
 */
public class EnglishDictionary {

    private static final String TAG = "EnglishDict";
    private static final String PREFS_NAME = "simon_ime_en_learn";
    private static final String KEY_LEARNED = "learned";
    private static final String BACKUP_FILENAME = "en_learned_backup.json";
    private static final int BOOST = 200;  // learned words get BOOST * count bonus over base freq

    private final Context context;
    // base word → freq (loaded from asset)
    private final Map<String, Integer> baseDict = new HashMap<>();
    // learned word → count (persisted)
    private final Map<String, Integer> learnedDict = new HashMap<>();
    private volatile boolean loaded = false;

    public EnglishDictionary(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Load base dictionary from assets + restore learned dict from prefs.
     * Call on a background thread (does file I/O).
     */
    public void loadAsync() {
        try {
            loadBaseDict();
        } catch (Exception e) {
            Log.w(TAG, "Base dict load failed (fail-open)", e);
        }
        try {
            loadLearnedDict();
        } catch (Exception e) {
            Log.w(TAG, "Learned dict load failed (fail-open)", e);
        }
        loaded = true;
        Log.i(TAG, "EnglishDictionary ready: base=" + baseDict.size() + " learned=" + learnedDict.size());
    }

    /**
     * Returns up to `limit` suggestions for the given prefix.
     * If prefix is empty, returns the top `limit` learned words by count (or nothing if learned empty).
     * Case-insensitive prefix match. Returns lowercased words from dict.
     */
    public List<String> suggest(String prefix, int limit) {
        if (!loaded) return Collections.emptyList();
        try {
            String lower = prefix == null ? "" : prefix.toLowerCase();
            List<String> matches = new ArrayList<>();

            if (lower.isEmpty()) {
                // Return top learned words only
                List<Map.Entry<String, Integer>> entries = new ArrayList<>(learnedDict.entrySet());
                Collections.sort(entries, (a, b) -> b.getValue() - a.getValue());
                for (Map.Entry<String, Integer> e : entries) {
                    matches.add(e.getKey());
                    if (matches.size() >= limit) break;
                }
                return matches;
            }

            // Collect all candidates with scores
            // score = learnedCount*BOOST + baseFreq
            Map<String, Integer> scoreMap = new HashMap<>();

            for (Map.Entry<String, Integer> e : baseDict.entrySet()) {
                String word = e.getKey();
                if (word.startsWith(lower) && !word.equals(lower)) {
                    int score = e.getValue();
                    Integer lc = learnedDict.get(word);
                    if (lc != null) score += lc * BOOST;
                    scoreMap.put(word, score);
                }
            }
            // Also check learned words not in base
            for (Map.Entry<String, Integer> e : learnedDict.entrySet()) {
                String word = e.getKey();
                if (word.startsWith(lower) && !word.equals(lower) && !scoreMap.containsKey(word)) {
                    scoreMap.put(word, e.getValue() * BOOST);
                }
            }

            List<Map.Entry<String, Integer>> candidates = new ArrayList<>(scoreMap.entrySet());
            Collections.sort(candidates, (a, b) -> {
                int scoreDiff = b.getValue() - a.getValue();
                if (scoreDiff != 0) return scoreDiff;
                // Shorter-first tiebreak
                return a.getKey().length() - b.getKey().length();
            });

            for (Map.Entry<String, Integer> e : candidates) {
                matches.add(e.getKey());
                if (matches.size() >= limit) break;
            }
            return matches;
        } catch (Exception e) {
            Log.w(TAG, "suggest() failed (fail-open)", e);
            return Collections.emptyList();
        }
    }

    /**
     * Learn a word: increment its learnedCount and persist.
     * Silently ignores invalid words (non-letters, length < 2 or > 40, all-caps single token).
     */
    public void learn(String word) {
        if (!loaded) return;
        if (!isValidLearnWord(word)) return;
        try {
            String lower = word.toLowerCase();
            int count = learnedDict.containsKey(lower) ? learnedDict.get(lower) : 0;
            learnedDict.put(lower, count + 1);
            persistLearnedDict();
        } catch (Exception e) {
            Log.w(TAG, "learn() failed (fail-open)", e);
        }
    }

    /**
     * Clear all learned words (for settings UI).
     */
    public void clearLearned() {
        try {
            learnedDict.clear();
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_LEARNED).apply();
            // Delete backup file
            File backup = getBackupFile();
            if (backup.exists()) backup.delete();
            Log.i(TAG, "Learned dict cleared");
        } catch (Exception e) {
            Log.w(TAG, "clearLearned() failed", e);
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ==================== Internal ====================

    private boolean isValidLearnWord(String word) {
        if (word == null || word.length() < 2 || word.length() > 40) return false;
        for (char c : word.toCharArray()) {
            if (!Character.isLetter(c)) return false;
        }
        // Skip single all-caps tokens like "OK", "API" (they're already in base)
        // Actually we DO want to learn them; just skip if all-caps AND length==1 (impossible since len>=2)
        // But allow "API", "LLM" etc. — those are fine to learn
        return true;
    }

    private void loadBaseDict() throws IOException {
        InputStream is = context.getResources().openRawResource(R.raw.en_words);
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int tabIdx = line.indexOf('\t');
            String word;
            int freq;
            if (tabIdx >= 0) {
                word = line.substring(0, tabIdx).trim().toLowerCase();
                try {
                    freq = Integer.parseInt(line.substring(tabIdx + 1).trim());
                } catch (NumberFormatException e) {
                    freq = 1;
                }
            } else {
                word = line.toLowerCase();
                freq = 1;
            }
            if (!word.isEmpty() && word.length() <= 40) {
                // Keep highest freq if word appears multiple times
                Integer existing = baseDict.get(word);
                if (existing == null || freq > existing) {
                    baseDict.put(word, freq);
                }
            }
        }
        reader.close();
    }

    private void loadLearnedDict() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_LEARNED, null);
        if (json == null) {
            // Try file backup
            json = loadFromFileBackup();
        }
        if (json != null) {
            parseLearnedJson(json);
        }
    }

    private void parseLearnedJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String word = keys.next();
                int count = obj.optInt(word, 1);
                learnedDict.put(word, count);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse learned json", e);
        }
    }

    private void persistLearnedDict() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Integer> e : learnedDict.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            String json = obj.toString();
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_LEARNED, json).apply();
            saveToFileBackup(json);
        } catch (Exception e) {
            Log.w(TAG, "persistLearnedDict() failed", e);
        }
    }

    private void saveToFileBackup(String json) {
        try {
            File backup = getBackupFile();
            backup.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(backup)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to save learned backup", e);
        }
    }

    private String loadFromFileBackup() {
        try {
            File backup = getBackupFile();
            if (!backup.exists()) return null;
            try (FileInputStream fis = new FileInputStream(backup)) {
                byte[] data = new byte[(int) backup.length()];
                fis.read(data);
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to load learned backup", e);
            return null;
        }
    }

    private File getBackupFile() {
        File backupDir = new File(context.getExternalFilesDir(null), "backup");
        return new File(backupDir, BACKUP_FILENAME);
    }
}
