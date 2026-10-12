package com.simon.voiceime;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class DualHandHostTest {
    static DualHandState state;
    static long clock=1000;
    static void eq(Object want,Object got){if(!Objects.equals(want,got))throw new AssertionError("want="+want+" got="+got);}
    static void key(String key,String direction) {
        DualHandState.Stroke s=state.begin(key,clock);
        state.end(s,direction,false);state.drain(clock+100);clock+=150;
    }
    static void sequence(String expected,String... keys){state.clear();for(String k:keys){String[] v=k.split(":");key(v[0],v[1]);}eq(expected,String.join(" ",state.syllables()));}
    public static void main(String[] args)throws Exception {
        Path assets=Path.of(args[0]);
        DualHandTable table;
        try(Reader map=Files.newBufferedReader(assets.resolve("dual_hand_map.tsv"));Reader left=Files.newBufferedReader(assets.resolve("left_hand.tsv"));Reader draft=Files.newBufferedReader(assets.resolve("draft_chars.tsv"))){table=new DualHandTable(map,left,draft);}
        state=new DualHandState(table);
        eq("tap",DualHandGesture.classify(12,0,40,12).direction);
        eq("right",DualHandGesture.classify(30,0,120,30).direction);
        DualHandGesture.Result diagonal=DualHandGesture.classify(30,-30,120,43);
        eq("right",diagonal.direction);eq(true,diagonal.ambiguous);
        eq("up",DualHandGesture.classify(0,-30,35,30).direction);
        eq("tap",DualHandGesture.classify(27,0,79,27).direction);
        eq("right",DualHandGesture.classify(28,0,79,28).direction);
        eq("tap",DualHandGesture.classify(18,0,100,18).direction);
        sequence("ㄉㄨㄟ","2:tap","C:right");
        sequence("ㄒㄧㄣ","4:left","E:left");
        sequence("ㄍㄨㄥ ㄙ","3:tap","F:up","6:left","Ø:tap");
        sequence("ㄨㄛ ㄞ ㄋㄧ","3:right","B:tap","3:down","C:tap","2:left","Ø:up");
        sequence("ㄕ ㄏㄠ ㄉㄜ","5:left","Ø:tap","3:left","D:tap","2:tap","B:tap");
        sequence("ㄧㄢ ㄧㄣ ㄨㄣ ㄩㄣ","4:right","E:tap","4:right","E:left","3:right","E:left","4:down","E:left");
        sequence("ㄚ","A:tap");
        sequence("ㄉㄨㄟ","1:tap","2:tap","C:right");
        sequence("ㄐㄧ ㄧ ㄨ ㄩ ㄦ","4:tap","Ø:tap","Ø:up","Ø:right","Ø:down","Ø:left");
        sequence("ㄧ ㄨ ㄩ","4:right","Ø:tap","3:right","Ø:tap","4:down","Ø:tap");
        state.clear();key("3","tap");key("Ø","tap");eq(0,state.syllables().size());eq("ㄍ",state.pending());
        state.clear();key("2","tap");key("6","down");eq("ㄉ",state.pending());
        state.clear();DualHandState.Stroke r=state.begin("C",100);state.end(r,"right",false);eq(0,state.drain(120).size());
        DualHandState.Stroke l=state.begin("2",130);state.end(l,"tap",false);state.drain(160);eq(List.of("ㄉㄨㄟ"),state.syllables());
        state.clear();r=state.begin("C",100);state.end(r,"tap",false);state.drain(141);l=state.begin("2",142);state.end(l,"tap",false);state.drain(200);eq(List.of("ㄞ"),state.syllables());eq("ㄉ",state.pending());
        state.clear();r=state.begin("C",100);l=state.begin("2",140);state.end(r,"right",false);eq(0,state.drain(160).size());state.end(l,"tap",false);state.drain(180);eq(List.of("ㄉㄨㄟ"),state.syllables());
        state.clear();l=state.begin("2",100);r=state.begin("C",110);state.end(r,"right",false);eq(0,state.drain(200).size());state.end(l,"tap",false);state.drain(220);eq(List.of("ㄉㄨㄟ"),state.syllables());
        state.clear();l=state.begin("2",100);state.end(l,"tap",true);state.drain(200);eq(null,state.pending());
        sequence("ㄉㄨㄟ","2:tap","C:right");eq(table.draft("ㄉㄨㄟ"),state.draft());key("1","tap");eq(DualHandState.Delete.PENDING,state.backspace());eq(1,state.syllables().size());eq(DualHandState.Delete.SYLLABLE,state.backspace());eq("",state.draft());eq("ㄉ",state.pending());eq(DualHandState.Delete.PENDING,state.backspace());eq(DualHandState.Delete.EDITOR,state.backspace());
        state.clear();l=state.begin("2",100);state.end(l,"tap",false);r=state.begin("C",110);state.end(r,"right",false);
        DualHandState.Stroke space=state.begin("core",200);state.end(space,"tap",false);DualHandState.Stroke next=state.begin("A",210);state.end(next,"tap",false);
        state.drain(300);eq(List.of("ㄉㄨㄟ"),state.syllables());state.clearBuffer();state.drain(301);eq(List.of("ㄚ"),state.syllables());
        try{new DualHandTable(new StringReader("state\tkey\tgesture\tsyllable\nØ\tA\ttap\tㄚ\nØ\tA\ttap\tㄚ\n"),new StringReader(""),new StringReader(""));throw new AssertionError("duplicate accepted");}catch(IOException expected){}
        System.out.println("PASS gestures, six SPEC sequences, Ø rules, race 30/40/>40ms and reversed release, cancel, invalid direction, draft/backspace, malformed asset");
    }
}
