package com.simon.voiceime;
public final class LongChoices670HostTest{
 public static void main(String[] a)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
  try{for(int n=0;n<120;n++)for(String k:(n%2==0?"ㄐㄧㄣ ":"ㄊㄧㄢ ").split(""))c.press(k.equals(" ")?"space":k);
   if(c.textChoices().isEmpty())throw new AssertionError("120-character text has no correction choices");
   String old=c.previewText();ZhuyinInputController.TextChoice pick=null;for(ZhuyinInputController.TextChoice choice:c.textChoices())if(choice.kind.equals("char")&&!choice.label.equals(old.substring(old.length()-1))){pick=choice;break;}
   if(pick==null||!c.chooseTextCandidate(pick).accepted)throw new AssertionError("long character replacement refused");
   if(!c.previewText().equals(old.substring(0,old.length()-1)+pick.label))throw new AssertionError("long replacement changed other text");
   c.press("backspace");if(c.previewText().codePointCount(0,c.previewText().length())!=119)throw new AssertionError("120-character whole deletion refused");
   System.out.println("PASS long choices and whole delete");
  }finally{c.close();}
 }
}
