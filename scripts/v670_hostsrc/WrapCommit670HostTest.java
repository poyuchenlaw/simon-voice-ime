package com.simon.voiceime;
public final class WrapCommit670HostTest {
 static boolean phonetic(String s){return s.codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||cp>=0x31a0&&cp<=0x31bf||"ˊˇˋ˙ˉ".indexOf(cp)>=0);}
 static void type(ZhuyinInputController c,String s){for(int i=0;i<s.length();i++)c.press(s.charAt(i)==' '?"space":s.substring(i,i+1));}
 public static void main(String[] args)throws Exception{
  int length=Integer.parseInt(args[2]);
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(args[0],args[1]));
  try{ZhuyinInputController.class.getDeclaredMethod("setTextLayout",boolean.class).invoke(c,true);}catch(NoSuchMethodException old){}
  try{
   for(int n=0;n<length;n++){
    type(c,n%2==0?"ㄐㄧㄣ ":"ㄊㄧㄢ ");
    String raw=c.previewText();if(phonetic(raw))throw new AssertionError("completed overflow is phonetic at syllable="+(n+1)+" chars="+raw.codePointCount(0,raw.length()));
    if(raw.codePointCount(0,raw.length())!=n+1)throw new AssertionError("lost syllable at="+(n+1));
   }
   type(c,args.length>3?args[3]:"ˋˋ");
   String shown;try{shown=(String)ZhuyinInputController.class.getDeclaredMethod("textPreview").invoke(c);}catch(NoSuchMethodException old){shown=c.previewText();}
   String committed=c.press("enter").commitText;
   if(phonetic(committed))throw new AssertionError("Enter commits phonetic trailing syllable; completed="+length);
   if(!shown.equals(committed))throw new AssertionError("Enter differs from shown provisional");
   System.out.println("PASS length="+length+" completed text, provisional Enter identical, zero phonetic");
  }finally{c.close();}
 }
}
