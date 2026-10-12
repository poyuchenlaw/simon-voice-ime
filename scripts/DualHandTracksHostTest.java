package com.simon.voiceime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
public final class DualHandTracksHostTest {
    static Reader asset(Path dir,String name)throws IOException{return Files.newBufferedReader(dir.resolve(name),StandardCharsets.UTF_8);}
    static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    static void input(DualHandState state,String left,String ld,String right,String rd,long t){
        if(left!=null){var stroke=state.begin(left,t);state.end(stroke,ld,false);state.drain(t+100);}
        var stroke=state.begin(right,t+200);state.end(stroke,rd,false);state.drain(t+300);
    }
    public static void main(String[] args)throws Exception {
        Path dir=Path.of(args[0]);DualHandTable table=new DualHandTable(asset(dir,"dual_hand_map.tsv"),asset(dir,"left_hand.tsv"),asset(dir,"draft_chars.tsv"));
        DualHandTracks tracks=new DualHandTracks(asset(dir,"phrase_words.tsv"),asset(dir,"grid_chars.tsv"),asset(dir,"readings.tsv"));
        check(tracks.candidates(List.of("ㄑㄩㄝ")).isEmpty(),"one syllable suppressed");
        check(tracks.candidates(List.of("ㄑㄩㄝ","ㄖㄣ")).stream().anyMatch(c->c.text.equals("確認")),"SPEC confirmation");
        check(tracks.candidates(List.of("ㄊㄧㄠ","ㄎㄨㄢ")).get(0).text.equals("條款"),"SPEC clause rank");
        check(tracks.candidates(List.of("ㄑㄩㄝ","ㄖㄣ","ㄑㄩㄝ","ㄖㄣ","ㄑㄩㄝ")).isEmpty(),"five suppressed");
        var fallback=tracks.candidates(List.of("ㄑㄩㄝ","ㄖㄣ","ㄅㄟ","ㄌㄩㄢ"));
        check(!fallback.isEmpty()&&fallback.get(0).end==2,"longest prefix only");
        var grid=tracks.grid(List.of(List.of("ㄑㄩㄝ"),List.of("ㄖㄣ")),0);
        check(grid.size()==9&&grid.get(0).text.equals("確認")&&grid.get(0).end==2,"association first row");
        check(grid.get(3).text.codePointCount(0,grid.get(3).text.length())==1,"lower rows single");
        check(tracks.readings("行").containsAll(List.of("ㄒㄧㄥ","ㄏㄤ")),"all polyphonic readings");
        check(tracks.grid(List.of(tracks.readings("行")),0).size()==9,"merged polyphonic singles");
        check(tracks.mismatches("確認",List.of("ㄑㄩㄝ","ㄖㄣ")).isEmpty(),"matched pronunciation");
        check(tracks.mismatches("確人",List.of("ㄑㄩㄝ","ㄖㄣ")).isEmpty(),"tones ignored");
        check(tracks.mismatches("確天",List.of("ㄑㄩㄝ","ㄖㄣ")).equals(List.of(1)),"mismatch reminder only");
        DualHandState state=new DualHandState(table);
        input(state,"4","up","B","down",0);input(state,"5","down","E","left",1000);
        check(state.syllables().equals(List.of("ㄑㄩㄝ","ㄖㄣ")),"SPEC sequence");String original=state.draft();
        check(!state.applyAi("確認了")&&state.draft().equals(original),"length rejection");
        check(state.applyAi("確天")&&state.draft().equals("確天"),"equal length adopts mismatch");
        check(state.restoreAi()&&state.draft().equals(original)&&state.syllables().size()==2,"undo keeps syllables");
        check(state.applyAi("確認"),"AI apply");state.backspace();check(state.draft().equals("確")&&state.syllables().equals(List.of("ㄑㄩㄝ")),"r3 backspace");
        check(state.restoreAi()&&state.draft().codePointCount(0,state.draft().length())==1,"undo does not resurrect deleted syllable");
        check("ㄖ".equals(state.pending()),"6.89 remembers removed initial");state.discardPending();state.applyAi("𠮷");check(state.draft().length()==2,"non BMP scalar accepted");state.backspace();check(state.draft().isEmpty(),"non BMP delete");
        input(state,"4","up","B","down",2000);input(state,"5","down","E","left",3000);
        state.consume(1);check(state.syllables().equals(List.of("ㄖㄣ")),"prefix consumes only match");
        check(DualHandTracks.tail("a".repeat(55)+"𠮷",50).codePointCount(0,DualHandTracks.tail("a".repeat(55)+"𠮷",50).length())==50,"50 scalars context");
        check(!DualHandTracks.fresh(1,1,0,8000)&&DualHandTracks.fresh(1,1,0,7999)&&!DualHandTracks.fresh(1,2,0,1),"timeout and stale generation");
        System.out.println("PASS: SPEC 10-4 confirmation / clause rank; prefix, grid, polyphony, mismatch, undo, r3, Unicode, timeout/stale");
    }
}
