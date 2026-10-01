package com.simon.voiceime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import org.junit.Test;

/** Never-waived controller gate: a failed engine parse must not leak keymap ASCII. */
public class ZhuyinV644SafetyGateTest {
    private static final String SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙";
    private static final class NoParseEngine implements ZhuyinInputController.Engine {
        StringBuilder raw = new StringBuilder(); String committed = "";
        String learned = "";
        @Override public void key(String key) { raw.append((char) ZhuyinKeyMap.physicalKey(key)); }
        @Override public void backspace() { if (raw.length() > 0) raw.setLength(raw.length() - 1); }
        @Override public void space() { committed += raw; raw.setLength(0); }
        @Override public void enter() { committed += raw; raw.setLength(0); }
        @Override public void choose(int index) { }
        @Override public void moveCursor(String direction) { }
        @Override public int cursorPosition() { return raw.length(); }
        @Override public String composingText() { return raw.toString(); }
        @Override public List<String> candidates() { return Collections.emptyList(); }
        @Override public String takeCommit() { String out = committed; committed = ""; return out; }
        @Override public void clear() { raw.setLength(0); }
        @Override public void learnPhrase(String word, String pronunciation) { learned = word; }
    }
    private static void assertNoAscii(String text) { for(int i=0;i<text.length();i++) assertFalse("keysym in ["+text+"]", "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347 ".indexOf(text.charAt(i))>=0); }

    @Test public void named_incident_and_10000_fuzzed_streams_never_commit_keymap_ascii() {
        Random random = new Random(644L);
        for (int run = 0; run < 10000; run++) {
            NoParseEngine engine = new NoParseEngine(); ZhuyinInputController controller = new ZhuyinInputController(engine);
            int length = run == 0 ? 14 : 1 + random.nextInt(24);
            for (int i = 0; i < length; i++) {
                String key = String.valueOf(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
                ZhuyinInputController.State state = controller.press(key);
                assertTrue(state.accepted);
            }
            String terminator = run % 3 == 0 ? "enter" : run % 3 == 1 ? "space" : "backspace";
            ZhuyinInputController.State state = controller.press(terminator);
            assertNoAscii(state.commitText);
            assertNoAscii(engine.takeCommit());
        }
    }

    @Test public void no_parse_preview_is_editable_and_commits_glyphs_not_partial_or_ascii() {
        NoParseEngine engine = new NoParseEngine(); ZhuyinInputController controller = new ZhuyinInputController(engine);
        controller.press("ㄍ"); controller.press("ㄧ");
        assertEquals("ㄍㄧ", controller.state().composingText);
        assertEquals("no_parse", controller.state().candidateKind);
        assertEquals("ㄍ", controller.press("backspace").composingText);
        assertEquals("ㄍ", controller.press("enter").commitText);
    }

    @Test public void protected_fields_disable_controller_learning_and_match_all_password_variants() throws Exception {
        NoParseEngine engine = new NoParseEngine();
        ZhuyinWordIndex index = ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄋ", "你", "ㄋㄧˇ", 1, false));
        ZhuyinInputController controller = new ZhuyinInputController(engine, index);
        controller.setLearningEnabled(false); controller.press("ㄋ"); controller.chooseCandidate(0);
        assertEquals("", engine.learned);
        java.lang.reflect.Method guard = SimonIMEService.class.getDeclaredMethod("isProtectedInputField", EditorInfo.class);
        guard.setAccessible(true);
        int[] types = {InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD};
        for (int type : types) { EditorInfo info = new EditorInfo(); info.inputType = type; assertTrue((Boolean) guard.invoke(null, info)); }
        EditorInfo noLearning = new EditorInfo(); noLearning.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        assertTrue((Boolean) guard.invoke(null, noLearning));
    }
}
