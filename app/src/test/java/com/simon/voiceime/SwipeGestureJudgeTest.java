package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.simon.voiceime.SwipeGestureJudge.Result.*;

public class SwipeGestureJudgeTest {
    @Test public void tap() {
        assertEquals(NONE, SwipeGestureJudge.judge(3f, 2f, 100f, 8f));
    }

    @Test public void verticalDrag() {
        assertEquals(NONE, SwipeGestureJudge.judge(30f, 120f, 100f, 8f));
    }

    @Test public void swipeLeft() {
        assertEquals(LEFT, SwipeGestureJudge.judge(-200f, 20f, 100f, 8f));
    }

    @Test public void swipeRight() {
        assertEquals(RIGHT, SwipeGestureJudge.judge(200f, -15f, 100f, 8f));
    }

    @Test public void diagonalCounterexample() {
        assertEquals(NONE, SwipeGestureJudge.judge(100f, 90f, 100f, 8f));
    }

    @Test public void belowDistance() {
        assertEquals(NONE, SwipeGestureJudge.judge(99f, 0f, 100f, 8f));
    }

    @Test public void exactDistance() {
        assertEquals(RIGHT, SwipeGestureJudge.judge(100f, 0f, 100f, 8f));
    }

    @Test public void exactRatio() {
        assertEquals(LEFT, SwipeGestureJudge.judge(-150f, 100f, 100f, 8f));
    }

    @Test public void belowRatio() {
        assertEquals(NONE, SwipeGestureJudge.judge(149f, 100f, 100f, 8f));
    }

    @Test public void atTouchSlop() {
        assertEquals(NONE, SwipeGestureJudge.judge(8f, 0f, 1f, 8f));
    }

}
