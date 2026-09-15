package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.*;

public class TextLossGuardTest {
    @Test public void threeSegmentsWithThreeCharacterOverlaps() {
        String joined = TextLossGuard.joinDedup(
                java.util.Arrays.asList("今天去圖書館", "圖書館借故事書", "故事書很好看"), 6);
        assertEquals("今天去圖書館借故事書很好看", joined);
        assertEquals(13, joined.length());
    }
    @Test public void segmentsWithoutOverlapAreConcatenated() {
        assertEquals("今天天氣很好適合散步", TextLossGuard.joinDedup(
                java.util.Arrays.asList("今天", "天氣很好", "適合散步"), 0));
        assertEquals("甲乙丙丁戊己", TextLossGuard.joinDedup(
                java.util.Arrays.asList("甲乙", "丙丁", "戊己"), 6));
    }
    @Test public void nineExtraCharactersDoNotRescue() {
        assertFalse(TextLossGuard.shouldRescue(2, 11));
    }
    @Test public void tenExtraBelowRatioDoNotRescue() {
        assertFalse(TextLossGuard.shouldRescue(21, 31));
    }
    @Test public void tenExtraAtRatioRescue() {
        assertTrue(TextLossGuard.shouldRescue(20, 30));
    }
    @Test public void shorterCandidateDoesNotRescue() {
        assertFalse(TextLossGuard.shouldRescue(30, 20));
    }
    @Test public void emptyFinalStillRequiresTenCharacters() {
        assertFalse(TextLossGuard.shouldRescue(0, 9));
        assertTrue(TextLossGuard.shouldRescue(0, 10));
    }
    @Test public void configuredThresholdsAreUsed() {
        assertFalse(TextLossGuard.shouldRescue(10, 19, 5, 2.0));
        assertTrue(TextLossGuard.shouldRescue(10, 20, 5, 2.0));
    }
}
