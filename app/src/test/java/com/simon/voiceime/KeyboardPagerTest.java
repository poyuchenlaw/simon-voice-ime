package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.simon.voiceime.KeyboardPager.KeyboardMode.*;
import static com.simon.voiceime.KeyboardPager.Direction.*;

public class KeyboardPagerTest {
    @Test public void voiceLeft() {
        assertEquals(NUMBERS, KeyboardPager.next(VOICE, LEFT));
    }

    @Test public void voiceRight() {
        assertEquals(ENGLISH, KeyboardPager.next(VOICE, RIGHT));
    }

    @Test public void numbersRight() {
        assertEquals(VOICE, KeyboardPager.next(NUMBERS, RIGHT));
    }

    @Test public void englishLeft() {
        assertEquals(VOICE, KeyboardPager.next(ENGLISH, LEFT));
    }

    @Test public void numbersLeftWraps() {
        assertEquals(ENGLISH, KeyboardPager.next(NUMBERS, LEFT));
    }

    @Test public void englishRightWraps() {
        assertEquals(NUMBERS, KeyboardPager.next(ENGLISH, RIGHT));
    }

}
