package com.simon.voiceime;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Boundary regression: a parsed-prefix commit must survive an empty tail menu. */
public class ZhuyinV647aTailTest {
    private static class PrefixAndTail implements ZhuyinInputController.Engine {
        String preview = "", pending = "";
        public boolean preservesUnparsedInput() { return true; }
        public void key(String key) { preview="ㄎ˙"; pending="你"; }
        public void backspace() { preview="ㄎ"; }
        public void space() { enter(); }
        public void enter() { pending+=preview; preview=""; }
        public void choose(int i) { }
        public void moveCursor(String d) { }
        public int cursorPosition() { return preview.length(); }
        public String composingText() { return preview; }
        public List<String> candidates() { return Collections.emptyList(); }
        public String takeCommit() { String out=pending; pending=""; return out; }
        public void clear() { preview=""; pending=""; }
    }
    @Test public void no_candidate_tail_drains_prefix_once_and_explicit_commit_keeps_tail() {
        ZhuyinInputController c=new ZhuyinInputController(new PrefixAndTail());
        ZhuyinInputController.State s=c.press("ㄎ");
        assertEquals("你",s.commitText);
        assertEquals("ㄎ˙",s.composingText);
        assertTrue(c.state().commitText.isEmpty());
        assertEquals("ㄎ˙",c.press("enter").commitText);
        assertTrue(c.press("enter").commitText.isEmpty());
    }
    @Test public void unparsed_tail_is_preserved_on_punctuation_and_clear_discards_it() {
        ZhuyinInputController c=new ZhuyinInputController(new PrefixAndTail());
        c.press("ㄎ");
        assertEquals("ㄎ˙",c.flushForPunctuation().commitText);
        c.press("ㄎ");c.clear();
        assertTrue(c.state().composingText.isEmpty());
        assertTrue(c.press("enter").commitText.isEmpty());
    }
    @Test public void every_keysym_in_engine_commits_is_rendered_as_zhuyin() {
        // Independent literal oracle from the workorder and standard keyboard.
        final String keys="1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347 ";
        final String glyphs="ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙ˉ";
        for(int i=0;i<keys.length();i++) {
            final String raw="你"+keys.charAt(i)+"，。！？";
            ZhuyinInputController c=new ZhuyinInputController(new PrefixAndTail() {
                @Override public void key(String key) { preview="ㄎ˙"; pending=raw; }
            });
            assertEquals("keysym="+keys.charAt(i),"你"+glyphs.charAt(i)+"，。！？",c.press("ㄎ").commitText);
        }
    }
    @Test public void punctuation_only_raw_tail_and_space_are_not_text() {
        ZhuyinInputController c=new ZhuyinInputController(new PrefixAndTail() {
            @Override public void key(String key) { preview=""; pending="./,7; -"; }
        });
        assertEquals("ㄡㄥㄝ˙ㄤˉㄦ",c.press("ㄡ").commitText);
    }

}
