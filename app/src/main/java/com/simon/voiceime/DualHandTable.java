package com.simon.voiceime;

import java.io.*;
import java.util.*;

/** Read the W1 TSV contract without Android or a second phonetic allocation table. */
public final class DualHandTable {
    public static final String[] DIRECTIONS={"tap","up","left","down","right"};
    public static final String[] RIGHT_KEYS={"A","B","C","D","E","F","Ø"};
    public static final String[] STATES={"ㄅ","ㄆ","ㄇ","ㄈ","ㄉ","ㄊ","ㄋ","ㄌ","ㄍ","ㄎ","ㄏ","ㄐ","ㄑ","ㄒ","ㄓ","ㄔ","ㄕ","ㄖ","ㄗ","ㄘ","ㄙ","Ø","ㄧ","ㄨ","ㄩ"};
    private final Map<String,String> right=new HashMap<>(), left=new HashMap<>(), drafts=new HashMap<>();
    public DualHandTable(Reader map, Reader initial, Reader chars) throws IOException {
        read(map,"state\tkey\tgesture\tsyllable",4,v->{
            if(!Arrays.asList(STATES).contains(v[0])||!Arrays.asList(RIGHT_KEYS).contains(v[1])||!direction(v[2]))throw new IOException("Invalid map coordinate");
            put(right,v[0]+":"+v[1]+":"+v[2],v[3]);
        });
        read(initial,"key\tgesture\tstate",3,v->{
            if(!v[0].matches("[1-6]")||!direction(v[1])||!v[2].isEmpty()&&!Arrays.asList(STATES).contains(v[2]))throw new IOException("Invalid left coordinate");
            put(left,v[0]+":"+v[1],v[2]);
        });
        read(chars,"syllable\tchar",2,v->{
            if(!v[0].matches("[ㄅ-ㄩ]+")||v[1].codePointCount(0,v[1].length())!=1)throw new IOException("Invalid draft row");
            put(drafts,v[0],v[1]);
        });
        if(right.size()!=875||left.size()!=30||drafts.size()!=415)throw new IOException("Incomplete dual-hand assets");
        for(String syllable:right.values())if(!syllable.isEmpty()&&!drafts.containsKey(syllable))throw new IOException("Missing draft character");
    }
    private interface Row {void accept(String[] v)throws IOException;}
    private static void read(Reader reader,String header,int columns,Row row)throws IOException {
        BufferedReader in=new BufferedReader(reader);
        if(!header.equals(in.readLine()))throw new IOException("Invalid TSV header");
        String line;while((line=in.readLine())!=null){String[] v=line.split("\t",-1);if(v.length!=columns)throw new IOException("Invalid TSV columns");row.accept(v);}
    }
    private static void put(Map<String,String> map,String key,String value)throws IOException {
        if(map.putIfAbsent(key,value)!=null)throw new IOException("Duplicate TSV coordinate");
    }
    public static boolean direction(String value){return Arrays.asList(DIRECTIONS).contains(value);}
    public static boolean isLeft(String key){return key!=null&&key.matches("[1-6]");}
    public static boolean isRight(String key){return Arrays.asList(RIGHT_KEYS).contains(key);}
    public String left(String key,String direction){return left.getOrDefault(key+":"+direction,"");}
    public String right(String state,String key,String direction){return right.getOrDefault((state==null?"Ø":state)+":"+key+":"+direction,"");}
    public String draft(String syllable){String value=drafts.get(syllable);if(value==null)throw new IllegalArgumentException("Unknown syllable");return value;}
}
