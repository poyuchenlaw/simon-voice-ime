package com.simon.voiceime;
import java.util.*;
/** Fixed metadata only. Receiver cannot accept the cross-page correction schema. */
final class CorrectionCounters {
    private final Map<String,Long> counts=new LinkedHashMap<>();private String openPage;
    void event(String page,String action){String key=page+":"+action;counts.put(key,counts.getOrDefault(key,0L)+1);}
    void open(String page){cancel();openPage=page;event(page,"opened");}
    void choose(int rank){if(openPage!=null){event(openPage,"chosen_"+rank);openPage=null;}}
    void cancel(){if(openPage!=null){event(openPage,"cancelled");openPage=null;}}
    long count(String page,String action){return counts.getOrDefault(page+":"+action,0L);}
    Map<String,Long> snapshot(){return Collections.unmodifiableMap(new LinkedHashMap<>(counts));}
}
