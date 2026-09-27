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
    }

    static final class State {
        final String composingText;
        final List<String> candidates;
        final String commitText;
        final boolean accepted;
        final int cursorPosition;
        State(String composingText, List<String> candidates, String commitText, boolean accepted, int cursorPosition) {
            this.composingText = composingText;
            this.candidates = candidates;
            this.commitText = commitText;
            this.accepted = accepted;
            this.cursorPosition = cursorPosition;
        }
    }

    private final Engine engine;
    ZhuyinInputController(Engine engine) { this.engine = engine == null ? DisabledEngine.INSTANCE : engine; }

    State press(String key) {
        if ("space".equals(key)) engine.space();
        else if ("enter".equals(key)) engine.enter();
        else if ("backspace".equals(key)) engine.backspace();
        else if (isZhuyin(key)) engine.key(key);
        else return snapshot(false, false);
        return snapshot(true, true);
    }

    State chooseCandidate(int index) {
        List<String> candidates = engine.candidates();
        if (index < 0 || index >= candidates.size()) return snapshot(false, false);
        engine.choose(index);
        return snapshot(true, true);
    }

    State moveCursorLeft() { engine.moveCursor("left"); return snapshot(true, true); }
    State moveCursorRight() { engine.moveCursor("right"); return snapshot(true, true); }

    State clear() { engine.clear(); return snapshot(true, true); }
    State state() { return snapshot(false, false); }
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
