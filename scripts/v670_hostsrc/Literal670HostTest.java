package com.simon.voiceime;
public final class Literal670HostTest {
 static void today(ZhuyinInputController c){for(char cp:"ㄐㄧㄣ ㄊㄧㄢ ".toCharArray())c.press(cp==' '?"space":String.valueOf(cp));}
 public static void main(String[] args)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(args[0],args[1]));c.setTextLayout(true);
  try{if(!"ㄅ".equals(c.punctuation("ㄅ").commitText))throw new AssertionError("explicit literal immediate commit must survive");
   today(c);c.punctuation("ㄅ");if(!"今天ㄅ".equals(c.textPreview())||!"今天ㄅ".equals(c.press("enter").commitText))throw new AssertionError("explicit literal zhuyin inside composition must survive");
   today(c);c.punctuation("😀");if(!"今天😀".equals(c.textPreview())||!"今天😀".equals(c.press("enter").commitText))throw new AssertionError("supplementary literal must survive");
   System.out.println("PASS explicit literal immediate/in-composition zhuyin exception + supplementary literal commit");
  }finally{c.close();}
 }
}
