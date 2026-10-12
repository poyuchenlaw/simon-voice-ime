package com.simon.voiceime;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Spec 6891L 9.1a/d; unchanged coordinates use the committed 6.89 fixtures. */
public class App6891LayoutTest {
 private final Path assets=Path.of("src/phone/assets/dual_hand");
 private DualHandTable table()throws Exception{return new DualHandTable(Files.newBufferedReader(assets.resolve("dual_hand_map.tsv")),Files.newBufferedReader(assets.resolve("left_hand.tsv")),Files.newBufferedReader(assets.resolve("draft_chars.tsv")));}
 private List<String> fixture(String name)throws Exception{return Files.readAllLines(Path.of("src/testPhone/resources/app6891",name));}
 private DualHandState.Output stroke(DualHandState s,String key,String dir){var b=s.begin(key,100);s.end(b,dir,false);return s.drain(1000).get(0);}
 @Test public void a_newYuKeyAndStableYiWu()throws Exception{
  var t=table();assertEquals("ㄩ",t.left("6","right"));assertEquals("ㄧ",t.left("4","right"));assertEquals("ㄨ",t.left("3","right"));
  var s=new DualHandState(t);assertFalse(stroke(s,"6","right").empty);assertEquals("ㄩ",s.pending());stroke(s,"B","tap");assertEquals(List.of("ㄩㄝ"),s.syllables());
 }
 @Test public void a_oldYuDirectionIsEmptyAndPreservesPending()throws Exception{
  var s=new DualHandState(table());assertEquals("",table().left("4","down"));assertTrue(stroke(s,"4","down").empty);assertNull(s.pending());
  stroke(s,"4","right");assertTrue(stroke(s,"4","down").empty);assertEquals("ㄧ",s.pending());assertTrue(s.syllables().isEmpty());
 }
 @Test public void d_allRightOutputsMatch689AndOnlyTwoLeftCoordinatesChange()throws Exception{
  var t=table();int right=0,left=0;
  for(String row:fixture("dual_hand_map.tsv").subList(1,876)){String[] c=row.split("\t",-1);assertEquals(row,c[3],t.right(c[0],c[1],c[2]));if(c[0].equals("Ø"))assertEquals(c[3],t.right(null,c[1],c[2]));right++;}
  for(String row:fixture("left_hand.tsv").subList(1,31)){String[] c=row.split("\t",-1);String want=c[0].equals("4")&&c[1].equals("down")?"":c[0].equals("6")&&c[1].equals("right")?"ㄩ":c[2];assertEquals(row,want,t.left(c[0],c[1]));left++;}
  assertEquals(875,right);assertEquals(30,left);assertEquals(31,Files.readAllLines(assets.resolve("left_hand.tsv")).size());
 }
}
