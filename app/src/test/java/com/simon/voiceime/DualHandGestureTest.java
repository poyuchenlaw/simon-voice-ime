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
    @Test public void shortRightCoversAllKeysRightOfMidline(){
        for(String key:new String[]{"A","B","C","D","E","F","Ø","clipboard","core"}){
            assertEquals(key,"right",DualHandGesture.classify(key,9.1f,0,40,9.1f,2).direction);
            assertEquals(key,"tap",DualHandGesture.classify(key,9,0,120,9,2).direction);
            assertEquals(key,"tap",DualHandGesture.classify(key,8.9f,0,120,8.9f,2).direction);
            assertEquals(key,"tap",DualHandGesture.classify(key,0,-10,120,10,2).direction);
        }
        for(String key:new String[]{"1","2","3","4","5","6","tools","symbols"})
            assertEquals(key,"tap",DualHandGesture.classify(key,10,0,120,10,2).direction);
    }
    @Test(expected=IllegalArgumentException.class) public void invalidCoordinateRejected(){DualHandGesture.classify(Float.NaN,0,40,0);}
}
