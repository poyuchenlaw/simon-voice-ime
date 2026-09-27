package com.simon.voiceime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Thin UI-facing adapter. The conversion, candidate ranking and learning remain in libchewing. */
final class ZhuyinInputController {
    interface Engine {
        void key(String key);
        void backspace();
        void space();
        void enter();
        void choose(int index);
        void moveCursor(String direction);
        int cursorPosition();
        String composingText();
        List<String> candidates();
        String takeCommit();
        void clear();
        default void learnPhrase(String word, String pronunciation) { }
        default List<String[]> personalPhrases() { return Collections.emptyList(); }
    }

    static final class State {
        final String composingText;
        final List<String> candidates;
        final String commitText;
        final boolean accepted;
        final int cursorPosition;
        final String candidateKind;
        final List<ZhuyinWordIndex.Entry> abbreviationEntries;
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition) {
            this(composingText, candidates, commitText, accepted, cursorPosition, "engine", Collections.emptyList());
        }
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition, String candidateKind, List<ZhuyinWordIndex.Entry> abbreviationEntries) {
            this.composingText = composingText;
            this.candidates = candidates;
            this.commitText = commitText;
            this.accepted = accepted;
            this.cursorPosition = cursorPosition;
            this.candidateKind = candidateKind;
            this.abbreviationEntries = abbreviationEntries;
        }
    }

    private final Engine engine;
    private final ZhuyinWordIndex abbreviationIndex;
    private final StringBuilder abbreviationKeys = new StringBuilder();
    private List<ZhuyinWordIndex.Entry> abbreviationEntries = Collections.emptyList();
    private List<String> associationCandidates = Collections.emptyList();
    ZhuyinInputController(Engine engine) { this.engine = engine == null ? DisabledEngine.INSTANCE : engine; this.abbreviationIndex = null; }
    ZhuyinInputController(Engine engine, ZhuyinWordIndex index) {
        this.engine = engine == null ? DisabledEngine.INSTANCE : engine;
        this.abbreviationIndex = index;
        if (index != null) {
            List<String[]> learned = this.engine.personalPhrases();
            index.rememberPersonal(learned);
        }
    }

    State press(String key) {
        if ("backspace".equals(key) && abbreviationKeys.length() > 0) {
            abbreviationKeys.setLength(abbreviationKeys.length() - 1);
            abbreviationEntries = abbreviationKeys.length() >= 2 ? abbreviationIndex.lookup(abbreviationKeys.toString()) : Collections.emptyList();
            if (abbreviationKeys.length() == 0) return snapshot(true, true);
            return abbreviationSnapshot();
        }
        if (isZhuyin(key) && abbreviationIndex != null
                && (abbreviationKeys.length() > 0 || engine.composingText().isEmpty())) {
            if (abbreviationKeys.length() > 0) {
                String extended = abbreviationKeys.toString() + key;
                if (abbreviationKeys.length() == 1 && abbreviationIndex.isCompleteSyllable(extended)) {
                    replayAbbreviation();
                    if (isZhuyin(key)) engine.key(key);
                    return snapshot(true, true);
                }
                if (abbreviationIndex.hasPrefix(extended)) {
                    abbreviationKeys.append(key);
                    abbreviationEntries = abbreviationKeys.length() >= 2 ? abbreviationIndex.lookup(extended) : Collections.emptyList();
                    if (!abbreviationEntries.isEmpty()) return abbreviationSnapshot();
                    return abbreviationSnapshot();
                }
                replayAbbreviation();
            } else if (abbreviationIndex.hasPrefix(key)) {
                abbreviationKeys.append(key);
                return abbreviationSnapshot();
            }
        }
        if (abbreviationKeys.length() > 0 && !isZhuyin(key)) replayAbbreviation();
        associationCandidates = Collections.emptyList();
        if ("space".equals(key)) engine.space();
        else if ("enter".equals(key)) engine.enter();
        else if ("backspace".equals(key)) engine.backspace();
        else if (isZhuyin(key)) engine.key(key);
        else return snapshot(false, false);
        return snapshot(true, true);
    }

    State chooseCandidate(int index) {
        if (abbreviationKeys.length() >= 2) {
            if (index < 0 || index >= abbreviationEntries.size()) return abbreviationSnapshot();
            ZhuyinWordIndex.Entry selected = abbreviationEntries.get(index);
            engine.learnPhrase(selected.word, selected.pronunciation);
            abbreviationIndex.rememberPersonal(selected);
            abbreviationKeys.setLength(0); abbreviationEntries = Collections.emptyList();
            return new State("", Collections.emptyList(), selected.word, true, 0, "abbreviation", Collections.emptyList());
        }
        List<String> candidates = engine.candidates();
        if (index < 0 || index >= candidates.size()) return snapshot(false, false);
        engine.choose(index);
        return snapshot(true, true);
    }

    State moveCursorLeft() { engine.moveCursor("left"); return snapshot(true, true); }
    State moveCursorRight() { engine.moveCursor("right"); return snapshot(true, true); }

    State clear() { abbreviationKeys.setLength(0); abbreviationEntries = Collections.emptyList(); associationCandidates = Collections.emptyList(); engine.clear(); return snapshot(true, true); }
    State state() { return snapshot(false, false); }
    State showAssociations(List<String> candidates) {
        associationCandidates = candidates == null ? Collections.emptyList() : new ArrayList<>(candidates);
        return associationSnapshot();
    }
    State chooseAssociation(int index) {
        if (index < 0 || index >= associationCandidates.size()) return associationSnapshot();
        String value = associationCandidates.get(index);
        associationCandidates = Collections.emptyList();
        return new State("", Collections.emptyList(), value, true, 0, "association", Collections.emptyList());
    }
    void close() {
        if (engine instanceof AutoCloseable) {
            try { ((AutoCloseable) engine).close(); } catch (Exception ignored) { }
        }
    }

    private State snapshot(boolean accepted, boolean drainCommit) {
        String composing = engine.composingText();
        if (composing == null) composing = "";
        List<String> candidates = engine.candidates();
        if (candidates == null) candidates = Collections.emptyList();
        return new State(composing, Collections.unmodifiableList(new ArrayList<>(candidates)),
                drainCommit ? engine.takeCommit() : "", accepted, engine.cursorPosition());
    }

    private State abbreviationSnapshot() {
        List<String> words = new ArrayList<>();
        for (ZhuyinWordIndex.Entry e : abbreviationEntries) words.add(e.word);
        return new State(abbreviationKeys.toString(), Collections.unmodifiableList(words), "", true, abbreviationKeys.length(), "abbreviation", abbreviationEntries);
    }
    private State associationSnapshot() {
        return new State("", Collections.unmodifiableList(new ArrayList<>(associationCandidates)), "", false, 0, "association", Collections.emptyList());
    }
    private void replayAbbreviation() {
        for (int i=0; i<abbreviationKeys.length(); i++) engine.key(abbreviationKeys.substring(i, i+1));
        abbreviationKeys.setLength(0); abbreviationEntries = Collections.emptyList();
    }

    private static boolean isZhuyin(String key) {
        return key != null && key.length() == 1
                && "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".contains(key);
    }

    private static final class DisabledEngine implements Engine {
        static final DisabledEngine INSTANCE = new DisabledEngine();
        @Override public void key(String key) { }
        @Override public void backspace() { }
        @Override public void space() { }
        @Override public void enter() { }
        @Override public void choose(int index) { }
        @Override public void moveCursor(String direction) { }
        @Override public int cursorPosition() { return 0; }
        @Override public String composingText() { return ""; }
        @Override public List<String> candidates() { return Collections.emptyList(); }
        @Override public String takeCommit() { return ""; }
        @Override public void clear() { }
    }
}
