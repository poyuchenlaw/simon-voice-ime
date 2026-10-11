package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class DualHandLearningTest {
    private Reader asset(String name)throws IOException{return Files.newBufferedReader(Path.of("src/phone/assets/dual_hand",name));}
    private DualHandTracks tracks(DualHandMemory memory)throws IOException{return new DualHandTracks(asset("phrase_words.tsv"),asset("grid_chars.tsv"),asset("readings.tsv"),memory);}
    private DualHandMemory memory()throws IOException{return new DualHandMemory(Files.createTempDirectory("dh-memory").resolve("personal.tsv").toFile());}
    private void input(DualHandState state,String l,String ld,String r,String rd,long t){var stroke=state.begin(l,t);state.end(stroke,ld,false);state.drain(t+100);stroke=state.begin(r,t+200);state.end(stroke,rd,false);state.drain(t+300);}
    @Test public void remembersCountsRecencyAndUnknownWordsAcrossReload()throws Exception {
        var memory=memory();var key=List.of("ㄑㄩㄝ","ㄖㄣ");
        memory.remember(key,"卻仁",false,10);memory.remember(key,"缺人",false,20);memory.remember(key,"卻仁",false,30);memory.remember(key,"確人",false,40);
        var choices=tracks(new DualHandMemory(memory.file())).candidates(key);
        assertEquals(List.of("卻仁","確人","缺人"),choices.stream().map(c->c.text).toList());assertTrue(choices.stream().allMatch(c->c.personal));
        assertEquals(2,memory.find(key).get(0).count);assertEquals(30,memory.find(key).get(0).lastUsed);
        assertEquals(2,tracks(memory).candidates(List.of("ㄑㄩㄝ","ㄖㄣ","ㄅㄟ","ㄌㄩㄢ")).get(0).end);
        assertTrue(tracks(memory).candidates(List.of("ㄑㄩㄝ")).isEmpty());assertTrue(tracks(memory).candidates(List.of("ㄑㄩㄝ","ㄖㄣ","ㄅㄟ","ㄌㄩㄢ","ㄅㄚ")).isEmpty());
    }
    @Test public void protectedMisalignedAndMalformedValuesLeaveNoFile()throws Exception {
        var m=memory();assertFalse(m.remember(List.of("ㄑㄩㄝ"),"確認",false,1));assertFalse(m.remember(List.of("ㄑㄩㄝ"),"確",true,1));
        assertFalse(m.remember(List.of("ㄑㄩㄝˋ"),"確",false,1));assertFalse(m.remember(List.of("ㄑㄩㄝ"),"\t",false,1));assertFalse(m.file().exists());
    }
    @Test public void evictsLeastRecentlyUsedAtTwoThousand()throws Exception {
        var m=memory();for(int i=0;i<2000;i++)m.remember(List.of("ㄧ"),new String(Character.toChars(0x4e00+i)),false,i+1);
        m.remember(List.of("ㄧ"),"一",false,3000);m.remember(List.of("ㄧ"),"𠮷",false,3001);
        var entries=new DualHandMemory(m.file()).find(List.of("ㄧ"));assertEquals(2000,entries.size());assertTrue(entries.stream().anyMatch(e->e.text.equals("一")));assertFalse(entries.stream().anyMatch(e->e.text.equals("丁")));
    }
    @Test public void gridPrioritizesLearnedPhrasesAndSinglesWithExactReadings()throws Exception {
        var m=memory();m.remember(List.of("ㄑㄩㄝ","ㄖㄣ"),"卻仁",false,1);m.remember(List.of("ㄑㄩㄝ"),"闕",false,2);
        var grid=tracks(m).grid(List.of(List.of("ㄑㄩㄝ"),List.of("ㄖㄣ")),0);
        assertEquals(9,grid.size());assertEquals("卻仁",grid.get(0).text);assertTrue(grid.get(0).personal);assertEquals(List.of("ㄑㄩㄝ","ㄖㄣ"),grid.get(0).syllables);
        assertEquals("闕",grid.get(3).text);assertTrue(grid.get(3).personal);assertEquals(List.of("ㄑㄩㄝ"),grid.get(3).syllables);
        m.remember(List.of("ㄑㄩㄝ","ㄖㄣ","ㄅㄚ"),"卻仁八",false,3);
        assertEquals("卻仁八",tracks(m).grid(List.of(List.of("ㄑㄩㄝ"),List.of("ㄖㄣ"),List.of("ㄅㄚ")),1).get(0).text);
    }
    @Test public void aiAndDisplayTablesDifferAndIgnoreManualCorrections()throws Exception {
        String ai=new String(Files.readAllBytes(Path.of("src/phone/assets/dual_hand/draft_chars.tsv")),java.nio.charset.StandardCharsets.UTF_8).replace("ㄑㄩㄝ\t卻","ㄑㄩㄝ\t闕").replace("ㄖㄣ\t人","ㄖㄣ\t仁");
        var state=new DualHandState(new DualHandTable(asset("dual_hand_map.tsv"),asset("left_hand.tsv"),asset("draft_chars.tsv"),new StringReader(ai)));
        input(state,"4","up","B","down",0);input(state,"5","down","E","left",1000);
        assertEquals("卻人",state.draft());assertEquals("闕仁",state.aiDraft());state.replace(0,2,"確認");assertEquals("確認",state.draft());assertEquals("闕仁",state.aiDraft());
        state.applyAi("確人");state.replace(1,2,"仁");assertEquals("確仁",state.draft());assertEquals("闕仁",state.aiDraft());
    }
    @Test public void malformedStoredRowsFailWithoutOverwriting()throws Exception {
        var file=memory().file();
        for(String row:List.of("ㄧ\t一\t0\t1\n","ㄧˋ\t一\t1\t1\n","ㄧ\t一\t1\t-1\n","ㄧ\t一\tbad\t1\n","ㄧ\t一\t1\t1\nㄧ\t一\t2\t2\n")){
            byte[] bytes=row.getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(file.toPath(),bytes);
            assertThrows(IOException.class,()->new DualHandMemory(file));assertArrayEquals(bytes,Files.readAllBytes(file.toPath()));
        }
    }
    @Test public void failedAtomicWriteRetainsPreviousMemory()throws Exception {
        var m=memory();m.remember(List.of("ㄧ"),"一",false,1);
        Files.createDirectory(Path.of(m.file()+".tmp"));assertThrows(IOException.class,()->m.remember(List.of("ㄧ"),"以",false,2));
        assertEquals(List.of("一"),m.find(List.of("ㄧ")).stream().map(e->e.text).toList());assertEquals("一",new DualHandMemory(m.file()).find(List.of("ㄧ")).get(0).text);
    }
}
