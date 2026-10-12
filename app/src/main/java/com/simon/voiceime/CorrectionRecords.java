package com.simon.voiceime;
import java.util.*;
/** Bounded field-local readings; never serialized or included in diagnostics. */
final class CorrectionRecords {
    static final int RECENT_COMMIT_MAX=2000;
    final Map<Integer,String> positions=new LinkedHashMap<>(),glyphs=new LinkedHashMap<>();
    static String untoned(String sound){return sound==null?"":sound.replaceAll("[ ˉˊˇˋ˙]","");}
    void clear(){positions.clear();glyphs.clear();}
    int size(){return positions.size();}
    String reading(int at,String ch){return ch.equals(glyphs.get(at))?positions.get(at):null;}
    void record(int at,String text,List<String> sounds,DualHandTracks tracks,boolean protectedField){
        record(at,text,sounds,tracks,protectedField,true);
    }
    void record(int at,String text,List<String> sounds,DualHandTracks tracks,boolean protectedField,boolean verifyReading){
        if(protectedField){clear();return;}
        for(Integer key:new ArrayList<>(positions.keySet()))if(key<at+text.length()&&key+glyphs.get(key).length()>at){positions.remove(key);glyphs.remove(key);}
        if(verifyReading&&tracks==null||sounds==null||sounds.size()!=text.codePointCount(0,text.length()))return;
        int index=0;
        for(int cp:text.codePoints().toArray()){
            String ch=new String(Character.toChars(cp)),original=sounds.get(index++),sound=untoned(original);
            positions.remove(at);glyphs.remove(at);
            if(!verifyReading&&original!=null||verifyReading&&tracks.readings(ch).contains(sound)){positions.put(at,sound);glyphs.put(at,ch);}
            at+=ch.length();
        }
        while(size()>RECENT_COMMIT_MAX){Integer oldest=positions.keySet().iterator().next();positions.remove(oldest);glyphs.remove(oldest);}
    }
    void rebase(String before,String after,int left,int right,String value){
        rebase(before,after,left,right,value,0);
    }
    void rebase(String before,String after,int left,int right,String value,int offset){
        int a=left-offset,b=right-offset;
        if(a<0||b<a||b>before.length()||!(before.substring(0,a)+value+before.substring(b)).equals(after)){clear();return;}
        int delta=value.length()-(right-left);Map<Integer,String> moved=new LinkedHashMap<>(),letters=new LinkedHashMap<>();
        for(Map.Entry<Integer,String> e:positions.entrySet()){
            int at=e.getKey();String ch=glyphs.get(at);if(at<right&&at+ch.length()>left)continue;
            int next=at>=right?at+delta:at;moved.put(next,e.getValue());letters.put(next,ch);
        }
        positions.clear();positions.putAll(moved);glyphs.clear();glyphs.putAll(letters);
    }
}
