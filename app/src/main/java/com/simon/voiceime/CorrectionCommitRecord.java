package com.simon.voiceime;
import java.util.*;
/** One unconsumed commit per controller, captured before native parts are discarded. */
final class CorrectionCommitRecord {
    private static final Map<Object,CorrectionCommitRecord> pending=new WeakHashMap<>();
    final String text;final List<String> sounds;
    private CorrectionCommitRecord(String text,List<String> sounds){this.text=text;this.sounds=sounds;}
    static void capture(Object controller,String text,List<String> reading,int span){
        pending.remove(controller);
        if(span<0||span>reading.size()||span!=text.codePointCount(0,text.length()))return;
        List<String> sounds=new ArrayList<>();for(String sound:reading.subList(0,span))sounds.add(CorrectionRecords.untoned(sound));
        pending.put(controller,new CorrectionCommitRecord(text,sounds));
    }
    static List<String> take(Object controller,String text){CorrectionCommitRecord r=pending.remove(controller);return r!=null&&r.text.equals(text)?r.sounds:null;}
    static void clear(){pending.clear();}
}
