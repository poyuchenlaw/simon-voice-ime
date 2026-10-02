package com.simon.voiceime;
public class InsertionCaretHost {
 public static void main(String[] a)throws Exception{
  java.nio.file.Files.createDirectories(java.nio.file.Path.of(a[1]));
  try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])){ZhuyinInputController c=new ZhuyinInputController(e);
   for(String k:new String[]{"ㄎ","ˇ","ㄧ","ˇ"})c.press(k);
   var f=c.moveCursorToKey(1);System.out.println("incomplete="+c.previewText()+" first="+f.candidates.subList(0,Math.min(8,f.candidates.size())));
   int rank=f.candidates.indexOf("可");if(rank<0||rank>=8)throw new AssertionError("missing-key repair must be in first eight");
   c.chooseCandidate(rank);if(!c.previewText().equals("可以"))throw new AssertionError("insert repair preserves suffix: "+c.previewText());
   if(!c.sentenceKeys().equals("ㄎㄜˇㄧˇ"))throw new AssertionError("inserted reading key order: "+c.sentenceKeys());
   System.out.println("PASS missing-key repair, sentence conversion, suffix and key order");
  }
 }
}
