package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
public class DualHandGestureTest {
    @Test public void specTrajectoriesAndBoundaries(){
        assertEquals("tap",DualHandGesture.classify(12,0,40,12).direction);
        assertEquals("right",DualHandGesture.classify(30,0,120,30).direction);
        DualHandGesture.Result diagonal=DualHandGesture.classify(30,30,120,43);
        assertEquals("right",diagonal.direction);assertTrue(diagonal.ambiguous);
        assertEquals("up",DualHandGesture.classify(0,-30,35,30).direction);
        assertEquals("tap",DualHandGesture.classify(18,0,100,18).direction);
        assertEquals("tap",DualHandGesture.classify(27,0,79,27).direction);
        assertEquals("right",DualHandGesture.classify(28,0,79,28).direction);
        assertEquals("right",DualHandGesture.classify(20,0,80,20).direction);
        assertEquals("tap",DualHandGesture.classify(30,0,50,60).direction);
        assertFalse(DualHandGesture.classify(42,30,120,52).ambiguous);
    }
    @Test public void twoPointFastInjectionIsLinear(){
        assertEquals("right",DualHandGesture.classify(30,0,40,60,2).direction);
        assertEquals("up",DualHandGesture.classify(0,-28,40,56,2).direction);
        assertEquals("tap",DualHandGesture.classify(27,0,40,54,2).direction);
        assertEquals("tap",DualHandGesture.classify(30,0,40,60,3).direction);
        DualHandGesture.Result diagonal=DualHandGesture.classify(30,30,40,90,2);
        assertEquals("right",diagonal.direction);assertTrue(diagonal.ambiguous);
    }
    @Test(expected=IllegalArgumentException.class) public void invalidCoordinateRejected(){DualHandGesture.classify(Float.NaN,0,40,0);}
}
