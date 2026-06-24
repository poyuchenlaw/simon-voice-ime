package com.simon.voiceime.correct;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java OpenCC s2tw (Simplified -> Traditional, Taiwan glyph standard).
 *
 * <p>Mirrors the conversion_chain of OpenCC's bundled {@code s2tw.json}:
 * <ol>
 *   <li>Group dict {STPhrases, STCharacters} — phrase-first (longest match wins).</li>
 *   <li>TWVariants — character-level Taiwan glyph normalisation.</li>
 * </ol>
 *
 * <p><b>NEVER s2twp.</b> s2twp adds the phrase-variant chain (TWPhrases) which over-converts
 * legal/business terms (程序→程式, 聲明→宣告, 審核→稽核, 係爭→系爭). This class intentionally
 * does NOT bundle TWPhrases — only the glyph chain — exactly matching s2tw.
 *
 * <p>The conversion is deterministic greedy longest-match-first scanning, which produces output
 * identical to OpenCC's mmseg-based s2tw for the (overwhelmingly common) unambiguous
 * simplified→traditional case. No native library — pure JVM, unit-testable.
 *
 * <p>Dict files are tab-separated: {@code <source>\t<target> [alt...]}; the first whitespace
 * token after the tab is the primary target (matching the server's loader convention).
 */
public final class OpenCcS2tw {

    private final Map<String, String> s2t;     // STPhrases ∪ STCharacters (longest match first)
    private final Map<String, String> twVar;   // TWVariants (single-char glyphs)
    private final int maxKeyLen;

    private OpenCcS2tw(Map<String, String> s2t, Map<String, String> twVar, int maxKeyLen) {
        this.s2t = s2t;
        this.twVar = twVar;
        this.maxKeyLen = maxKeyLen;
    }

    /**
     * Build from three OpenCC dict streams. The streams are fully consumed and closed by caller.
     *
     * @param stPhrases    STPhrases.txt   (phrases, ~49k lines)
     * @param stCharacters STCharacters.txt (single chars, ~4k lines)
     * @param twVariants   TWVariants.txt  (TW glyph variants, ~39 lines)
     */
    public static OpenCcS2tw load(InputStream stPhrases, InputStream stCharacters, InputStream twVariants)
            throws IOException {
        Map<String, String> s2t = new HashMap<>(70000);
        int[] maxLen = {1};
        // Load phrases first then characters; on key collision, keep the phrase (already present).
        loadDict(stPhrases, s2t, maxLen);
        loadDict(stCharacters, s2t, maxLen);

        Map<String, String> twVar = new HashMap<>(64);
        loadDict(twVariants, twVar, new int[]{1});

        return new OpenCcS2tw(s2t, twVar, maxLen[0]);
    }

    private static void loadDict(InputStream in, Map<String, String> into, int[] maxLen) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String rest = line.substring(tab + 1);
                // primary target = first whitespace-delimited token
                int sp = indexOfWhitespace(rest);
                String val = sp < 0 ? rest : rest.substring(0, sp);
                if (key.isEmpty() || val.isEmpty() || key.equals(val)) continue;
                if (!into.containsKey(key)) {  // phrase (loaded first) wins over char
                    into.put(key, val);
                    if (key.length() > maxLen[0]) maxLen[0] = key.length();
                }
            }
        }
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    /** Convert simplified Chinese text to Taiwan-standard traditional (s2tw, glyph only). */
    public String convert(String text) {
        if (text == null || text.isEmpty()) return text;
        // Pass 1: greedy longest-match over {STPhrases ∪ STCharacters}.
        StringBuilder out = new StringBuilder(text.length() + 8);
        int n = text.length();
        int i = 0;
        while (i < n) {
            int hi = Math.min(maxKeyLen, n - i);
            boolean matched = false;
            for (int len = hi; len >= 1; len--) {
                String sub = text.substring(i, i + len);
                String rep = s2t.get(sub);
                if (rep != null) {
                    out.append(rep);
                    i += len;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                out.append(text.charAt(i));
                i++;
            }
        }
        // Pass 2: TWVariants, character by character.
        if (!twVar.isEmpty()) {
            String stage1 = out.toString();
            StringBuilder out2 = new StringBuilder(stage1.length());
            for (int k = 0; k < stage1.length(); k++) {
                String ch = String.valueOf(stage1.charAt(k));
                String rep = twVar.get(ch);
                out2.append(rep != null ? rep : ch);
            }
            return out2.toString();
        }
        return out.toString();
    }
}
