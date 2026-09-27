package com.simon.voiceime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores only committed adjacent word pairs and counts in the app-private files directory. */
final class ZhuyinAssociationHistory {
    private final File file;
    private final Map<String, Long> counts = new HashMap<>();
    ZhuyinAssociationHistory(File file) { this.file = file; load(); }
    synchronized void record(String previous, String next) {
        if (previous == null || previous.isEmpty() || next == null || next.isEmpty()) return;
        String key = previous + "\t" + next;
        counts.put(key, counts.getOrDefault(key, 0L) + 1L);
        persist();
    }
    synchronized List<String> nextWords(String previous) {
        List<Map.Entry<String,Long>> found = new ArrayList<>();
        for (Map.Entry<String,Long> e : counts.entrySet()) if (e.getKey().startsWith(previous + "\t")) found.add(e);
        found.sort(Comparator.<Map.Entry<String,Long>>comparingLong(Map.Entry::getValue).reversed().thenComparing(e -> e.getKey().substring(previous.length()+1)));
        List<String> words = new ArrayList<>();
        for (Map.Entry<String,Long> e : found) words.add(e.getKey().substring(previous.length()+1));
        return words;
    }
    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line; while ((line = in.readLine()) != null) {
                String[] parts = line.split("\\t", 3);
                if (parts.length == 3) counts.put(parts[0] + "\t" + parts[1], Long.parseLong(parts[2]));
            }
        } catch (Exception ignored) { counts.clear(); }
    }
    private void persist() {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            for (Map.Entry<String,Long> e : counts.entrySet()) out.write(e.getKey() + "\t" + e.getValue() + "\n");
        } catch (Exception ignored) { temp.delete(); return; }
        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file); }
    }
}
