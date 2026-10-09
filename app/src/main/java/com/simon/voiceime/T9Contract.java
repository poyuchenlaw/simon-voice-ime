package com.simon.voiceime;
import java.util.List;
/** Enforce PROMPT_t9_semantic_coloring_v1 at both trust boundaries. */
public final class T9Contract {
 public static final String PUNCT="，。？！、：；";
 public static String bare(String s){StringBuilder b=new StringBuilder();s.codePoints().filter(c->PUNCT.indexOf(c)<0).forEach(b::appendCodePoint);return b.toString();}
 public static String filter(String draft,List<String> alt,String raw){
  if(raw==null)return draft;String first=raw.split("\\n",2)[0];int[] expected=draft.codePoints().toArray(),actual=bare(first).codePoints().toArray();
  if(actual.length!=expected.length||alt.size()!=expected.length)return draft;
  for(int i=0;i<actual.length;i++){final int value=actual[i];if(value!=expected[i]&&alt.get(i).codePoints().noneMatch(c->c==value))return draft;}
  StringBuilder out=new StringBuilder();boolean previous=true;
  for(int c:first.codePoints().toArray()){boolean p=PUNCT.indexOf(c)>=0;if(!p||!previous)out.appendCodePoint(c);previous=p;}
  return out.toString();
 }
 public static int changed(String draft,String sentence){int[] a=draft.codePoints().toArray(),b=bare(sentence).codePoints().toArray();if(a.length!=b.length)return a.length;int n=0;for(int i=0;i<a.length;i++)if(a[i]!=b[i])n++;return n;}
 public static String draftPunctuation(String draft,String sentence){int[] chars=draft.codePoints().toArray();StringBuilder out=new StringBuilder();int i=0;for(int c:sentence.codePoints().toArray()){if(PUNCT.indexOf(c)>=0)out.appendCodePoint(c);else if(i<chars.length)out.appendCodePoint(chars[i++]);}return i==chars.length?out.toString():draft;}
}
