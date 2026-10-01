package com.simon.voiceime;

import java.util.*;

/** Observation-only adapter. Labels are accepted physical keys, never posterior winners.
 * An explicit candidate choice or a surviving commit confirms a trace after ten seconds.
 * Edits, field changes, reset and overflow discard uncertain traces rather than train them.
 */
final class TouchModelShadow {
    private static final class Sample {
        final String screen, key; final double x,y;
        Sample(String s,String k,double x,double y){screen=s;key=k;this.x=x;this.y=y;}
    }
    private static final class Confirmation {
        final long due; final List<Sample> samples;
        Confirmation(long d,List<Sample> s){due=d;samples=s;}
    }
    private final TouchModel model;
    private final List<Sample> trace = new ArrayList<>();
    private final Map<Long,Confirmation> waiting = new LinkedHashMap<>();
    private long sequence;
    private List<TouchModel.Alternative> lastPosterior=Collections.emptyList();
    List<TouchModel.Alternative> posterior(){return lastPosterior;}
    TouchModelShadow(TouchModel model){this.model=model;}
    List<TouchModel.Alternative> press(String screen,String physicalKey,double x,double y) {
        // Validate the label independently of the prediction.
        model.parameters(screen,physicalKey);
        List<TouchModel.Alternative> posterior=model.predict(screen,x,y);lastPosterior=posterior;
        if(trace.size()>=512) invalidate();
        trace.add(new Sample(screen,physicalKey,x,y));
        List<TouchModel.Alternative> alternatives=new ArrayList<>();
        for(TouchModel.Alternative a:posterior) if(!a.key.equals(physicalKey)&&alternatives.size()<2) alternatives.add(a);
        return Collections.unmodifiableList(alternatives);
    }
    int confirmChoice() {
        List<Sample> accepted=new ArrayList<>(trace);trace.clear();
        for(Sample s:accepted)model.updateConfirmed(s.screen,s.key,s.x,s.y);
        return accepted.size();
    }
    void discardTrace(){trace.clear();}
    long awaitConfirmation(long now) {
        if(trace.isEmpty())return -1;
        if(waiting.size()>=32)waiting.clear();
        long id=++sequence;
        waiting.put(id,new Confirmation(now+10_000,new ArrayList<>(trace)));trace.clear();
        return id;
    }
    int confirm(long id,long now,boolean unchanged) {
        Confirmation c=waiting.get(id);
        if(c==null)return 0;
        if(!unchanged){waiting.remove(id);return 0;}
        if(now<c.due)return 0;
        waiting.remove(id);
        for(Sample s:c.samples)model.updateConfirmed(s.screen,s.key,s.x,s.y);
        return c.samples.size();
    }
    void invalidate(){trace.clear();waiting.clear();}
    void reset(){invalidate();model.reset();}
    byte[] snapshot(){return model.snapshot();}
}
