package com.simon.voiceime;
public class CorrectionPolicyHostTest {
 public static void main(String[] args)throws Exception {
  Class<?> cls=Class.forName("com.simon.voiceime.AiComposition$RevertedSpan");
  var ctor=cls.getDeclaredConstructor(String.class,int.class,int.class);var blocks=cls.getDeclaredMethod("blocks",String.class);
  Object span=ctor.newInstance("abcdef",2,4);
  if(!(Boolean)blocks.invoke(span,"abcdefg"))throw new AssertionError("append outside reverted span must not reapply");
  if((Boolean)blocks.invoke(span,"abcXefg"))throw new AssertionError("edit reverted span must allow new auto correction");
  if((Boolean)blocks.invoke(span,"abcdefg"))throw new AssertionError("restoring original typo after span edit must remain allowed");
  Object shifted=ctor.newInstance("abcdef",2,4);
  if(!(Boolean)blocks.invoke(shifted,"Zabcdef"))throw new AssertionError("prefix edit shifts span, does not clear suppression");
  if((Boolean)blocks.invoke(shifted,"ZabXdef"))throw new AssertionError("shifted span edit clears suppression");
  var protectedChange=AiComposition.class.getDeclaredMethod("protectedChange",String.class,String.class,Iterable.class);
  for(String old:new String[]{"二","2026","第條","不","無","未","非","否","免","勿","毋","沒","別","得","不得","應","須","可能","會","甲乙"}){
   boolean protectedToken=(Boolean)protectedChange.invoke(null,old,"顯",java.util.List.of("甲乙"));
   if(!protectedToken)throw new AssertionError("protected token auto allowed: "+old);
  }
  if((Boolean)protectedChange.invoke(null,"髓以","所以",java.util.List.of()))throw new AssertionError("ordinary repair spuriously protected");
  System.out.println("PASS reverted-span edits, unrelated edits, number/citation/polarity/installed-word gates");
 }
}
