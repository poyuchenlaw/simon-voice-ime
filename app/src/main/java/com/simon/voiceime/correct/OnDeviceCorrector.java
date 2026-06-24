package com.simon.voiceime.correct;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * On-device deterministic text corrector for the APPEND fast path.
 *
 * <p>PURE JAVA — no Android dependencies — so the whole pipeline (steps 1-4 + the step-6
 * 改字-guard) runs on the JVM and is unit-testable. The punctuation step (step 5, ONNX/Android)
 * is injected behind the {@link Punctuator} interface so tests can stub it.
 *
 * <p>Pipeline (order matters — mirrors the server's deterministic prefix in
 * {@code main.py:/v1/process-text} BEFORE any LLM):
 * <ol>
 *   <li><b>Step 1</b> paraformer legal homophone correction — on SIMPLIFIED text, before conversion
 *       (ported from {@code paraformer_legal_hotwords.PARAFORMER_LEGAL_CORRECTIONS}).</li>
 *   <li><b>Step 2</b> remove fillers (呃/嗯/額…) — ported verbatim from {@code _remove_fillers}.</li>
 *   <li><b>Step 3</b> 簡→繁 OpenCC s2tw (glyph only, NEVER s2twp) + curated legal overrides
 *       applied AFTER s2tw (ported from {@code _apply_s2t} + {@code opencc_legal_overrides.txt});
 *       also 臺→台 like the server.</li>
 *   <li><b>Step 4</b> legal_dictionary exact-match corrections (mishear→correct) with protected
 *       terms never touched.</li>
 *   <li><b>Step 5</b> punctuation via {@link Punctuator} (injected).</li>
 *   <li><b>Step 6</b> 改字 GUARD: strip ALL punctuation from the punctuated output and compare
 *       char-by-char to the step-4 text. If they differ, DISCARD punctuation and use step-4 text.
 *       Punctuation can ONLY add punctuation, never alter characters.</li>
 * </ol>
 *
 * <p>LANDMINES honoured: only EXACT-MATCH curated replacements (no fuzzy/pinyin/windowed
 * matching), dicts are read-only (no auto-learning), no streaming/VAD. The auto-learned
 * {@code correction_learned.json} layer ({@code _apply_corrections}) is intentionally NOT ported
 * (ROVER landmine — auto-learning dicts are forbidden).
 */
public final class OnDeviceCorrector {

    /** Step-5 punctuation hook. Returns text with punctuation added; chars must be unchanged. */
    public interface Punctuator {
        /** @return punctuated text, or {@code null} to skip (e.g. model not ready). */
        String addPunctuation(String text);

        /**
         * Context-aware variant: {@code precedingContext} is the user's already-typed text just
         * before the cursor (前文), so punctuation at the junction is correct. Implementations that
         * ignore context (e.g. the CT-Transformer tagger) inherit the default, which delegates to
         * {@link #addPunctuation(String)}.
         *
         * @param text             the newly-dictated, deterministically-corrected text to punctuate
         * @param precedingContext bounded window of text before the cursor (may be {@code null}/empty)
         * @return punctuated text, or {@code null} to skip / fall back
         */
        default String addPunctuation(String text, String precedingContext) {
            return addPunctuation(text);
        }
    }

    // Compiled exact-match replacement rules, longest-key-first.
    private final List<Rule> paraformerRules;    // step 1 (simplified)
    private final List<Rule> legalOverrideRules; // step 3b (after s2tw)
    private final List<Rule> mishearRules;        // step 4 (traditional)
    private final OpenCcS2tw openCc;              // step 3a
    private final List<String> protectedTerms;    // step 4 guard

    // ===== _remove_fillers regexes (ported verbatim from main.py) =====
    private static final Pattern F_SIL = Pattern.compile("<\\s*sil\\s*>");
    private static final Pattern F_REPEAT = Pattern.compile("[呃嗯額唔欸]{2,}");
    private static final Pattern F_AROUND_PUNCT = Pattern.compile("[，,。.、！!？?\\s]+[呃嗯額唔欸][，,。.、！!？?\\s]*");
    private static final Pattern F_AFTER_PUNCT = Pattern.compile("[呃嗯額唔欸][，,。.、！!？?\\s]+");
    private static final Pattern F_HEAD = Pattern.compile("^[呃嗯額唔欸]+[，,。.]?\\s*");
    private static final Pattern F_TAIL = Pattern.compile("[，,。.]?\\s*[呃嗯額唔欸]+$");
    // between two CJK ideographs: (?<=CJK)[呃嗯額](?=CJK)
    private static final Pattern F_BETWEEN = Pattern.compile("(?<=[\\u4e00-\\u9fff])[呃嗯額](?=[\\u4e00-\\u9fff])");
    private static final Pattern F_DUP_COMMA = Pattern.compile("[，,、]{2,}");
    private static final Pattern F_LEAD_PUNCT = Pattern.compile("^[，,、。.]+");

    // Punctuation set stripped by the 改字 guard. Covers CJK + ASCII punctuation and whitespace
    // the CT-Transformer model can emit (commas/periods/question/exclamation/quotes/brackets/etc.).
    private static final Pattern PUNCT_STRIP =
            Pattern.compile("[\\s\\u3000-\\u303F\\uFF00-\\uFFEF\\p{Punct}·…—－]+");

    private OnDeviceCorrector(List<Rule> paraformerRules,
                              OpenCcS2tw openCc,
                              List<Rule> legalOverrideRules,
                              List<Rule> mishearRules,
                              List<String> protectedTerms) {
        this.paraformerRules = paraformerRules;
        this.openCc = openCc;
        this.legalOverrideRules = legalOverrideRules;
        this.mishearRules = mishearRules;
        this.protectedTerms = protectedTerms;
    }

    // ====================================================================
    // Construction
    // ====================================================================

    /**
     * Build from the bundled asset streams. All streams are consumed and closed by caller.
     *
     * @param paraformerLegal paraformer_legal.txt   (wrong\tcorrect)
     * @param stPhrases       opencc/STPhrases.txt
     * @param stCharacters    opencc/STCharacters.txt
     * @param twVariants      opencc/TWVariants.txt
     * @param legalOverrides  legal_overrides.txt    (wrong\tcorrect [alt...])
     * @param mishear         legal_dict_mishear.txt (wrong\tcorrect)
     * @param protectedTermsIn protected_terms.txt   (one term per line)
     */
    public static OnDeviceCorrector load(InputStream paraformerLegal,
                                         InputStream stPhrases,
                                         InputStream stCharacters,
                                         InputStream twVariants,
                                         InputStream legalOverrides,
                                         InputStream mishear,
                                         InputStream protectedTermsIn) throws IOException {
        List<Rule> para = compileRules(loadPairs(paraformerLegal));
        OpenCcS2tw cc = OpenCcS2tw.load(stPhrases, stCharacters, twVariants);
        List<Rule> overrides = compileRules(loadPairs(legalOverrides));
        List<Rule> mis = compileRules(loadPairs(mishear));
        List<String> prot = loadLines(protectedTermsIn);
        return new OnDeviceCorrector(para, cc, overrides, mis, prot);
    }

    private static Map<String, String> loadPairs(InputStream in) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String key = line.substring(0, tab);
                String rest = line.substring(tab + 1);
                int sp = firstWs(rest);
                String val = sp < 0 ? rest : rest.substring(0, sp);
                if (!key.isEmpty() && !val.isEmpty() && !key.equals(val)) {
                    map.put(key, val);
                }
            }
        }
        return map;
    }

    private static List<String> loadLines(InputStream in) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.charAt(0) == '#') continue;
                out.add(t);
            }
        }
        return out;
    }

    private static int firstWs(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    /** Compile exact-match rules, longest key first (mirrors server's longest-match-first). */
    private static List<Rule> compileRules(Map<String, String> map) {
        List<Rule> rules = new ArrayList<>(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            rules.add(new Rule(e.getKey(), e.getValue()));
        }
        rules.sort((a, b) -> b.from.length() - a.from.length());
        return rules;
    }

    // ====================================================================
    // Pipeline
    // ====================================================================

    /**
     * Full deterministic correction + punctuation + guard.
     *
     * @param senseVoiceText on-device SenseVoice text (simplified, no punctuation), already
     *                       passed through EnglishMapper.apply().
     * @param punctuator     step-5 hook; may be {@code null} to skip punctuation entirely.
     * @return corrected, optionally-punctuated text.
     */
    public String correct(String senseVoiceText, Punctuator punctuator) {
        return correct(senseVoiceText, punctuator, null);
    }

    /**
     * Full deterministic correction + punctuation + guard, with 前文 context for the punctuator.
     *
     * @param senseVoiceText on-device SenseVoice text (simplified, no punctuation).
     * @param punctuator     step-5 hook; may be {@code null} to skip punctuation entirely.
     * @param precedingContext text already typed before the cursor (前文), passed to the punctuator
     *                         so junction punctuation is correct; may be {@code null}.
     * @return corrected, optionally-punctuated text.
     */
    public String correct(String senseVoiceText, Punctuator punctuator, String precedingContext) {
        return correct(senseVoiceText, precedingContext,
                punctuator == null ? new Punctuator[0] : new Punctuator[]{punctuator});
    }

    /**
     * Full deterministic correction (steps 1-4), then a CAGED punctuation CHAIN (steps 5-6).
     *
     * <p>The {@code punctuators} are tried in order (primary first, fallbacks after). Each candidate
     * is independently put through the 改字 GUARD: if it only added punctuation, it is accepted and
     * returned; if it altered/added/dropped a non-punctuation character (or returned {@code null}/
     * empty/threw), the NEXT punctuator is tried. If none passes, the deterministic step-4 text is
     * returned (no punctuation). This is how the LLM punctuator (primary) degrades gracefully to the
     * CT-Transformer tagger (fallback) to deterministic — all within one call, cage-checked per try.
     *
     * @param senseVoiceText   on-device SenseVoice text (simplified, no punctuation).
     * @param precedingContext 前文 passed to context-aware punctuators; may be {@code null}.
     * @param punctuators      ordered chain; empty/{@code null} entries skipped. May be empty.
     * @return corrected text, punctuated by the first chain member whose output passes the guard.
     */
    public String correct(String senseVoiceText, String precedingContext, Punctuator... punctuators) {
        if (senseVoiceText == null || senseVoiceText.isEmpty()) return senseVoiceText;

        // Step 1: paraformer legal homophone correction (SIMPLIFIED, before conversion).
        String t = applyRules(senseVoiceText, paraformerRules);

        // Step 2: remove fillers.
        t = removeFillers(t);

        // Step 3: 簡→繁 s2tw + legal overrides + 臺→台.
        t = openCc.convert(t);
        t = applyRules(t, legalOverrideRules);
        t = t.replace("臺", "台");

        // Step 4: legal_dictionary exact-match corrections (protected terms never touched).
        String preGuard = applyMishearProtected(t);

        // Step 5 + 6: punctuation CHAIN behind the 改字 guard (primary -> fallbacks).
        if (punctuators == null) return preGuard;
        for (Punctuator p : punctuators) {
            if (p == null) continue;
            String punctuated;
            try {
                punctuated = p.addPunctuation(preGuard, precedingContext);
            } catch (Throwable th) {
                continue; // this punctuator failed; try the next
            }
            if (punctuated == null || punctuated.isEmpty()) continue;
            String guarded = applyChangedCharGuard(preGuard, punctuated);
            // Guard returns preGuard when it REJECTS (chars changed). Treat "accepted" as
            // "punctuation actually added and chars unchanged"; otherwise fall through to next.
            if (!guarded.equals(preGuard)) {
                return guarded; // accepted: only punctuation differs
            }
            // If this punctuator added nothing (output == preGuard char-wise but no punctuation),
            // or the guard rejected it, fall through to the next chain member.
        }
        return preGuard; // no punctuator produced an accepted result
    }

    /** Steps 1-4 only (no punctuation). Exposed for testing and as the guard's reference text. */
    public String correctDeterministic(String senseVoiceText) {
        return correct(senseVoiceText, null);
    }

    /** _remove_fillers — ported verbatim from main.py. */
    String removeFillers(String text) {
        if (text == null) return null;
        text = F_SIL.matcher(text).replaceAll("");
        text = F_REPEAT.matcher(text).replaceAll("");
        text = F_AROUND_PUNCT.matcher(text).replaceAll("，");
        text = F_AFTER_PUNCT.matcher(text).replaceAll("");
        text = F_HEAD.matcher(text).replaceAll("");
        text = F_TAIL.matcher(text).replaceAll("");
        text = F_BETWEEN.matcher(text).replaceAll("");
        text = F_DUP_COMMA.matcher(text).replaceAll("，");
        text = F_LEAD_PUNCT.matcher(text).replaceAll("");
        return text.trim();
    }

    private static String applyRules(String text, List<Rule> rules) {
        String r = text;
        for (Rule rule : rules) {
            if (r.indexOf(rule.from) >= 0) {
                r = r.replace(rule.from, rule.to);
            }
        }
        return r;
    }

    /**
     * Step 4: apply mishear corrections, but never modify a protected term. We mask every
     * protected-term occurrence with a unique Private-Use-Area sentinel before applying mishear
     * rules, then restore. This guarantees an exact protected term is never partially rewritten
     * by an overlapping mishear rule.
     */
    String applyMishearProtected(String text) {
        if (text == null || text.isEmpty()) return text;
        List<String> hits = new ArrayList<>();
        String masked = text;
        // Protected terms longest-first so the longest protected term wins.
        List<String> sortedProt = new ArrayList<>(protectedTerms);
        sortedProt.sort((a, b) -> b.length() - a.length());
        for (String term : sortedProt) {
            if (term.isEmpty()) continue;
            if (masked.indexOf(term) < 0) continue;
            String sentinel = sentinelFor(hits.size());
            hits.add(term);
            masked = masked.replace(term, sentinel);
        }
        masked = applyRules(masked, mishearRules);
        for (int i = 0; i < hits.size(); i++) {
            masked = masked.replace(sentinelFor(i), hits.get(i));
        }
        return masked;
    }

    /**
     * Unique mask token for a protected-term occurrence, built from Unicode Private Use Area
     * code points (U+E000..U+F8FF). These never appear in SenseVoice/legal text, so a masked term
     * cannot be partially matched/rewritten by a mishear rule, and restoration is exact.
     */
    private static String sentinelFor(int index) {
        char c = (char) (0xE000 + (index & 0x18FF));
        return String.valueOf(c);
    }

    /**
     * Step 6 改字 GUARD. Strip ALL punctuation from {@code punctuated}, compare char-by-char with
     * {@code preGuard}. Identical → punctuation only added punctuation → accept punctuated.
     * Different → punctuation altered/dropped/added a non-punctuation char → reject, return preGuard.
     */
    String applyChangedCharGuard(String preGuard, String punctuated) {
        String strippedPunct = stripPunct(punctuated);
        String strippedPre = stripPunct(preGuard);
        if (strippedPunct.equals(strippedPre)) {
            return punctuated; // safe: only punctuation differs
        }
        return preGuard; // model changed characters — discard punctuation
    }

    /** Strip all punctuation/whitespace (used by the 改字 guard and exposed for tests). */
    public static String stripPunct(String s) {
        if (s == null) return "";
        return PUNCT_STRIP.matcher(s).replaceAll("");
    }

    // ====================================================================
    // Visible-for-test accessors
    // ====================================================================

    int paraformerRuleCount() { return paraformerRules.size(); }
    int legalOverrideRuleCount() { return legalOverrideRules.size(); }
    int mishearRuleCount() { return mishearRules.size(); }
    int protectedTermCount() { return protectedTerms.size(); }

    private static final class Rule {
        final String from;
        final String to;
        Rule(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }
}
