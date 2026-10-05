package com.simon.voiceime;
public final class LiveFixture670HostTest {
 public static void main(String[] args)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(args[0],args[1]));c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],args[1]));
  try{for(String s:new String[]{"ㄉ","ㄨ","ㄛ","ˋ","ㄩ","ˊ"})c.press(s);
   System.out.println("literal="+c.previewText()+" keys="+c.sentenceKeys());
   ZhuyinInputController mapped=c.preparedSentence("ㄉㄨㄛ ㄩˊ","多餘");
   if(mapped==null)throw new AssertionError("independent corrected mapping refused");
   System.out.println("mapped="+mapped.previewText()+" keys="+mapped.sentenceKeys());mapped.close();
  }finally{c.close();}
 }
}
