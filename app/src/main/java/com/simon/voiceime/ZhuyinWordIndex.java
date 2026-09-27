package com.simon.voiceime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Build-generated initial-symbol dictionary; source entries are never handwritten here. */
final class ZhuyinWordIndex {
    static final class Entry {
        final String key, word, pronunciation;
        final long frequency;
        final boolean personal;
        Entry(String key, String word, String pronunciation, long frequency, boolean personal) {
            this.key = key; this.word = word; this.pronunciation = pronunciation;
            this.frequency = frequency; this.personal = personal;
        }
    }
    private final List<Entry> entries;
    private final SQLiteDatabase database;
    private final File personalFile;
    private final LinkedHashMap<String,Entry> personalEntries = new LinkedHashMap<>();
    private ZhuyinWordIndex(List<Entry> entries) { this.entries = entries; this.database = null; this.personalFile = null; }
    private ZhuyinWordIndex(SQLiteDatabase database, File personalFile) { this.entries = Collections.emptyList(); this.database = database; this.personalFile = personalFile; loadPersonal(); }

    static ZhuyinWordIndex open(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "zhuyin_initials.db");
        if (!file.isFile() || file.length() == 0) {
            try (InputStream in = context.getAssets().open("zhuyin_initials.db");
                 FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
        return new ZhuyinWordIndex(SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY),
                new File(context.getFilesDir(), "zhuyin_personal_initials.tsv"));
    }
    boolean hasPrefix(String key) {
        for (Entry e : personalEntries.values()) if (e.key.startsWith(key)) return true;
        if (database == null) for (Entry e : entries) if (e.key.startsWith(key)) return true;
        if (database == null) return false;
        try (Cursor c = database.rawQuery("SELECT 1 FROM words WHERE initial_key LIKE ? LIMIT 1", new String[]{key + "%"})) { return c.moveToFirst(); }
    }
    boolean isCompleteSyllable(String value) {
        if (database == null) return "ㄓㄨ".equals(value) || "ㄋㄧ".equals(value) || "ㄈㄚ".equals(value) || "ㄌㄩ".equals(value);
        try (Cursor c = database.rawQuery("SELECT 1 FROM syllables WHERE spelling=? LIMIT 1", new String[]{value})) { return c.moveToFirst(); }
    }
    List<Entry> lookup(String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : personalEntries.values()) if (e.key.startsWith(key)) out.add(e);
        if (database == null) {
            for (Entry e : entries) if (e.key.startsWith(key)) out.add(e);
            rankAndDeduplicate(out, key);
            return out;
        }
        int inputSymbols = key.codePointCount(0, key.length());
        try (Cursor c = database.rawQuery("SELECT initial_key,word,pronunciation,frequency,personal FROM words WHERE initial_key LIKE ? ORDER BY CASE WHEN length(word)=? THEN 0 ELSE 1 END, personal DESC, frequency DESC LIMIT 100", new String[]{key + "%", Integer.toString(inputSymbols)})) {
            while (c.moveToNext()) {
                Entry entry = new Entry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0);
                out.add(entry);
            }
        }
        rankAndDeduplicate(out, key);
        return out;
    }
    private static void rankAndDeduplicate(List<Entry> candidates, String key) {
        Map<String, Entry> bestByWord = new LinkedHashMap<>();
        for (Entry candidate : candidates) {
            Entry existing = bestByWord.get(candidate.word);
            if (existing == null || compareRank(candidate, existing, key) < 0)
                bestByWord.put(candidate.word, candidate);
        }
        candidates.clear();
        candidates.addAll(bestByWord.values());
        candidates.sort((a, b) -> compareRank(a, b, key));
    }
    private static int compareRank(Entry a, Entry b, String key) {
        int inputSymbols = key.codePointCount(0, key.length());
        int aLength = a.word.codePointCount(0, a.word.length());
        int bLength = b.word.codePointCount(0, b.word.length());
        int byLengthTier = Boolean.compare(aLength != inputSymbols, bLength != inputSymbols);
        if (byLengthTier != 0) return byLengthTier;
        if (a.personal != b.personal) return a.personal ? -1 : 1;
        return Long.compare(b.frequency, a.frequency);
    }
    List<String> continuations(String prefix) {
        List<String> out = new ArrayList<>();
        if (database == null) {
            for (Entry e : entries) if (e.word.startsWith(prefix) && e.word.length() > prefix.length()) out.add(e.word.substring(prefix.length()));
            return out;
        }
        try (Cursor c = database.rawQuery("SELECT substr(word, ?) FROM words WHERE word LIKE ? AND length(word)>? GROUP BY substr(word, ?) ORDER BY MAX(frequency) DESC LIMIT 50", new String[]{Integer.toString(prefix.length()+1), prefix+"%", Integer.toString(prefix.length()), Integer.toString(prefix.length()+1)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }
    void close() { if (database != null && database.isOpen()) database.close(); }
    synchronized void rememberPersonal(Entry entry) {
        Entry personal = new Entry(entry.key, entry.word, entry.pronunciation, Long.MAX_VALUE, true);
        personalEntries.put(entry.word + "\t" + entry.pronunciation, personal);
        if (personalFile == null) return;
        File temp = new File(personalFile.getParentFile(), personalFile.getName() + ".tmp");
        persistPersonal();
    }
    synchronized void rememberPersonal(List<String[]> phrases) {
        if (phrases == null || phrases.isEmpty()) return;
        for (String[] phrase : phrases) if (phrase != null && phrase.length >= 2) {
            String key = keyFor(phrase[1]);
            if (!phrase[0].isEmpty() && !key.isEmpty())
                personalEntries.put(phrase[0] + "\t" + phrase[1], new Entry(key, phrase[0], phrase[1], Long.MAX_VALUE, true));
        }
        persistPersonal();
    }
    private void persistPersonal() {
        if (personalFile == null) return;
        File temp = new File(personalFile.getParentFile(), personalFile.getName() + ".tmp");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            for (Entry e : personalEntries.values()) out.write(e.key + "\t" + e.word + "\t" + e.pronunciation + "\n");
        } catch (Exception ignored) { temp.delete(); return; }
        if (!temp.renameTo(personalFile)) { personalFile.delete(); temp.renameTo(personalFile); }
    }
    synchronized void rememberPersonal(String word, String pronunciation) {
        String key = keyFor(pronunciation);
        if (word != null && !word.isEmpty() && !key.isEmpty())
            rememberPersonal(new Entry(key, word, pronunciation, Long.MAX_VALUE, true));
    }
    private void loadPersonal() {
        if (personalFile == null || !personalFile.isFile()) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(personalFile), StandardCharsets.UTF_8))) {
            String line; while ((line = in.readLine()) != null) {
                String[] p = line.split("\\t", 3); if (p.length == 3) personalEntries.put(p[1] + "\t" + p[2], new Entry(p[0], p[1], p[2], Long.MAX_VALUE, true));
            }
        } catch (Exception ignored) { personalEntries.clear(); }
    }
    private static String keyFor(String pronunciation) {
        StringBuilder key = new StringBuilder();
        for (String syllable : pronunciation.split("\\s+")) {
            for (int i=0; i<syllable.length(); i++) if ("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ".indexOf(syllable.charAt(i)) >= 0) { key.append(syllable.charAt(i)); break; }
        }
        return key.toString();
    }
    static ZhuyinWordIndex forTesting(Entry... entries) {
        return new ZhuyinWordIndex(Collections.unmodifiableList(Arrays.asList(entries)));
    }
}
