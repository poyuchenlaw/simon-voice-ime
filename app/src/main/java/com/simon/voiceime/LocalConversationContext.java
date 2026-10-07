package com.simon.voiceime;
/** Ephemeral, on-device conversation hint. No I/O and no request serialization. */
final class LocalConversationContext {
 private static String packageName="",hint="";
 private static long captured=-1;
 static synchronized void update(String owner,String value,long now){
  clear();if(owner==null||owner.isEmpty()||value==null||value.isEmpty())return;
  packageName=owner;int count=value.codePointCount(0,value.length());
  hint=value.substring(value.offsetByCodePoints(0,Math.max(0,count-160)));captured=now;
 }
 static synchronized String text(String owner,long now){
  if(captured<0||now<captured||now-captured>=30000){clear();return "";}
  return packageName.equals(owner)?hint:"";
 }
 static synchronized void clear(){packageName="";hint="";captured=-1;}
}
