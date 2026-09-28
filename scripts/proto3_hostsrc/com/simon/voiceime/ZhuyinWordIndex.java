package com.simon.voiceime;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class ZhuyinWordIndex {
 static final class Entry { final String key,word,pronunciation; final long frequency; final boolean personal; Entry(String w,String p){this("",w,p,0,false);} Entry(String k,String w,String p,long f,boolean x){key=k;word=w;pronunciation=p;frequency=f;personal=x;} }
 private final List<Entry> entries;
 private final NavigableMap<String,List<Entry>> byKey=new TreeMap<>();
 ZhuyinWordIndex(){entries=Collections.emptyList();}
 ZhuyinWordIndex(List<Entry> es){entries=es;for(Entry e:es)byKey.computeIfAbsent(e.key,k->new ArrayList<>()).add(e);}
 static ZhuyinWordIndex read(File f)throws Exception{List<Entry> es=new ArrayList<>();try(BufferedReader r=new BufferedReader(new InputStreamReader(new FileInputStream(f),StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null){String[] p=l.split("\t",5);if(p.length==5)es.add(new Entry(p[0],p[1],p[2],Long.parseLong(p[3]),"1".equals(p[4])));}}return new ZhuyinWordIndex(es);}
 List<Entry> lookup(String s){List<Entry> out=new ArrayList<>();for(List<Entry> es:byKey.subMap(s,true,s+"\uffff",true).values())out.addAll(es);out.sort((a,b)->Long.compare(b.frequency,a.frequency));if(out.size()>100)return new ArrayList<>(out.subList(0,100));return out;} boolean hasPrefix(String s){return !byKey.subMap(s,true,s+"\uffff",true).isEmpty();}
 List<Entry> segmented(String s){List<List<Entry>> paths=new ArrayList<>();segment(s,0,new ArrayList<>(),paths);List<Entry> out=new ArrayList<>();for(List<Entry> p:paths){if(p.size()<2)continue;StringBuilder w=new StringBuilder(),pr=new StringBuilder();double f=0;for(Entry e:p){w.append(e.word);if(pr.length()>0)pr.append(' ');pr.append(e.pronunciation);f+=Math.log(e.frequency>1000000000L?1L:Math.max(1,e.frequency));}out.add(new Entry(s,w.toString(),pr.toString(),Math.max(1,Math.round(Math.exp(Math.min(40,f/p.size())))),false));}out.sort((a,b)->Long.compare(b.frequency,a.frequency));if(out.size()>100)return new ArrayList<>(out.subList(0,100));return out;}
 private List<Entry> exact(String s){List<Entry> out=new ArrayList<>(byKey.getOrDefault(s,Collections.emptyList()));out.sort((a,b)->Long.compare(b.frequency,a.frequency));if(out.size()>50)return new ArrayList<>(out.subList(0,50));return out;}
 private void segment(String s,int o,List<Entry> path,List<List<Entry>> out){if(out.size()>=5000)return;if(o==s.length()){if(path.size()>1)out.add(new ArrayList<>(path));return;}for(int e=s.length();e>=o+2;e--)for(Entry x:exact(s.substring(o,e))){path.add(x);segment(s,e,path,out);path.remove(path.size()-1);if(out.size()>=5000)return;}}
 boolean hasSegmentedPrefix(String s){return hasSegmentedPrefix(s,new HashSet<>());} private boolean hasSegmentedPrefix(String s,Set<String> dead){if(dead.contains(s))return false;for(int i=2;i<s.length();i++)if(!exact(s.substring(0,i)).isEmpty()&&(hasPrefix(s.substring(i))||hasSegmentedPrefix(s.substring(i),dead)))return true;dead.add(s);return false;}
 boolean isCompleteSyllable(String s){return false;} void rememberPersonal(List<String[]> p){} void rememberPersonal(Entry e){}
 List<String> continuations(String s){return Collections.emptyList();}
 static ZhuyinWordIndex fromFile(File f)throws Exception{return read(f);}
}
