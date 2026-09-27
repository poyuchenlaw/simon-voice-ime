package com.simon.voiceime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ZhuyinInputControllerTest {
    private static final class FakeEngine implements ZhuyinInputController.Engine {
        String composing = "";
        String committed = "";
        List<String> candidates = Arrays.asList("你好", "你號");
        int chosen = -1;
        String lastCursorKey = "";
        int cursor = 0;
        @Override public void moveCursor(String direction) { lastCursorKey = direction; cursor = Math.max(0, cursor + ("left".equals(direction) ? -1 : 1)); }
        @Override public int cursorPosition() { return cursor; }
        @Override public void key(String key) { composing += key; }
        @Override public void backspace() { composing = ""; }
        @Override public void space() { composing = "你好"; }
        @Override public void enter() { committed += composing; composing = ""; }
        @Override public void choose(int index) { chosen = index; composing = candidates.get(index); }
        @Override public String composingText() { return composing; }
        @Override public List<String> candidates() { return candidates; }
        @Override public String takeCommit() { String out = committed; committed = ""; return out; }
        @Override public void clear() { composing = ""; }
    }

    @Test public void composing_buffer_includes_engine_preedit_and_candidates() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine);
        ZhuyinInputController.State state = controller.press("ㄋ");
        assertEquals("ㄋ", state.composingText);
        assertEquals(Arrays.asList("你好", "你號"), state.candidates);
    }

    @Test public void candidate_choice_updates_composing_state_and_commit_is_drained() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine);
        assertTrue(controller.chooseCandidate(0).accepted);
        assertEquals("你好", controller.state().composingText);
        assertFalse(controller.chooseCandidate(9).accepted);
        assertEquals("你好", controller.press("enter").commitText);
    }

    @Test public void tone_symbol_is_forwarded_as_input_not_committed_raw() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine);
        assertEquals("˙", controller.press("˙").composingText);
        assertEquals("", controller.press("˙").commitText);
    }

    @Test public void all_candidates_are_returned_and_candidate_24_is_selectable() {
        FakeEngine engine = new FakeEngine();
        java.util.ArrayList<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) many.add("candidate-" + (i + 1));
        engine.candidates = many;
        ZhuyinInputController controller = new ZhuyinInputController(engine);
        assertEquals(24, controller.state().candidates.size());
        assertEquals("candidate-1", controller.state().candidates.get(0));
        assertTrue(controller.chooseCandidate(23).accepted);
        assertEquals("candidate-24", controller.state().composingText);
    }

    @Test public void cursor_buttons_forward_left_right_and_refresh_candidates() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine);
        ZhuyinInputController.State left = controller.moveCursorLeft();
        assertEquals("left", engine.lastCursorKey);
        assertEquals(0, left.cursorPosition);
        ZhuyinInputController.State right = controller.moveCursorRight();
        assertEquals("right", engine.lastCursorKey);
        assertEquals(1, right.cursorPosition);
        assertEquals(engine.candidates, right.candidates);
    }

    @Test public void horizontal_swipe_starting_in_candidate_row_does_not_change_page() {
        assertEquals(SwipeGestureJudge.Result.NONE,
                SwipeGestureJudge.judge(-180, 2, 56, 8, true));
        assertEquals(SwipeGestureJudge.Result.LEFT,
                SwipeGestureJudge.judge(-180, 2, 56, 8, false));
    }

    @Test public void zhuyin_keys_map_to_libchewing_standard_physical_layout() {
        String symbols = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙";
        String physicalKeys = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347";
        assertEquals(41, symbols.length());
        assertEquals(symbols.length(), physicalKeys.length());
        for (int i = 0; i < symbols.length(); i++) {
            assertEquals("physical key for " + symbols.charAt(i), (int) physicalKeys.charAt(i),
                    ZhuyinKeyMap.physicalKey(String.valueOf(symbols.charAt(i))));
        }
        assertEquals(-1, ZhuyinKeyMap.physicalKey("x"));
    }
}
