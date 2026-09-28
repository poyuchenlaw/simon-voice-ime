package com.simon.voiceime;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class T9CompositionModelTest {
    @Test public void choosingMiddleSegmentLeavesNeighborsAndEnterPreviewIntact() {
        T9CompositionModel model=new T9CompositionModel();
        model.update(0,"18",Arrays.asList("甲","加"));
        model.update(1,"28",Arrays.asList("乙","也"));
        model.update(2,"38",Arrays.asList("丙","並"));
        assertTrue(model.choose(1,1));
        String refreshedPreview=model.preview(); // matches candidate click -> render refresh -> Enter
        assertEquals("甲也丙",refreshedPreview);
        assertEquals("甲",model.segment(0).selected);
        assertEquals("丙",model.segment(2).selected);
    }
    @Test public void keyChangesRefreshOnlyTheirOwnSegment() {
        T9CompositionModel model=new T9CompositionModel();
        model.update(0,"18",Arrays.asList("甲","加"));
        model.update(1,"28",Arrays.asList("乙","也"));
        model.choose(0,1);
        model.update(1,"29",Arrays.asList("要","藥"));
        assertEquals("加要",model.preview());
    }
}
