package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;

/** Expectations are the approved 6.89 examples, not derived from implementation. */
public class App689Test {
 private final Path assets=Path.of("src/phone/assets/dual_hand");
 private DualHandTable table()throws Exception{return new DualHandTable(Files.newBufferedReader(assets.resolve("dual_hand_map.tsv")),Files.newBufferedReader(assets.resolve("left_hand.tsv")),Files.newBufferedReader(assets.resolve("draft_chars.tsv")));}
 private void stroke(DualHandState b,String key,String dir){DualHandState.Stroke s=b.begin(key,100);b.end(s,dir,false);b.drain(1000);}
 private Object call(Object o,String method,Class<?>[] types,Object... args)throws Exception{try{return o.getClass().getMethod(method,types).invoke(o,args);}catch(NoSuchMethodException missing){throw new AssertionError("6.89 missing "+method,missing);}}
 private Object call(Object o,String method)throws Exception{return call(o,method,new Class<?>[0]);}
 @Test public void c_oncePerGestureAndStoredInitial()throws Exception{
  DualHandState b=new DualHandState(table());stroke(b,"1","tap");stroke(b,"Ø","right");assertEquals(List.of("ㄅㄨ"),b.syllables());b.backspace();assertEquals(List.of(),b.syllables());assertEquals("ㄅ",b.pending());b.backspace();assertNull(b.pending());
  stroke(b,"A","right");assertEquals(List.of("ㄨㄚ"),b.syllables());b.backspace();assertNull(b.pending());assertTrue(b.syllables().isEmpty());
  stroke(b,"3","tap");stroke(b,"A","right");assertEquals(List.of("ㄍㄨㄚ"),b.syllables());b.backspace();assertEquals("ㄍ",b.pending());b.backspace();assertNull(b.pending());
 }
 @Test public void abc_previewAndTwentySyllables()throws Exception{DualHandState b=new DualHandState(table());for(int i=0;i<20;i++)stroke(b,"Ø","right");assertEquals(20,b.syllables().size());stroke(b,"1","tap");String preview=(String)call(b,"preview");assertEquals(String.join(" ",Collections.nCopies(20,"ㄨ"))+" ㄅ",preview);assertEquals(20,call(b,"cursor"));}
 @Test public void de_middleDeleteAndInsert()throws Exception{DualHandState b=new DualHandState(table());stroke(b,"Ø","up");stroke(b,"1","tap");stroke(b,"Ø","right");stroke(b,"Ø","down");call(b,"select",new Class<?>[]{int.class},1);assertEquals(2,call(b,"cursor"));b.backspace();assertEquals(List.of("ㄧ","ㄩ"),b.syllables());assertEquals("ㄅ",b.pending());assertEquals("ㄧ ㄅ ㄩ",call(b,"preview"));stroke(b,"Ø","up");assertEquals(List.of("ㄧ","ㄅㄧ","ㄩ"),b.syllables());assertEquals(2,call(b,"cursor"));call(b,"select",new Class<?>[]{int.class},1);call(b,"eraseCharacter");assertEquals(List.of("ㄧ","ㄩ"),b.syllables());assertNull(b.pending());assertEquals(-1,call(b,"selected"));call(b,"select",new Class<?>[]{int.class},0);call(b,"setCursor",new Class<?>[]{int.class},0);assertEquals("BLOCKED",call(b,"eraseCharacter").toString());assertEquals(List.of("ㄧ","ㄩ"),b.syllables());}
 @Test public void d_pendingDeleteAndAiRestore()throws Exception{DualHandState b=new DualHandState(table());stroke(b,"Ø","up");stroke(b,"1","tap");assertEquals("PENDING",call(b,"eraseCharacter").toString());assertEquals(List.of("ㄧ"),b.syllables());assertTrue(b.applyAi("甲"));assertEquals("RESTORED",call(b,"eraseCharacter").toString());assertFalse(b.aiShown());assertEquals("一",b.draft());}
 @Test public void g_nameAndJ_reselect()throws Exception{DualHandState b=new DualHandState(table());stroke(b,"5","up");stroke(b,"E","left");stroke(b,"1","tap");stroke(b,"B","tap");stroke(b,"Ø","down");assertEquals(List.of("ㄔㄣ","ㄅㄛ","ㄩ"),b.syllables());call(b,"select",new Class<?>[]{int.class},0);String[] name={"陳","柏","宇"};for(int i=0;i<3;i++){call(b,"lock",new Class<?>[]{int.class,String.class},i,name[i]);assertEquals(i<2?i+1:-1,call(b,"selected"));}assertEquals("陳柏宇",b.draft());assertEquals(true,call(b,"hasLocks"));assertFalse(b.applyAi("陳伯於"));call(b,"select",new Class<?>[]{int.class},2);call(b,"lock",new Class<?>[]{int.class,String.class},2,"諭");assertEquals("陳柏諭",b.draft());}
 @Test public void f_allSingleChoicesAndLockedFirst()throws Exception{DualHandTracks tracks=new DualHandTracks(Files.newBufferedReader(assets.resolve("phrase_words.tsv")),Files.newBufferedReader(assets.resolve("grid_chars.tsv")),Files.newBufferedReader(assets.resolve("readings.tsv")));List<?> choices=(List<?>)call(tracks,"singles",new Class<?>[]{String.class,int.class,String.class},"ㄩ",0,"宇");assertTrue(choices.size()>9);assertEquals("宇",((DualHandTracks.Choice)choices.get(0)).text);assertEquals(1,((DualHandTracks.Choice)choices.get(0)).end);}
}
