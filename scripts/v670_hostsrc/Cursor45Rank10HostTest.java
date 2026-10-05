package com.simon.voiceime;
public final class Cursor45Rank10HostTest{
 public static void main(String[] a)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
  try{for(int n=0;n<45;n++)for(String k:(n%2==0?"ㄐㄧㄣ ":"ㄊㄧㄢ ").split(""))c.press(k.equals(" ")?"space":k);
   for(int boundary:new int[]{0,22,45}){c.moveCursorToPreviewBoundary(boundary);String old=c.previewText();ZhuyinInputController.TextChoice pick=null;int rank=0;
    for(ZhuyinInputController.TextChoice choice:c.textChoices())if(choice.kind.equals("char")&&++rank==10){pick=choice;break;}
    if(pick==null)throw new AssertionError("rank10 missing");System.out.println("boundary="+boundary+" rank10="+pick.label);
    if(!c.chooseTextCandidate(pick).accepted)throw new AssertionError("rank10 transaction refused boundary="+boundary);
    int index=Math.max(1,boundary)-1;String expected=old.substring(0,old.offsetByCodePoints(0,index))+pick.label+old.substring(old.offsetByCodePoints(0,index+1));
    if(!expected.equals(c.previewText())||c.phoneticSyllables().size()!=45)throw new AssertionError("other text/readings changed");
   }System.out.println("PASS 45 chars rank10 start/middle/end atomically text+readings");
  }finally{c.close();}
 }
}
