package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
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
    enum Provenance {
        PUBLIC,
        PERSONAL_PUBLIC,
        REMOTE_PRIVATE,
        LEARNED
    }
    static final class Entry {
        final String key, word, pronunciation;
        final long frequency;
        final boolean personal;
        final Provenance provenance;
        Entry(String key, String word, String pronunciation, long frequency, boolean personal) {
            // This constructor is retained for the existing public-index tests.
            // Its historical flag meant "bundled/installed", not "Simon-specific".
            this(key, word, pronunciation, frequency, Provenance.PUBLIC);
        }
        Entry(String key, String word, String pronunciation, long frequency, Provenance provenance) {
            this.key = key; this.word = word; this.pronunciation = pronunciation;
            this.frequency = frequency; this.provenance = provenance;
            this.personal = provenance != Provenance.PUBLIC;
        }
    }
    private final List<Entry> entries;
    private final SQLiteDatabase database;
    private final File personalFile;
    private final File remoteFile;
    private final LinkedHashMap<String,Entry> personalEntries = new LinkedHashMap<>();
    private final LinkedHashMap<String,Entry> remoteEntries = new LinkedHashMap<>();
    private ZhuyinWordIndex(List<Entry> entries) { this.entries = entries; this.database = null; this.personalFile = null; this.remoteFile = null; }
    private ZhuyinWordIndex(SQLiteDatabase database, File personalFile, File remoteFile) { this.entries = Collections.emptyList(); this.database = database; this.personalFile = personalFile; this.remoteFile = remoteFile; loadPersonal(); loadRemote(); publishRimeVocabulary(); }

    static ZhuyinWordIndex open(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "zhuyin_initials.db");
        SharedPreferences prefs = context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE);
        if (!file.isFile() || file.length() == 0 || (prefs.getBoolean("auto_vocab_enabled", true) && assetVersionChanged(context, file))) {
            File temp = new File(context.getFilesDir(), "zhuyin_initials.db.tmp");
            try (InputStream in = context.getAssets().open("zhuyin_initials.db");
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            if (!temp.renameTo(file)) { temp.delete(); throw new java.io.IOException("cannot install public vocabulary"); }
        }
        File remote = prefs.getBoolean("auto_vocab_enabled", true)
                ? new File(context.getFilesDir(), "zhuyin_remote_private.tsv") : null;
        return new ZhuyinWordIndex(SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY),
                new File(context.getFilesDir(), "zhuyin_personal_initials.tsv"), remote);
    }
    private static boolean assetVersionChanged(Context context, File installed) {
        File assetCopy = new File(context.getCacheDir(), "zhuyin_initials_asset_check.db");
        try (InputStream in = context.getAssets().open("zhuyin_initials.db"); FileOutputStream out = new FileOutputStream(assetCopy)) {
            byte[] buffer = new byte[8192]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (Exception ignored) { return false; }
        try { return !metadata(installed, "public_vocab_version").equals(metadata(assetCopy, "public_vocab_version")); }
        finally { assetCopy.delete(); }
    }
    private static String metadata(File file, String key) {
        SQLiteDatabase db = null;
        try { db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key=?", new String[]{key})) { return c.moveToFirst() ? c.getString(0) : ""; }
        } catch (Exception ignored) { return ""; }
        finally { if (db != null) db.close(); }
    }
    synchronized boolean isInstalledWord(String word){
        for(Entry e:personalEntries.values())if(e.word.equals(word))return true;
        for(Entry e:remoteEntries.values())if(e.word.equals(word))return true;
        for(Entry e:entries)if(e.personal&&e.word.equals(word))return true;
        return false;
    }
    boolean hasPrefix(String key) {
        for (Entry e : personalEntries.values()) if (e.key.startsWith(key)) return true;
        for (Entry e : remoteEntries.values()) if (e.key.startsWith(key)) return true;
        if (database == null) for (Entry e : entries) if (e.key.startsWith(key)) return true;
        if (database == null) return false;
        try (Cursor c = database.rawQuery("SELECT 1 FROM words WHERE initial_key LIKE ? LIMIT 1", new String[]{key + "%"})) { return c.moveToFirst(); }
    }
    boolean isCompleteSyllable(String value) {
        if (database == null) return "ㄓㄨ".equals(value) || "ㄋㄧ".equals(value) || "ㄈㄚ".equals(value) || "ㄌㄩ".equals(value);
        try (Cursor c = database.rawQuery("SELECT 1 FROM syllables WHERE spelling=? LIMIT 1", new String[]{value})) { return c.moveToFirst(); }
    }
    int[] cursorWordRange(String text,int cursor){
        if(text==null||cursor<=0||cursor>text.length()
                ||cursor<text.length()&&Character.isLowSurrogate(text.charAt(cursor)))return null;
        int left=text.offsetByCodePoints(cursor,-Math.min(8,text.codePointCount(0,cursor)));
        int right=text.offsetByCodePoints(cursor,Math.min(8,text.codePointCount(cursor,text.length())));
        for(int length=4;length>=2;length--){
            // Equal-length matches prefer the nearest start before the caret.
            for(int start=text.offsetByCodePoints(cursor,-1);start>=left;){
                if(text.codePointCount(start,right)>=length){
                    int end=text.offsetByCodePoints(start,length);
                    if(end>=cursor&&!wordEntries(text.substring(start,end)).isEmpty())return new int[]{start,end};
                }
                if(start==left)break;start=text.offsetByCodePoints(start,-1);
            }
        }
        return new int[]{text.offsetByCodePoints(cursor,-Math.min(2,text.codePointCount(0,cursor))),cursor};
    }
    List<Entry> cursorWordCandidates(String text){
        LinkedHashMap<String,Entry> choices=new LinkedHashMap<>();
        for(Entry e:homophoneCandidates(text,false))choices.putIfAbsent(e.word,e);
        for(Entry e:selectionCandidates(text))if(e.word.codePointCount(0,e.word.length())>1)choices.putIfAbsent(e.word,e);
        return new ArrayList<>(choices.values());
    }
    List<Entry> cursorCharacterCandidates(String text){return homophoneCandidates(text,true);}
    List<Entry> homophoneCandidates(String text,boolean character){
        LinkedHashMap<String,Entry> choices=new LinkedHashMap<>();
        for(Entry selected:wordEntries(text)){
            if(selected.pronunciation.isEmpty())continue;
            for(Entry e:personalEntries.values())if(e.pronunciation.equals(selected.pronunciation)&&(e.word.codePointCount(0,e.word.length())==1)==character&&!e.word.equals(text))choices.putIfAbsent(e.word,e);
            for(Entry e:remoteEntries.values())if(e.pronunciation.equals(selected.pronunciation)&&(e.word.codePointCount(0,e.word.length())==1)==character&&!e.word.equals(text))choices.putIfAbsent(e.word,e);
            if(database==null){for(Entry e:entries)if(e.pronunciation.equals(selected.pronunciation)&&(e.word.codePointCount(0,e.word.length())==1)==character&&!e.word.equals(text))choices.putIfAbsent(e.word,e);}
            else try(Cursor c=database.rawQuery("SELECT initial_key,word,pronunciation,frequency,personal FROM words WHERE initial_key=? AND pronunciation=? AND " +(character?"length(word)=1":"length(word)>1")+" ORDER BY frequency DESC LIMIT 50",new String[]{selected.key,selected.pronunciation})){
                while(c.moveToNext()){Entry e=new Entry(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0?Provenance.PERSONAL_PUBLIC:Provenance.PUBLIC);if(!e.word.equals(text))choices.putIfAbsent(e.word,e);}
            }
        }
        return new ArrayList<>(choices.values());
    }
    private List<Entry> wordEntries(String text){
        List<Entry> matches=new ArrayList<>();
        for(Entry e:personalEntries.values())if(e.word.equals(text))matches.add(e);
        for(Entry e:remoteEntries.values())if(e.word.equals(text))matches.add(e);
        if(database==null){for(Entry e:entries)if(e.word.equals(text))matches.add(e);}
        else try(Cursor c=database.rawQuery("SELECT initial_key,word,pronunciation,frequency,personal FROM words WHERE word=? ORDER BY frequency DESC LIMIT 4",new String[]{text})){
            while(c.moveToNext())matches.add(new Entry(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0?Provenance.PERSONAL_PUBLIC:Provenance.PUBLIC));
        }
        return matches;
    }
    List<Entry> selectionCandidates(String text) {
        if(text==null||text.isEmpty()||text.codePointCount(0,text.length())>32)return Collections.emptyList();
        List<Entry> matches=wordEntries(text);
        LinkedHashMap<String,Entry> choices=new LinkedHashMap<>();
        for(Entry selected:matches){
            if(selected.key.isEmpty())continue;
            for(Entry e:lookup(selected.key))if(!e.word.equals(text)&&e.word.codePointCount(0,e.word.length())>1)choices.putIfAbsent(e.word,e);
            String initial=selected.key.substring(0,Character.charCount(selected.key.codePointAt(0)));
            if(database==null){for(Entry e:entries)if(e.key.equals(initial)&&e.word.codePointCount(0,e.word.length())==1&&!e.word.equals(text))choices.putIfAbsent(e.word,e);}
            else try(Cursor c=database.rawQuery("SELECT initial_key,word,pronunciation,frequency,personal FROM words WHERE initial_key=? AND length(word)=1 ORDER BY frequency DESC LIMIT 50",new String[]{initial})){
                while(c.moveToNext()){Entry e=new Entry(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getInt(4)!=0?Provenance.PERSONAL_PUBLIC:Provenance.PUBLIC);if(!e.word.equals(text))choices.putIfAbsent(e.word,e);}
            }
        }
        return new ArrayList<>(choices.values());
    }
    List<Entry> lookup(String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : personalEntries.values()) if (e.key.startsWith(key)) out.add(e);
        for (Entry e : remoteEntries.values()) if (e.key.startsWith(key)) out.add(e);
        if (database == null) {
            for (Entry e : entries) if (e.key.startsWith(key)) out.add(e);
            rankAndDeduplicate(out, key);
            return out;
        }
        int inputSymbols = key.codePointCount(0, key.length());
        try (Cursor c = database.rawQuery("SELECT initial_key,word,pronunciation,frequency,personal FROM words WHERE initial_key LIKE ? ORDER BY CASE WHEN length(word)=? THEN 0 ELSE 1 END, personal DESC, frequency DESC LIMIT 100", new String[]{key + "%", Integer.toString(inputSymbols)})) {
            while (c.moveToNext()) {
                Entry entry = new Entry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
                        c.getInt(4) != 0 ? Provenance.PERSONAL_PUBLIC : Provenance.PUBLIC);
                out.add(entry);
            }
        }
        rankAndDeduplicate(out, key);
        return out;
    }
    private List<Entry> exact(String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : personalEntries.values()) if (e.key.equals(key)) out.add(e);
        for (Entry e : remoteEntries.values()) if (e.key.equals(key)) out.add(e);
        if (database == null) {
            for (Entry e : entries) if (e.key.equals(key)) out.add(e);
        } else try (Cursor c = database.rawQuery("SELECT initial_key,word,pronunciation,frequency,personal FROM words WHERE initial_key=? ORDER BY frequency DESC LIMIT 50", new String[]{key})) {
            while (c.moveToNext()) out.add(new Entry(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),
                    c.getInt(4) != 0 ? Provenance.PERSONAL_PUBLIC : Provenance.PUBLIC));
        }
        return out;
    }
    List<Entry> segmented(String key) {
        List<List<Entry>> paths = new ArrayList<>();
        segment(key, 0, new ArrayList<>(), paths, 5000);
        List<Entry> out = new ArrayList<>();
        for (List<Entry> path : paths) {
            if (path.size() < 2) continue;
            StringBuilder word = new StringBuilder(), pronunciation = new StringBuilder();
            double logFrequency = 0;
            for (Entry e : path) {
                word.append(e.word);
                if (pronunciation.length() > 0) pronunciation.append(' ');
                pronunciation.append(e.pronunciation);
                long boundedFrequency = e.frequency > 1_000_000_000L ? 1L : Math.max(1L, e.frequency);
                logFrequency += Math.log(boundedFrequency);
            }
            long frequency = Math.max(1L, Math.min(Long.MAX_VALUE, Math.round(Math.exp(Math.min(40, logFrequency / path.size())))));
            out.add(new Entry(key, word.toString(), pronunciation.toString(), frequency, false));
        }
        rankAndDeduplicate(out, key);
        if (out.size() > 100) out = new ArrayList<>(out.subList(0, 100));
        return out;
    }
    boolean hasSegmentedPrefix(String key) {
        return hasSegmentedPrefix(key, new java.util.HashSet<>());
    }
    private boolean hasSegmentedPrefix(String key, java.util.Set<String> knownDeadEnds) {
        if (knownDeadEnds.contains(key)) return false;
        for (int split = 2; split < key.length(); split++) {
            if (!exact(key.substring(0, split)).isEmpty()
                    && (hasPrefix(key.substring(split)) || hasSegmentedPrefix(key.substring(split), knownDeadEnds))) return true;
        }
        knownDeadEnds.add(key);
        return false;
    }
    private void segment(String key, int offset, List<Entry> path, List<List<Entry>> out, int limit) {
        if (out.size() >= limit) return;
        if (offset == key.length()) { if (path.size() > 1) out.add(new ArrayList<>(path)); return; }
        for (int end = key.length(); end >= offset + 2; end--) {
            String part = key.substring(offset, end);
            for (Entry e : exact(part)) {
                path.add(e); segment(key, end, path, out, limit); path.remove(path.size() - 1);
                if (out.size() >= limit) return;
            }
        }
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
        int byProvenance = Integer.compare(provenanceRank(a.provenance), provenanceRank(b.provenance));
        if (byProvenance != 0) return byProvenance;
        int aLength = a.word.codePointCount(0, a.word.length());
        int bLength = b.word.codePointCount(0, b.word.length());
        int byLength = Boolean.compare(aLength != inputSymbols, bLength != inputSymbols);
        if (byLength != 0) return byLength;
        return Long.compare(b.frequency, a.frequency);
    }
    private static int provenanceRank(Provenance provenance) {
        switch (provenance) {
            case LEARNED: return 0;
            case REMOTE_PRIVATE: return 1;
            case PERSONAL_PUBLIC: return 2;
            default: return 3;
        }
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
        Entry personal = new Entry(entry.key, entry.word, entry.pronunciation, Long.MAX_VALUE, Provenance.LEARNED);
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
                personalEntries.put(phrase[0] + "\t" + phrase[1], new Entry(key, phrase[0], phrase[1], Long.MAX_VALUE, Provenance.LEARNED));
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
        publishRimeVocabulary();
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
                String[] p = line.split("\\t", 3); if (p.length == 3) personalEntries.put(p[1] + "\t" + p[2], new Entry(p[0], p[1], p[2], Long.MAX_VALUE, Provenance.LEARNED));
            }
        } catch (Exception ignored) { personalEntries.clear(); }
    }
    synchronized void reloadRemote() { loadRemote(); publishRimeVocabulary(); }
    private void loadRemote() {
        remoteEntries.clear();
        if (remoteFile == null || !remoteFile.isFile() || remoteFile.length() > 512 * 1024) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(remoteFile), StandardCharsets.UTF_8))) {
            String line; int count = 0;
            while ((line = in.readLine()) != null && count < 2000) {
                String[] p = line.split("\\t", 3);
                String key = p.length == 3 ? p[0] : "";
                if (p.length == 3 && !key.isEmpty() && !p[1].isEmpty()) {
                    remoteEntries.put(p[1] + "\\t" + p[2], new Entry(key, p[1], p[2], Long.MAX_VALUE - 1, Provenance.REMOTE_PRIVATE));
                    count++;
                }
            }
        } catch (Exception ignored) { remoteEntries.clear(); }
    }
    private void publishRimeVocabulary() {
        if (personalFile == null) return;
        List<String[]> installed = new ArrayList<>();
        for (Entry e : personalEntries.values()) installed.add(new String[]{e.word, e.pronunciation});
        for (Entry e : remoteEntries.values()) installed.add(new String[]{e.word, e.pronunciation});
        if (database != null) try (Cursor c = database.rawQuery("SELECT word,pronunciation FROM words WHERE personal=1", null)) {
            while(c.moveToNext()) installed.add(new String[]{c.getString(0), c.getString(1)});
        }
        try { RimeVocabularyInstaller.install(new File(personalFile.getParentFile(), "rime/user"), installed); }
        catch (java.io.IOException error) { android.util.Log.w("ZhuyinWordIndex", "Rime vocabulary install failed; retry on next refresh", error); }
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
