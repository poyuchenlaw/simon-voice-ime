package com.simon.voiceime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Explicit manual choices only; isolated from the toned 41-key/Rime vocabulary. */
public final class DualHandMemory {
    public static final int LIMIT=2000;
    public static final class Entry {
        public final String text;
        public final long count,lastUsed;
        Entry(String text,long count,long lastUsed){this.text=text;this.count=count;this.lastUsed=lastUsed;}
    }
    private final File file;
    private Map<String,Entry> entries=new LinkedHashMap<>();
    public DualHandMemory(File file)throws IOException {
        this.file=file;if(!file.isFile())return;
        if(file.length()>1024*1024)throw new IOException("Personal memory too large");
        try(BufferedReader in=Files.newBufferedReader(file.toPath(),StandardCharsets.UTF_8)){
            String line;while((line=in.readLine())!=null){
                String[] v=line.split("\t",-1);if(v.length!=4)throw new IOException("Invalid personal memory row");
                List<String> sounds=Arrays.asList(v[0].split(" ",-1));
                try{
                    long count=Long.parseLong(v[2]),used=Long.parseLong(v[3]);
                    if(!valid(sounds,v[1])||count<1||used<0||entries.size()>=LIMIT||entries.putIfAbsent(v[0]+"\t"+v[1],new Entry(v[1],count,used))!=null)throw new IOException("Invalid personal memory");
                }catch(NumberFormatException invalid){throw new IOException("Invalid personal memory number",invalid);}
            }
        }
    }
    public File file(){return file;}
    private static boolean valid(List<String> sounds,String text){
        return sounds!=null&&!sounds.isEmpty()&&sounds.size()<=4&&text!=null&&text.codePointCount(0,text.length())==sounds.size()
            &&text.codePoints().allMatch(cp->Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)
            &&sounds.stream().allMatch(s->s!=null&&s.matches("[ㄅ-ㄩ]{1,3}"));
    }
    public synchronized List<Entry> find(List<String> sounds){
        String prefix=String.join(" ",sounds)+"\t";List<Entry> found=new ArrayList<>();
        for(Map.Entry<String,Entry> e:entries.entrySet())if(e.getKey().startsWith(prefix))found.add(e.getValue());
        found.sort(Comparator.comparingLong((Entry e)->e.count).reversed().thenComparing(Comparator.comparingLong((Entry e)->e.lastUsed).reversed()).thenComparing(e->e.text));return found;
    }
    public boolean remember(List<String> sounds,String text,boolean protectedField)throws IOException{return remember(sounds,text,protectedField,System.currentTimeMillis());}
    synchronized boolean remember(List<String> sounds,String text,boolean protectedField,long now)throws IOException {
        if(protectedField||now<0||!valid(sounds,text))return false;
        Map<String,Entry> next=new LinkedHashMap<>(entries);String key=String.join(" ",sounds)+"\t"+text;Entry old=next.get(key);
        next.put(key,new Entry(text,old==null?1:old.count==Long.MAX_VALUE?Long.MAX_VALUE:old.count+1,now));
        if(next.size()>LIMIT){String oldest=Collections.min(next.keySet(),Comparator.comparingLong((String k)->next.get(k).lastUsed).thenComparing(k->k));next.remove(oldest);}
        File temp=new File(file.getParentFile(),file.getName()+".tmp");
        try(BufferedWriter out=Files.newBufferedWriter(temp.toPath(),StandardCharsets.UTF_8)){
            for(Map.Entry<String,Entry> e:next.entrySet())out.write(e.getKey()+"\t"+e.getValue().count+"\t"+e.getValue().lastUsed+"\n");
        }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        entries=next;return true;
    }
}
