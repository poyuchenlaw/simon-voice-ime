package com.simon.voiceime;
import java.util.*;
final class ZhuyinWordIndex {
 static final class Entry { final String key,word,pronunciation; final long frequency; final boolean personal; Entry(String w,String p){this("",w,p,0,false);} Entry(String k,String w,String p,long f,boolean x){key=k;word=w;pronunciation=p;frequency=f;personal=x;} }
 List<Entry> lookup(String s){return Collections.emptyList();} boolean hasPrefix(String s){return false;}
 boolean isCompleteSyllable(String s){return false;} void rememberPersonal(List<String[]> p){} void rememberPersonal(Entry e){}
 List<String> continuations(String s){return Collections.emptyList();}
}
