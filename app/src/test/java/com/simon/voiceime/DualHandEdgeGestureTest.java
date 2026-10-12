package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.*;

public class DualHandEdgeGestureTest {
    private static DualHandGesture.Result edge(String key, boolean leftEdge, float dx, float dy, long ms) {
        float path = (float) Math.hypot(dx, dy);
        return DualHandGesture.classify(key, leftEdge, dx, dy, ms, path, 3);
    }
    private static void same(DualHandGesture.Result expected, DualHandGesture.Result actual) {
        assertEquals(expected.direction, actual.direction);
        assertEquals(expected.ambiguous, actual.ambiguous);
    }
    @Test public void sixDpFastLeftIsNotSwallowedAsTap() {
        DualHandGesture.Result result = edge("1", true, -6, 0, 60);
        assertEquals("left", result.direction); assertFalse(result.ambiguous);
    }
    @Test public void elevenDpTelemetryLeftIsNotSwallowedAsTap() {
        DualHandGesture.Result result = edge("3", true, -11, -.7f, 92);
        assertEquals("left", result.direction); assertFalse(result.ambiguous);
    }
    @Test public void eighteenDpFastLeftIsNotSwallowedAsTap() {
        DualHandGesture.Result result = edge("5", true, -18, -3.5f, 72);
        assertEquals("left", result.direction); assertFalse(result.ambiguous);
    }
    @Test public void driftAndNonDominantDiagonalKeepOriginalResults() {
        for (float dx : new float[]{-4, -5.9f, -5.6f, 0})
            assertEquals("tap", edge("3", true, dx, 0, 60).direction);
        same(edge("3", false, -7, -7, 60), edge("3", true, -7, -7, 60));
        assertEquals("tap", edge("3", true, -7, -7, 60).direction);
    }
    @Test public void nonEdgeAndBottomKeysKeepTap() {
        for (String key : new String[]{"2", "4", "6", "A", "B", "C", "D", "E", "F",
                "Ø", "tools", "symbols", "clipboard", "core"})
            assertEquals(key, "tap", edge(key, false, -11, 0, 60).direction);
    }
    @Test public void upDownRightAndNonDominantLeftKeepBothResultFields() {
        for (String key : new String[]{"1", "3", "5"})
            for (float dx : new float[]{-40, -18, -6, 0, 6, 18, 40})
                for (float dy : new float[]{-40, -18, 0, 18, 40})
                    for (long ms : new long[]{0, 60, 80, 120, 400}) {
                        if (dx <= -6 && Math.abs(dx) >= 1.4f * Math.abs(dy)) continue;
                        same(edge(key, false, dx, dy, ms), edge(key, true, dx, dy, ms));
                    }
    }
    @Test public void exactHorizontalDominanceBoundaryIsUnambiguousLeft() {
        assertEquals("left", edge("3", true, -7, 5, 60).direction);
        assertFalse(edge("3", true, -7, 5, 60).ambiguous);
        same(edge("3", false, -7, 5.01f, 60), edge("3", true, -7, 5.01f, 60));
    }
    @Test public void edgeShortcutStillRejectsInvalidMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> DualHandGesture.classify("3", true, -6, Float.NaN, 60, 6, 3));
        assertThrows(IllegalArgumentException.class, () -> DualHandGesture.classify("3", true, -6, 0, -1, 6, 3));
        assertThrows(IllegalArgumentException.class, () -> DualHandGesture.classify("3", true, -6, 0, 60, -1, 3));
        assertThrows(IllegalArgumentException.class, () -> DualHandGesture.classify("3", true, -6, 0, 60, Float.POSITIVE_INFINITY, 3));
        assertThrows(IllegalArgumentException.class, () -> DualHandGesture.classify("3", true, -6, 0, 60, 6, -1));
    }
}
