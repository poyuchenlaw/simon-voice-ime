package com.simon.voiceime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class ZhuyinV642Test {
    private static final class Engine implements ZhuyinInputController.Engine {
        String composing = ""; String commit = ""; boolean cleared;
        @Override public void key(String key) { composing += key; }
        @Override public void backspace() { if (!composing.isEmpty()) composing = composing.substring(0, composing.length()-1); }
        @Override public void space() { commit = "已選"; composing = ""; }
        @Override public void enter() { }
        @Override public void choose(int index) { }
        @Override public void moveCursor(String direction) { }
        @Override public int cursorPosition() { return composing.length(); }
        @Override public String composingText() { return composing; }
        @Override public List<String> candidates() { return Collections.emptyList(); }
        @Override public String takeCommit() { String value = commit; commit = ""; return value; }
        @Override public void clear() { composing = ""; cleared = true; }
    }

    @Test public void firstSymbolShowsPublicOwnerAndSelectionConsumesComposition() {
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄔㄅㄩ", "陳柏諭", "ㄔㄣˊ ㄅㄛˊ ㄩˋ", 1, ZhuyinWordIndex.Provenance.PERSONAL_PUBLIC),
                new ZhuyinWordIndex.Entry("ㄔ", "成", "ㄔㄥˊ", 1, false));
        Engine engine = new Engine();
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);
        ZhuyinInputController.State state = controller.press("ㄔ");
        assertEquals("陳柏諭", state.candidates.get(0));
        assertEquals("陳柏諭", controller.chooseCandidate(0).commitText);
        assertTrue(engine.cleared);
    }

    @Test public void punctuationFlushesCompositionBeforePunctuationCommit() {
        Engine engine = new Engine(); engine.composing = "ㄔㄣˊ";
        ZhuyinInputController.State state = new ZhuyinInputController(engine).flushForPunctuation();
        assertEquals("已選", state.commitText);
        assertEquals("", engine.composing);
    }

    @Test public void bopomofoKeyTelemetryPreservesTouchFieldsAndAddsLatency() throws Exception {
        JSONObject event = ImeTelemetry.makeBopomofoKeyEvent(
                123L, "session", "6.42", "ㄅ", 1.25f, -2.5f, 100f, 200f, 7L);
        assertEquals("key", event.getString("type"));
        assertEquals("bopomofo", event.getString("page"));
        assertEquals("session", event.getString("session_id"));
        assertEquals("ㄅ", event.getString("key"));
        assertTrue(event.has("x"));
        assertTrue(event.has("y"));
        assertTrue(event.has("key_center_x"));
        assertTrue(event.has("key_center_y"));
        assertEquals(7L, event.getLong("key_to_candidate_ms"));
    }
}
