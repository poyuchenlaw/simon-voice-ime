package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class DualHandTracksTest {
    private Reader asset(String name)throws IOException{return Files.newBufferedReader(Path.of("src/phone/assets/dual_hand",name));}
    private DualHandTracks tracks()throws IOException{return new DualHandTracks(asset("phrase_words.tsv"),asset("grid_chars.tsv"),asset("readings.tsv"));}
    private DualHandState state()throws IOException{return new DualHandState(new DualHandTable(asset("dual_hand_map.tsv"),asset("left_hand.tsv"),asset("draft_chars.tsv")));}
    private void input(DualHandState state,String left,String direction,String right,String gesture,long now){var l=state.begin(left,now);state.end(l,direction,false);state.drain(now+100);var r=state.begin(right,now+200);state.end(r,gesture,false);state.drain(now+300);}
    @Test public void specShortPhrasesAndPrefixOnly()throws Exception {
        var tracks=tracks();assertTrue(tracks.candidates(List.of("ㄑㄩㄝ","ㄖㄣ")).stream().anyMatch(c->c.text.equals("確認")));
        assertEquals("條款",tracks.candidates(List.of("ㄊㄧㄠ","ㄎㄨㄢ")).get(0).text);
        assertTrue(tracks.candidates(List.of("ㄑㄩㄝ")).isEmpty());assertTrue(tracks.candidates(List.of("ㄑㄩㄝ","ㄖㄣ","ㄑㄩㄝ","ㄖㄣ","ㄑㄩㄝ")).isEmpty());
        assertEquals(2,tracks.candidates(List.of("ㄑㄩㄝ","ㄖㄣ","ㄅㄟ","ㄌㄩㄢ")).get(0).end);
    }
    @Test public void gridAssociationsAndPolyphonicFallback()throws Exception {
        var tracks=tracks();var grid=tracks.grid(List.of(List.of("ㄑㄩㄝ"),List.of("ㄖㄣ")),0);
        assertEquals(9,grid.size());assertEquals("確認",grid.get(0).text);assertEquals(2,grid.get(0).end);assertEquals(1,grid.get(3).text.codePointCount(0,grid.get(3).text.length()));
        assertTrue(tracks.readings("行").containsAll(List.of("ㄒㄧㄥ","ㄏㄤ")));assertEquals(9,tracks.grid(List.of(tracks.readings("行")),0).size());
        assertEquals("這",tracks.grid(List.of(List.of("ㄓㄟ")),0).get(0).text);assertEquals("",tracks.grid(List.of(List.of("ㄓㄟ")),0).get(1).text);
    }
    @Test public void lengthMismatchAndPronunciationReminderOnly()throws Exception {
        var state=state();input(state,"4","up","B","down",0);input(state,"5","down","E","left",1000);
        String before=state.draft();assertFalse(state.applyAi("確認了"));assertEquals(before,state.draft());
        assertTrue(state.applyAi("確天"));assertEquals("確天",state.draft());assertEquals(List.of(1),tracks().mismatches(state.draft(),state.syllables()));
        assertTrue(state.restoreAi());assertEquals(before,state.draft());assertEquals(List.of("ㄑㄩㄝ","ㄖㄣ"),state.syllables());
    }
    @Test public void r3DeleteUndoAndSupplementaryGlyphs()throws Exception {
        var state=state();input(state,"4","up","B","down",0);input(state,"5","down","E","left",1000);
        assertTrue(state.applyAi("確認"));state.backspace();assertEquals("確",state.draft());assertEquals(1,state.syllables().size());
        state.restoreAi();assertEquals("ㄖ",state.pending());state.discardPending();assertTrue(state.applyAi("𠮷"));state.backspace();assertEquals("",state.draft());assertTrue(state.syllables().isEmpty());
    }
    @Test public void newSyllableCommitsAiBeforeAppendAndKeepsPendingConsonant()throws Exception {
        var state=state();input(state,"4","up","B","down",0);input(state,"5","down","E","left",1000);state.applyAi("確認");
        var left=state.begin("2",2000);state.end(left,"tap",false);state.drain(2100);var right=state.begin("C",2200);state.end(right,"right",false);
        List<String> committed=new ArrayList<>();var out=state.drain(2300,()->{committed.add(state.draft());state.clearBuffer();return true;});
        assertEquals(List.of("確認"),committed);assertEquals("ㄉㄨㄟ",out.get(0).syllable);assertEquals(List.of("ㄉㄨㄟ"),state.syllables());
    }
    @Test public void unavailableEditorRetainsQueuedStrokeAndBuffer()throws Exception {
        var state=state();input(state,"4","up","B","down",0);state.applyAi("確");var right=state.begin("A",1000);state.end(right,"tap",false);
        assertTrue(state.drain(1100,()->false).isEmpty());assertEquals("確",state.draft());assertEquals(1,state.syllables().size());
        assertEquals(1,state.drain(1200,()->true).size());
    }
    @Test public void requestUsesBaseCharactersNotLocalAssociation()throws Exception {
        var state=state();input(state,"4","up","B","down",0);input(state,"5","down","E","left",1000);String base=state.baseDraft();
        state.replace(0,2,"確認");assertEquals(base,state.baseDraft());state.consume(1);assertEquals(List.of("ㄖㄣ"),state.syllables());
    }
    @Test public void explicitEightSecondDeadlineAndFiftyScalarContext(){assertTrue(DualHandTracks.fresh(2,2,100,8099));assertFalse(DualHandTracks.fresh(2,2,100,8100));assertFalse(DualHandTracks.fresh(2,3,100,101));String context=DualHandTracks.tail("𠮷".repeat(55),50);assertEquals(50,context.codePointCount(0,context.length()));}
    @Test public void malformedAssetStopsLoading(){assertThrows(IOException.class,()->new DualHandTracks(new StringReader("wrong"),new StringReader(""),new StringReader("")));}
}
