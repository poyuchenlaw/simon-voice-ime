package com.simon.voiceime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, on-device Bopomofo prefix candidates.
 *
 * <p>This is deliberately a System-1 path: it never calls a network service or
 * a semantic model.  A single leading Bopomofo symbol can therefore surface a
 * high-frequency personal phrase immediately; further symbols only narrow the
 * same local index.</p>
 */
public final class BopomofoDictionary {
    private static final class Entry {
        final String code;
        final String text;

        Entry(String code, String text) {
            this.code = code;
            this.text = text;
        }
    }

    // Ordered: personal and confirmed legal terms precede generic candidates.
    // Only observed/user-confirmed phrases belong here; do not invent homophones.
    private static final List<Entry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new Entry("ㄔㄣˊ", "陳柏諭"),
            new Entry("ㄔㄣˊㄅㄠˋㄓㄨㄤˋ", "陳報狀"),
            new Entry("ㄕㄨㄐㄧㄍㄨㄢ", "書記官"),
            new Entry("ㄅㄧㄢˋㄕˋ", "辨識"),
            new Entry("ㄩㄥˇㄅㄠˇㄑㄧㄥㄔㄨㄣ", "永保青春"),
            new Entry("ㄔㄣˊ", "陳"),
            new Entry("ㄕㄨ", "書"),
            new Entry("ㄅㄧㄢ", "編"),
            new Entry("ㄩㄥ", "用")
    ));
    private final Map<String, List<String>> confirmed = new LinkedHashMap<>();

    /**
     * Adds a manually selected candidate as an explicit user-confirmed signal.
     * The caller must persist it if it should survive a process restart.
     */
    public void recordConfirmed(String code, String text) {
        if (code == null || code.isEmpty() || text == null || text.trim().isEmpty()) return;
        List<String> values = confirmed.get(code);
        if (values == null) {
            values = new ArrayList<>();
            confirmed.put(code, values);
        }
        values.remove(text);
        values.add(0, text);
    }

    /** Returns unique candidates whose full Bopomofo code starts with prefix. */
    public List<String> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isEmpty() || limit <= 0) return Collections.emptyList();
        String normalizedPrefix = withoutToneMarks(prefix);
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : confirmed.entrySet()) {
            if (withoutToneMarks(entry.getKey()).startsWith(normalizedPrefix)) {
                for (String text : entry.getValue()) {
                    result.put(text, Boolean.TRUE);
                    if (result.size() >= limit) return new ArrayList<>(result.keySet());
                }
            }
        }
        for (Entry entry : ENTRIES) {
            if (withoutToneMarks(entry.code).startsWith(normalizedPrefix)) {
                result.put(entry.text, Boolean.TRUE);
                if (result.size() >= limit) break;
            }
        }
        return new ArrayList<>(result.keySet());
    }

    /** Tone marks are optional during entry and must not break a later syllable prefix. */
    private static String withoutToneMarks(String code) {
        return code.replace("ˊ", "")
                .replace("ˇ", "")
                .replace("ˋ", "")
                .replace("˙", "");
    }
}
