package com.simon.voiceime;

import java.util.*;

/** Per two-key spelling segments let the UI replace one Rime candidate without disturbing neighbors. */
final class T9CompositionModel {
    static final class Segment {
        final String keys; final List<String> candidates; String selected;
        Segment(String keys,List<String> candidates,String selected){this.keys=keys;this.candidates=Collections.unmodifiableList(new ArrayList<>(candidates));this.selected=selected;}
    }
    private final List<Segment> segments=new ArrayList<>();
    void update(int index,String keys,List<String> candidates){
        String old= index<segments.size()&&segments.get(index).keys.equals(keys)?segments.get(index).selected:null;
        String chosen=old!=null&&candidates.contains(old)?old:(candidates.isEmpty()?"":candidates.get(0));
        Segment next=new Segment(keys,candidates,chosen);if(index<segments.size())segments.set(index,next);else if(index==segments.size())segments.add(next);else throw new IndexOutOfBoundsException();
    }
    void trim(int count){while(segments.size()>count)segments.remove(segments.size()-1);}
    void removeLast(){if(!segments.isEmpty())segments.remove(segments.size()-1);}
    boolean choose(int index,int candidate){if(index<0||index>=segments.size())return false;Segment s=segments.get(index);if(candidate<0||candidate>=s.candidates.size())return false;s.selected=s.candidates.get(candidate);return true;}
    List<String> candidates(int index){return index<0||index>=segments.size()?Collections.emptyList():segments.get(index).candidates;}
    Segment segment(int index){return segments.get(index);}
    int size(){return segments.size();}
    String preview(){StringBuilder b=new StringBuilder();for(Segment s:segments)b.append(s.selected);return b.toString();}
}
