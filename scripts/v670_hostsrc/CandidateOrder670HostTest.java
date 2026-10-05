package com.simon.voiceime;
import java.util.*;
public class CandidateOrder670HostTest {
 public static void main(String[] a)throws Exception {
  try(RimeZhuyinEngine oldMenu=new RimeZhuyinEngine(a[0],a[1])){
   ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
   try {for(char k:"ㄐㄧㄣ ㄊㄧㄢ ".toCharArray())c.press(k==' '?"space":String.valueOf(k));
    for(int boundary:new int[]{1,2}){
     oldMenu.prepareSentence(c.sentenceKeys(),c.previewText());oldMenu.moveCursorToPreviewWord(boundary-1);int[] focused=oldMenu.previewEditRange();oldMenu.regroup(boundary);
     List<String> expected=new ArrayList<>();List<String> nativeLabels=oldMenu.regroupLabels();List<int[]> ranges=oldMenu.optionRanges();for(int n=0;n<nativeLabels.size();n++){String label=nativeLabels.get(n);int[] r=ranges.get(n);if(r[0]==focused[0]&&r[1]==focused[1]&&!label.contains("｜")&&!label.isEmpty()&&expected.size()<5)expected.add(label);}
     c.moveCursorToPreviewBoundary(boundary);List<String> actual=new ArrayList<>();for(ZhuyinInputController.TextChoice p:c.textChoices())if(p.kind.equals("word")&&actual.size()<5)actual.add(p.label);
     System.out.println("boundary="+boundary+" eligible native prefix up to5="+expected+" new word first5="+actual);
     if(expected.isEmpty()||actual.size()<expected.size()||!expected.equals(actual.subList(0,expected.size())))throw new AssertionError("word row must preserve eligible native word order before supplemental focus words; neighbouring spans excluded");
    }
   }finally{c.close();}
  }
 }
}
