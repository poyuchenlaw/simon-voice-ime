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
        String learnedWord = "";
        String learnedPronunciation = "";
        StringBuilder receivedKeys = new StringBuilder();
        List<String[]> personalPhrases = java.util.Collections.emptyList();
        int previewCharacter = -1;
        String beforeSegment = "";
        String segment = "";
        String afterSegment = "";
        List<String> segmentCandidates = java.util.Collections.emptyList();
        @Override public void moveCursor(String direction) { lastCursorKey = direction; cursor = Math.max(0, cursor + ("left".equals(direction) ? -1 : 1)); }
        @Override public boolean moveCursorToPreviewCharacter(int codePointIndex) {
            previewCharacter = codePointIndex;
            candidates = segmentCandidates;
            return true;
        }
        @Override public int cursorPosition() { return cursor; }
        @Override public void key(String key) { receivedKeys.append(key); composing += key; }
        @Override public void backspace() { composing = ""; }
        @Override public void space() { composing = "你好"; }
        @Override public void enter() { committed += composing; composing = ""; }
        @Override public void choose(int index) {
            chosen = index;
            if (!segment.isEmpty() && index >= 0 && index < candidates.size()) {
                segment = candidates.get(index);
                composing = beforeSegment + segment + afterSegment;
            } else {
                composing = candidates.get(index);
            }
        }
        @Override public String composingText() { return composing; }
        @Override public List<String> candidates() { return candidates; }
        @Override public String takeCommit() { String out = committed; committed = ""; return out; }
        @Override public void clear() { composing = ""; }
        @Override public void learnPhrase(String word, String pronunciation) {
            learnedWord = word; learnedPronunciation = pronunciation;
            java.util.ArrayList<String[]> updated = new java.util.ArrayList<>(personalPhrases);
            updated.add(new String[]{word, pronunciation});
            personalPhrases = updated;
        }
        @Override public List<String[]> personalPhrases() { return personalPhrases; }
    }

    @Test public void abbreviation_ranking_prefers_word_length_matching_input_and_deduplicates() {
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄧㄧ", "應有部分", "ㄧㄥˋ ㄧㄡˇ ㄅㄨˋ ㄈㄣˋ", Long.MAX_VALUE, true),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一心", "ㄧ ㄒㄧㄣ", 10000, false),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一樣", "ㄧ ㄧㄤˋ", 9025, false),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一夜", "ㄧ ㄧㄝˋ", 44283, false),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一一", "ㄧ ㄧ", 8000, false),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一樣", "ㄧˊ ㄧㄤˋ", 7000, false));

        List<ZhuyinWordIndex.Entry> candidates = index.lookup("ㄧㄧ");

        assertEquals("一夜", candidates.get(0).word);
        assertTrue(candidates.size() > 1);
        assertTrue(candidates.stream().limit(10).anyMatch(candidate -> "一樣".equals(candidate.word)));
        assertEquals("應有部分", candidates.get(candidates.size() - 1).word);
        java.util.Set<String> unique = new java.util.HashSet<>();
        for (ZhuyinWordIndex.Entry candidate : candidates) unique.add(candidate.word);
        assertEquals(candidates.size(), unique.size());
    }

    @Test public void selecting_yiyang_learns_it_as_first_candidate_for_next_abbreviation() {
        FakeEngine engine = new FakeEngine();
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一樣", "ㄧ ㄧㄤˋ", 9025, false),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一夜", "ㄧ ㄧㄝˋ", 44283, false));
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);

        controller.press("ㄧ");
        ZhuyinInputController.State beforeLearning = controller.press("ㄧ");
        assertEquals("一夜", beforeLearning.candidates.get(0));
        int yiyangPosition = beforeLearning.candidates.indexOf("一樣");
        assertTrue(yiyangPosition >= 0);
        controller.chooseCandidate(yiyangPosition);

        ZhuyinWordIndex reloadedIndex = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一樣", "ㄧ ㄧㄤˋ", 9025, false),
                new ZhuyinWordIndex.Entry("ㄧㄧ", "一夜", "ㄧ ㄧㄝˋ", 44283, false));
        controller = new ZhuyinInputController(engine, reloadedIndex);
        controller.press("ㄧ");
        ZhuyinInputController.State afterLearning = controller.press("ㄧ");
        assertEquals("一樣", afterLearning.candidates.get(0));
        assertEquals("一樣", engine.learnedWord);
        assertEquals("ㄧ ㄧㄤˋ", engine.learnedPronunciation);
    }

    @Test public void initial_bopomofo_sequence_shows_abbreviation_candidates_and_preserves_buffer() {
        FakeEngine engine = new FakeEngine();
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄈㄌ", "法律", "ㄈㄚˇ ㄌㄩˋ", 40, false),
                new ZhuyinWordIndex.Entry("ㄋㄏ", "你好", "ㄋㄧˇ ㄏㄠˇ", 30, false),
                new ZhuyinWordIndex.Entry("ㄔㄅㄩ", "陳柏諭", "ㄔㄣˊ ㄅㄛˊ ㄩˋ", 1, true));
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);
        assertEquals("ㄔ", controller.press("ㄔ").composingText);
        assertEquals("ㄔㄅ", controller.press("ㄅ").composingText);
        ZhuyinInputController.State state = controller.press("ㄩ");
        assertEquals("ㄔㄅㄩ", state.composingText);
        assertEquals("abbreviation", state.candidateKind);
        assertEquals("陳柏諭", state.candidates.get(0));
    }

    @Test public void repeated_vowel_initials_find_yiyang_abbreviation() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine,
                ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄧㄧ", "一樣", "ㄧ ㄧㄤˋ", 9025, false)));
        controller.press("ㄧ");
        ZhuyinInputController.State state = controller.press("ㄧ");
        assertEquals("ㄧㄧ", state.composingText);
        assertEquals("abbreviation", state.candidateKind);
        assertEquals("一樣", state.candidates.get(0));
    }

    @Test public void abbreviation_candidates_select_and_learn_full_pronunciation() {
        FakeEngine engine = new FakeEngine();
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄔㄅㄩ", "陳柏諭", "ㄔㄣˊ ㄅㄛˊ ㄩˋ", 1, true));
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);
        controller.press("ㄔ"); controller.press("ㄅ");
        ZhuyinInputController.State state = controller.press("ㄩ");
        assertEquals("ㄔㄅㄩ", state.composingText);
        assertEquals("abbreviation", state.candidateKind);
        assertEquals("陳柏諭", state.candidates.get(0));
        state = controller.chooseCandidate(0);
        assertEquals("陳柏諭", state.commitText);
        assertEquals("陳柏諭", engine.learnedWord);
        assertEquals("ㄔㄣˊ ㄅㄛˊ ㄩˋ", engine.learnedPronunciation);
    }

    @Test public void existing_libchewing_personal_phrases_are_imported_ahead_of_system_words() {
        FakeEngine engine = new FakeEngine();
        engine.personalPhrases = java.util.Collections.singletonList(new String[]{"陳柏諭", "ㄔㄣˊ ㄅㄛˊ ㄩˋ"});
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄔㄅㄩ", "系統詞", "ㄔㄣ ㄅㄛ ㄩ", 9000, false));
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);
        controller.press("ㄔ"); controller.press("ㄅ");
        ZhuyinInputController.State state = controller.press("ㄩ");
        assertEquals("陳柏諭", state.candidates.get(0));
    }

    @Test public void non_initial_key_replays_saved_keys_into_libchewing_without_loss() {
        FakeEngine engine = new FakeEngine();
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(
                new ZhuyinWordIndex.Entry("ㄔㄅㄩ", "陳柏諭", "ㄔㄣˊ ㄅㄛˊ ㄩˋ", 1, true));
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);
        controller.press("ㄔ"); controller.press("ㄅ");
        ZhuyinInputController.State state = controller.press("ˊ");
        assertEquals("ㄔㄅˊ", state.composingText);
        assertEquals("ㄔㄅˊ", engine.receivedKeys.toString());
        assertEquals("engine", state.candidateKind);
    }

    @Test public void valid_two_symbol_syllable_is_replayed_instead_of_misread_as_abbreviation() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine,
                ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄓ", "知", "ㄓ", 1, false)));
        controller.press("ㄓ");
        ZhuyinInputController.State state = controller.press("ㄨ");
        assertEquals("engine", state.candidateKind);
        assertEquals("ㄓㄨ", state.composingText);
    }

    @Test public void simple_prefix_does_not_hide_existing_engine_composition() {
        FakeEngine engine = new FakeEngine(); engine.composing = "已有";
        ZhuyinInputController controller = new ZhuyinInputController(engine,
                ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄔㄅ", "陳柏", "ㄔㄣˊ ㄅㄛˊ", 1, false)));
        ZhuyinInputController.State state = controller.press("ㄔ");
        assertEquals("engine", state.candidateKind);
        assertEquals("已有ㄔ", state.composingText);
    }

    @Test public void space_flushes_one_buffered_initial_before_engine_space() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine,
                ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄔㄅ", "陳柏", "ㄔㄣˊ ㄅㄛˊ", 1, false)));
        controller.press("ㄔ");
        ZhuyinInputController.State state = controller.press("space");
        assertEquals("ㄔ", engine.receivedKeys.toString());
        assertEquals("你好", state.composingText);
    }

    @Test public void association_selection_commits_remainder_and_zhuyin_key_returns_to_engine() {
        FakeEngine engine = new FakeEngine();
        ZhuyinInputController controller = new ZhuyinInputController(engine,
                ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄈㄌ", "法律", "ㄈㄚˇ ㄌㄩˋ", 10, false)));
        ZhuyinInputController.State associated = controller.showAssociations(Arrays.asList("事務所", "顧問"));
        assertEquals("association", associated.candidateKind);
        assertEquals("事務所", controller.chooseAssociation(0).commitText);
        controller.showAssociations(Arrays.asList("事務所"));
        assertEquals("ㄋ", controller.press("ㄋ").composingText);
        assertEquals("engine", controller.state().candidateKind);
    }

    @Test public void association_history_persists_only_adjacent_words_and_orders_by_count() throws Exception {
        java.io.File file = java.io.File.createTempFile("zhuyin-assoc", ".tsv");
        file.delete();
        ZhuyinAssociationHistory history = new ZhuyinAssociationHistory(file);
        history.record("法律", "事務所");
        history.record("法律", "顧問");
        history.record("法律", "事務所");
        assertEquals(Arrays.asList("事務所", "顧問"), history.nextWords("法律"));
        assertEquals(Arrays.asList("事務所", "顧問"), new ZhuyinAssociationHistory(file).nextWords("法律"));
        file.delete();
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
        assertTrue(controller.chooseCandidate(1).accepted);
        assertEquals("你號", controller.state().composingText);
        assertFalse(controller.chooseCandidate(9).accepted);
        assertEquals("你號", controller.press("enter").commitText);
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

    @Test public void horizontal_swipe_page_switch_handler_is_absent() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.exists(root.resolve("app/src/main"))) root = root.getParent();
        String service = new String(java.nio.file.Files.readAllBytes(root.resolve(
                "app/src/main/java/com/simon/voiceime/SimonIMEService.java")), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(service.contains("setupKeyboardSwipe"));
        assertFalse(java.nio.file.Files.exists(root.resolve(
                "app/src/main/java/com/simon/voiceime/SwipeGestureJudge.java")));
    }

    @Test public void preview_tap_targets_preceding_segment_and_candidate_replaces_only_that_segment() {
        FakeEngine engine = new FakeEngine();
        engine.composing = "現在會辨識";
        engine.beforeSegment = "現在";
        engine.segment = "會";
        engine.afterSegment = "辨識";
        engine.segmentCandidates = Arrays.asList("會", "回");
        ZhuyinInputController controller = new ZhuyinInputController(engine);

        ZhuyinInputController.State focused = controller.moveCursorToPreviewCharacter(2);
        assertEquals(2, engine.previewCharacter);
        assertEquals(Arrays.asList("會", "回"), focused.candidates);
        ZhuyinInputController.State chosen = controller.chooseCandidate(1);
        assertEquals("現在回辨識", chosen.composingText);
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
