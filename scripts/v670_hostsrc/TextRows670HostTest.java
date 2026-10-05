package com.simon.voiceime;
public final class TextRows670HostTest {
 static void keys(ZhuyinInputController c,String s){for(int i=0;i<s.length();i++)c.press(s.charAt(i)==' '?"space":s.substring(i,i+1));}
 public static void main(String[] args)throws Exception {
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(args[0],args[1]));c.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],args[1]));
  try{ZhuyinInputController.class.getDeclaredMethod("setTextLayout",boolean.class).invoke(c,true);}catch(NoSuchMethodException absent){}
  keys(c,"ㄐㄧㄣ ㄊㄧㄢ ");if(!"今天".equals(c.previewText()))throw new AssertionError("literal today fixture");
  c.press("backspace");if(!"今".equals(c.previewText()))throw new AssertionError("whole character deletion expected 今, got "+c.previewText());
  if(!c.sentenceKeys().equals("ㄐㄧㄣ "))throw new AssertionError("reading must delete together: "+c.sentenceKeys());
  keys(c,"ㄊㄧㄢ ");
  if(!c.moveCursorToPreviewBoundary(0).accepted)throw new AssertionError("boundary0 must be selectable");
  java.util.List<ZhuyinInputController.TextChoice> choices=c.textChoices();
  System.out.println("CANDIDATES "+choices.size());
  int words=0,chars=0;for(ZhuyinInputController.TextChoice choice:choices){if("char".equals(choice.kind))chars++;else words++;}
  System.out.println("ROWS words="+words+" chars="+chars);
  if(words==0||chars==0)throw new AssertionError("two candidate kinds required: words="+words+" chars="+chars);
  ZhuyinInputController.TextChoice pick=null;for(ZhuyinInputController.TextChoice choice:choices)if("char".equals(choice.kind)&&!choice.label.equals("今")){pick=choice;break;}
  if(pick!=null){try(RimeZhuyinEngine probe=new RimeZhuyinEngine(args[0],args[1])){probe.prepareSentence(pick.keys,pick.witness);probe.moveCursorToPreviewCharacter(pick.boundary-1);System.out.println("NATIVE BEFORE "+probe.previewText()+" idx="+pick.index+" label="+probe.regroupLabels().get(pick.index));probe.chooseRegroup(pick.index);System.out.println("NATIVE AFTER "+probe.previewText()+" keys="+probe.sentenceKeys());}}
  if(pick==null||!c.chooseTextCandidate(pick).accepted)throw new AssertionError("character transaction must apply");
  System.out.println("PICK "+pick.label+" => "+c.previewText());
  if(!c.previewText().equals(pick.label+"天"))throw new AssertionError("picked char must replace first char only");
  if(!c.previewText().endsWith("天")||c.phoneticSyllables().size()!=2)throw new AssertionError("replacement must preserve suffix and readings");
  if(c.chooseTextCandidate(pick).accepted)throw new AssertionError("stale candidate must be rejected");
  c.clear();keys(c,"ㄐㄧㄣ ㄅ");if(c.textPreview().codePoints().anyMatch(x->x>=0x3105&&x<=0x312f))throw new AssertionError("provisional must show no zhuyin");
  System.out.println("BEFORE PROVISIONAL text="+c.previewText()+" keys="+c.sentenceKeys()+" reading="+c.phoneticSyllables());c.press("backspace");System.out.println("AFTER PROVISIONAL text="+c.previewText()+" keys="+c.sentenceKeys()+" reading="+c.phoneticSyllables());if(!c.previewText().equals("金")||!c.sentenceKeys().equals("ㄐㄧㄣ "))throw new AssertionError("provisional deletes entire syllable");
  c.clear();keys(c,"ˋˋ");System.out.println("UNPARSED "+c.previewText()+" / "+c.sentenceKeys()+" / "+c.phoneticSyllables());
  if(!c.press("backspace").accepted||!c.previewText().isEmpty()||!c.sentenceKeys().isEmpty())throw new AssertionError("unparsed unfinished syllable must be deleted whole");
  c.close();
  for(int i=0;i<20;i++){
   RimeZhuyinEngine e=new RimeZhuyinEngine(args[0],args[1]);c=new ZhuyinInputController(e);c.setTextLayout(true);c.setLearningEnabled(false);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],args[1]));
   String literal="今天".repeat(10),reading="ㄐㄧㄣ ㄊㄧㄢ ".repeat(10);
   if(!e.prepareSentence(reading,literal))throw new AssertionError("known twenty-character fixture");
   int at=new int[]{1,20,10,2,19}[i%5];
   if(!c.moveCursorToPreviewBoundary(at).accepted)throw new AssertionError("boundary must be selectable "+at);String expected=literal.substring(0,at-1)+literal.substring(at);
   if(!c.press("backspace").accepted||!expected.equals(c.previewText()))throw new AssertionError("mixed deletion "+i+" at="+at+" got="+c.previewText());
   if(c.phoneticSyllables().size()!=19)throw new AssertionError("mixed deletion reading count "+i);
   c.close();
  }
  System.out.println("PASS mixed-position deletion 20/20 text+reading");
  System.out.println("PASS whole-character deletion text+reading");
 }
}
