package com.simon.voiceime;
public final class Recovery670HostTest {
 static void type(ZhuyinInputController c,String s){for(int i=0;i<s.length();i++){
  ZhuyinInputController.State state=c.press(s.charAt(i)==' '?"space":s.substring(i,i+1));
  if(!state.commitText.isEmpty())throw new AssertionError("typing must retain whole composition; native forced partial commit");
 }}
 public static void main(String[] args)throws Exception{
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(args[0],args[1]));c.setTextLayout(true);
  try{
   int prefix=Integer.parseInt(args[2]);for(int n=0;n<prefix;n++)type(c,n%2==0?"ㄐㄧㄣ ":"ㄊㄧㄢ ");
   for(String syllable:new String[]{"ㄟˇ","ㄜ˙","ㄎㄢˋ","ㄉㄜ˙","ㄔㄨ ","ㄌㄞˊ"}){type(c,syllable);System.out.println("STEP chars="+c.previewText().codePointCount(0,c.previewText().length())+" readings="+c.phoneticSyllables().size());}
   if(WrapCommit670HostTest.phonetic(c.previewText()))throw new AssertionError("completed suffix remains phonetic after invalid syllable");
   type(c,"ㄐㄧㄣ ㄊㄧㄢ ");
   String shown=c.textPreview();int count=shown.codePointCount(0,shown.length());
   if(count!=prefix+8)throw new AssertionError("expected "+(prefix+8)+" characters, got="+count);
   if(c.phoneticSyllables().size()!=prefix+8)throw new AssertionError("one reading per character after recovery");
   if(!shown.endsWith("今天"))throw new AssertionError("following normal syllables must decode again");
   if(!shown.equals(c.press("enter").commitText))throw new AssertionError("Enter must match shown recovery");
   System.out.println("PASS prefix="+prefix+" recovery=6 normal=2; no early commit; text/readings="+count+" zero phonetic");
  }finally{c.close();}
 }
}
