package com.simon.voiceime;

import java.io.*;
import java.util.*;

/** Offline phrase and correction rules; only the draft is ever eligible for AI. */
public final class DualHandTracks {
    public static final long TIMEOUT_MS=8000;
    public static final class Choice {
        public final String text;
        public final int start,end;
        public final long frequency;
        public final boolean personal;
        public final long lastUsed;
        public final List<String> syllables;
        Choice(String text,int start,int end,long frequency){this(text,start,end,frequency,false,0,Collections.emptyList());}
        Choice(String text,int start,int end,long frequency,boolean personal,long lastUsed,List<String> syllables){this.text=text;this.start=start;this.end=end;this.frequency=frequency;this.personal=personal;this.lastUsed=lastUsed;this.syllables=Collections.unmodifiableList(new ArrayList<>(syllables));}
    }
    private final DualHandMemory memory;
    private final Map<String,List<Choice>> phrases=new HashMap<>();
    private final Set<String> words=new HashSet<>();
    private final Map<String,List<String>> grid=new HashMap<>();
    private final Map<String,Map<String,Long>> readings=new HashMap<>();
    public DualHandTracks(Reader words,Reader chars,Reader sounds)throws IOException {this(words,chars,sounds,null);}
    public DualHandTracks(Reader words,Reader chars,Reader sounds,DualHandMemory memory)throws IOException {
        this.memory=memory;
        rows(words,"syllables\tword\tfrequency\trank",4,v->{
            int count=v[0].split(" ").length;if(count<2||count>4||count!=v[1].codePointCount(0,v[1].length()))throw new IOException("Invalid phrase");
            this.words.add(v[1]);long frequency=number(v[2]);int rank=(int)number(v[3]);if(rank<1)throw new IOException("Invalid rank");
            if(rank<=3)phrases.computeIfAbsent(v[0],k->new ArrayList<>()).add(new Choice(v[1],0,count,frequency));
        });
        rows(chars,"syllable\tchar1\tchar2\tchar3\tchar4\tchar5\tchar6\tchar7\tchar8\tchar9",10,v->{
            List<String> values=new ArrayList<>();for(int i=1;i<10;i++)if(!v[i].isEmpty()){
                if(v[i].codePointCount(0,v[i].length())!=1)throw new IOException("Invalid grid glyph");values.add(v[i]);
            }
            if(grid.putIfAbsent(v[0],values)!=null)throw new IOException("Duplicate grid");
        });
        rows(sounds,"char\tsyllable\tfrequency",3,v->{
            if(v[0].codePointCount(0,v[0].length())!=1||!v[1].matches("[ㄅ-ㄩ]+"))throw new IOException("Invalid reading");
            if(readings.computeIfAbsent(v[0],k->new HashMap<>()).putIfAbsent(v[1],number(v[2]))!=null)throw new IOException("Duplicate reading");
        });
        for(List<Choice> values:phrases.values())values.sort(ORDER);
        if(grid.size()!=415||phrases.isEmpty()||readings.isEmpty())throw new IOException("Incomplete tracks");
    }
    private interface Row {void accept(String[] values)throws IOException;}
    private static void rows(Reader input,String header,int columns,Row row)throws IOException {
        BufferedReader in=new BufferedReader(input);if(!header.equals(in.readLine()))throw new IOException("Invalid TSV header");
        String line;while((line=in.readLine())!=null){String[] values=line.split("\t",-1);if(values.length!=columns)throw new IOException("Invalid TSV columns");row.accept(values);}
    }
    private static long number(String value)throws IOException {try{long n=Long.parseLong(value);if(n<0)throw new IOException("Negative frequency/rank");return n;}catch(NumberFormatException invalid){throw new IOException("Invalid number",invalid);}}
    private static final Comparator<Choice> ORDER=Comparator.comparing((Choice c)->c.personal).reversed().thenComparing(Comparator.comparingLong((Choice c)->c.frequency).reversed()).thenComparing(Comparator.comparingLong((Choice c)->c.lastUsed).reversed()).thenComparing(c->c.text);
    public List<Choice> candidates(List<String> syllables){
        if(syllables.size()<2||syllables.size()>4)return Collections.emptyList();
        for(int count=syllables.size();count>=2;count--){List<Choice> found=choices(String.join(" ",syllables.subList(0,count)),0);if(!found.isEmpty())return Collections.unmodifiableList(found.subList(0,Math.min(3,found.size())));}
        return Collections.emptyList();
    }
    private List<Choice> choices(String key,int start){
        List<String> sounds=Arrays.asList(key.split(" "));List<Choice> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        if(memory!=null)for(DualHandMemory.Entry e:memory.find(sounds)){result.add(new Choice(e.text,start,start+sounds.size(),e.count,true,e.lastUsed,sounds));seen.add(e.text);}
        for(Choice c:phrases.getOrDefault(key,Collections.emptyList()))if(seen.add(c.text))result.add(new Choice(c.text,start,start+sounds.size(),c.frequency,false,0,sounds));
        result.sort(ORDER);return result;
    }
    public List<Choice> singles(String sound,int index,String locked){
        List<Choice> result=new ArrayList<>();Map<String,DualHandMemory.Entry> personal=new HashMap<>();
        if(memory!=null)for(DualHandMemory.Entry e:memory.find(Collections.singletonList(sound)))personal.put(e.text,e);
        for(Map.Entry<String,Map<String,Long>> entry:readings.entrySet())if(entry.getValue().containsKey(sound)){
            DualHandMemory.Entry e=personal.get(entry.getKey());result.add(new Choice(entry.getKey(),index,index+1,entry.getValue().get(sound),e!=null,e==null?0:e.lastUsed,Collections.singletonList(sound)));
        }
        result.sort(Comparator.comparing((Choice c)->!c.text.equals(locked)).thenComparing(c->!c.personal)
            .thenComparing(Comparator.comparingLong((Choice c)->personal.containsKey(c.text)?personal.get(c.text).count:0).reversed())
            .thenComparing(Comparator.comparingLong((Choice c)->c.frequency).reversed()).thenComparingInt(c->c.text.codePointAt(0)));
        return Collections.unmodifiableList(result);
    }
    public List<String> readings(String character){Map<String,Long> found=readings.get(character);return found==null?Collections.emptyList():new ArrayList<>(new TreeSet<>(found.keySet()));}
    public List<Integer> mismatches(String text,List<String> syllables){
        if(text.codePointCount(0,text.length())!=syllables.size())throw new IllegalArgumentException("Length mismatch");
        List<Integer> result=new ArrayList<>();int i=0;for(int cp:text.codePoints().toArray()){if(!readings(new String(Character.toChars(cp))).contains(syllables.get(i)))result.add(i);i++;}return result;
    }
    public List<Choice> grid(List<List<String>> sounds,int index){
        if(index<0||index>=sounds.size())return Collections.emptyList();
        List<Choice> words=new ArrayList<>();
        for(int length=2;length<=3;length++)for(int start=Math.max(0,index-length+1);start<=index&&start+length<=sounds.size();start++){
            List<String> keys=new ArrayList<>();keys.add("");
            for(int i=start;i<start+length;i++){List<String> next=new ArrayList<>();for(String prefix:keys)for(String sound:sounds.get(i))next.add(prefix.isEmpty()?sound:prefix+" "+sound);keys=next;}
            for(String key:keys)words.addAll(choices(key,start));
        }
        words.sort(ORDER);List<Choice> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(Choice word:words)if(seen.add(word.start+":"+word.end+":"+word.text)){result.add(word);if(result.size()==3)break;}
        boolean associations=!result.isEmpty();if(associations)while(result.size()<3)result.add(new Choice("",index,index+1,0));
        List<Choice> single=new ArrayList<>();seen.clear();List<String> syllables=sounds.get(index);
        for(String sound:syllables){
            if(memory!=null)for(DualHandMemory.Entry e:memory.find(Collections.singletonList(sound)))single.add(new Choice(e.text,index,index+1,e.count,true,e.lastUsed,Collections.singletonList(sound)));
            for(String ch:grid.getOrDefault(sound,Collections.emptyList())){
                long frequency=0;Map<String,Long> all=readings.getOrDefault(ch,Collections.emptyMap());for(String s:syllables)frequency+=all.getOrDefault(s,0L);
                single.add(new Choice(ch,index,index+1,frequency,false,0,Collections.singletonList(sound)));
            }
        }
        // Keep the locked single-reading W1 order after learned choices.
        if(syllables.size()>1)single.sort(ORDER);
        else single.sort((a,b)->a.personal&&b.personal?ORDER.compare(a,b):Boolean.compare(b.personal,a.personal));
        for(Choice ch:single)if(seen.add(ch.text)){result.add(ch);if(result.size()==9)break;}
        if(!result.isEmpty())while(result.size()<9)result.add(new Choice("",index,index+1,0));
        return result;
    }
    public int wordLength(String before){for(int n=Math.min(4,before.codePointCount(0,before.length()));n>=2;n--)if(words.contains(tail(before,n)))return n;return before.isEmpty()?0:1;}
    public static String tail(String text,int count){int n=text.codePointCount(0,text.length());return text.substring(text.offsetByCodePoints(0,Math.max(0,n-count)));}
    public static boolean fresh(long requested,long current,long sent,long now){return requested==current&&now>=sent&&now-sent<TIMEOUT_MS;}
}
