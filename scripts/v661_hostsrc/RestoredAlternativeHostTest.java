package com.simon.voiceime;
import java.util.*;
public class RestoredAlternativeHostTest {
 public static void main(String[] args)throws Exception {
  try(SingleRimeZhuyinEngine e=new SingleRimeZhuyinEngine(args[0],args[1])) {
   e.nativeEngine.restore(Arrays.asList("ㄇㄧㄥˊ","ㄒㄧㄢˇ","ㄙㄨㄛˇ","ㄧˇ"),"名顯所以");
   ZhuyinInputController c=new ZhuyinInputController(e);var state=c.moveCursorToKey(4);
   System.out.println("range="+state.targetStart+".."+state.targetEnd+" candidates="+state.candidates.subList(0,Math.min(15,state.candidates.size())));
   if(!state.candidates.contains("名顯")||state.targetEnd-state.targetStart!=2)throw new AssertionError("restored alternative must retain whole-word suggestions");
  }
 }
}
