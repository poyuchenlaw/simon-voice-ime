package com.simon.voiceime;
public final class CursorInsertion670HostTest{
 static void type(ZhuyinInputController c,String s){for(char k:s.toCharArray())c.press(k==' '?"space":String.valueOf(k));}
 public static void main(String[] a)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
  try{type(c,"ㄐㄧㄣ ㄊㄧㄢ ");c.moveCursorToPreviewBoundary(1);
   for(int n=0;n<6;n++){type(c,n%2==0?"ㄐㄧㄣ ":"ㄊㄧㄢ ");String shown=c.textPreview();if(shown.codePointCount(0,shown.length())!=3+n)throw new AssertionError("completed syllables collapsed during cursor insertion at="+n+" shown="+shown);}
   if(c.phoneticSyllables().size()!=8)throw new AssertionError("inserted text lacks one reading per character");
   java.util.List<ZhuyinInputController.TextChoice> choices=c.textChoices();ZhuyinInputController.TextChoice pick=null;for(ZhuyinInputController.TextChoice x:choices)if(x.kind.equals("char")){pick=x;break;}
   if(pick==null||!c.chooseTextCandidate(pick).accepted)throw new AssertionError("inserted character choice unavailable/refused");String shown=c.textPreview();if(!shown.equals(c.press("enter").commitText))throw new AssertionError("cursor insertion Enter differs from shown");
   System.out.println("PASS cursor insertion completed syllables/text/readings/candidate/Enter");
  }finally{c.close();}
 }
}
