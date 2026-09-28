package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.*;

public class T9KeyMapTest {
    @Test public void groupMappingMatchesSimonSpecification() {
        assertArrayEquals(new String[]{"ㄅㄆㄇㄈ","ㄉㄊㄋㄌ","ㄍㄎㄏ","ㄐㄑㄒ","ㄓㄔㄕㄖ","ㄗㄘㄙ","ㄧㄨㄩ","ㄚㄛㄜㄝㄞㄟㄠㄡ","ㄢㄣㄤㄥㄦ"},T9KeyMap.GROUPS);
        assertEquals(1,T9KeyMap.digitForSymbol("ㄅ"));assertEquals(8,T9KeyMap.digitForSymbol("ㄟ"));assertEquals(9,T9KeyMap.digitForSymbol("ㄦ"));
    }
    @Test public void twoPressAndFullPressShareTheSameRimeDigits() {
        assertEquals("18",T9KeyMap.twoPress(1,8));assertEquals("18",T9KeyMap.fullPressSequence("ㄅㄚ"));
        assertEquals("56",T9KeyMap.fullPressSequence("ㄓㄗ"));assertEquals(-1,T9KeyMap.digitForSymbol("ˇ"));
    }
}
