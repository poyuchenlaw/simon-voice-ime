package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
public class LayoutDiagnosticsTest {
 @Test public void monotoneForwardMovementIsNotBackAndForth(){LayoutDiagnostics.Axis a=new LayoutDiagnostics.Axis();for(int x:new int[]{0,0,10,20,20,30})a.sample(x);assertEquals(0,a.moves);assertEquals(0,a.max);}
 @Test public void reversalsReportMeasuredPixels(){LayoutDiagnostics.Axis a=new LayoutDiagnostics.Axis();for(int x:new int[]{100,104,100,100,106,104})a.sample(x);assertEquals(3,a.moves);assertEquals(6,a.max);}
 @Test public void stationaryDrawDoesNotForgetDirection(){LayoutDiagnostics.Axis a=new LayoutDiagnostics.Axis();for(int x:new int[]{0,12,12,12,8})a.sample(x);assertEquals(1,a.moves);assertEquals(4,a.max);}
 @Test public void changingTextLengthRestartsGlyphDirectionButKeepsCounts(){LayoutDiagnostics.Axis a=new LayoutDiagnostics.Axis();a.sample(0);a.sample(10);a.restart();a.sample(100);a.sample(90);assertEquals(0,a.moves);a.sample(96);assertEquals(1,a.moves);assertEquals(6,a.max);}
}
