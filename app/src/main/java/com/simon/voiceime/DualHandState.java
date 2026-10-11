package com.simon.voiceime;

import java.util.*;

/** Down-time ordering survives reversed finger release. No Android or AI dependencies. */
public final class DualHandState {
    private final DualHandTable table;
    private final List<Stroke> strokes=new ArrayList<>();
    private final List<String> syllables=new ArrayList<>();
    private String pending,display,aiBefore;
    private long revision;
    public long revision(){return revision;}
    public DualHandState(DualHandTable table){this.table=table;}
    public static final class Stroke {
        public final String key;
        public final long down;
        private String direction;
        private boolean ended,cancelled;
        Stroke(String key,long down){this.key=key;this.down=down;}
    }
    public static final class Output {
        public final Stroke stroke;
        public final String state,syllable,direction;
        public final boolean empty;
        Output(Stroke stroke,String state,String syllable,boolean empty){this.stroke=stroke;this.state=state;this.syllable=syllable;this.direction=stroke.direction;this.empty=empty;}
    }
    public Stroke begin(String key,long down){
        if(!DualHandTable.isLeft(key)&&!DualHandTable.isRight(key)&&!Arrays.asList("core","symbols","tools").contains(key))throw new IllegalArgumentException("Unknown key");
        Stroke stroke=new Stroke(key,down);int at=strokes.size();
        if(DualHandTable.isLeft(key))while(at>0){Stroke before=strokes.get(at-1);if(!DualHandTable.isRight(before.key)||down<before.down||down-before.down>DualHandGesture.RACE_MS)break;at--;}
        strokes.add(at,stroke);return stroke;
    }
    public void end(Stroke stroke,String direction,boolean cancelled){
        if(!strokes.contains(stroke)||!DualHandTable.direction(direction))throw new IllegalArgumentException("Unknown stroke or direction");
        stroke.direction=direction;stroke.ended=true;stroke.cancelled=cancelled;
    }
    public List<Output> drain(long now){return drain(now,()->true);}
    public List<Output> drain(long now,java.util.function.BooleanSupplier beforeSyllable){
        List<Output> out=new ArrayList<>();
        while(!strokes.isEmpty()){
            Stroke stroke=strokes.get(0);
            if(!stroke.ended || DualHandTable.isRight(stroke.key)&&now<=stroke.down+DualHandGesture.RACE_MS)break;
            String resolved=pending==null?"Ø":pending;
            if(!stroke.cancelled&&DualHandTable.isRight(stroke.key)&&!table.right(resolved,stroke.key,stroke.direction).isEmpty()&&!beforeSyllable.getAsBoolean())break;
            strokes.remove(0);if(stroke.cancelled)continue;
            if(DualHandTable.isLeft(stroke.key)){
                String next=table.left(stroke.key,stroke.direction);if(!next.isEmpty())pending=next;
                out.add(new Output(stroke,pending,"",next.isEmpty()));
            }else if(DualHandTable.isRight(stroke.key)){
                String state=resolved, syllable=table.right(state,stroke.key,stroke.direction);
                if(!syllable.isEmpty()){if(display!=null)display+=table.draft(syllable);syllables.add(syllable);pending=null;revision++;}
                out.add(new Output(stroke,state,syllable,syllable.isEmpty()));
            }else {out.add(new Output(stroke,pending,"",false));break;}
        }
        return out;
    }
    public String pending(){return pending;}
    public List<String> syllables(){return Collections.unmodifiableList(new ArrayList<>(syllables));}
    public String draft(){return display!=null?display:baseDraft();}
    public String baseDraft(){StringBuilder text=new StringBuilder();for(String s:syllables)text.append(table.draft(s));return text.toString();}
    public enum Delete {PENDING,SYLLABLE,EDITOR}
    public Delete backspace(){if(pending!=null){pending=null;return Delete.PENDING;}if(!syllables.isEmpty()){syllables.remove(syllables.size()-1);if(display!=null)display=trimLast(display);if(aiBefore!=null)aiBefore=trimLast(aiBefore);if(syllables.isEmpty()){display=null;aiBefore=null;}revision++;return Delete.SYLLABLE;}return Delete.EDITOR;}
    public void cancelTouches(){strokes.clear();}
    public void clearBuffer(){pending=null;syllables.clear();display=aiBefore=null;revision++;}
    private static String trimLast(String text){return text.isEmpty()?text:text.substring(0,text.offsetByCodePoints(text.length(),-1));}
    public boolean aiShown(){return aiBefore!=null;}
    public boolean applyAi(String value){if(syllables.isEmpty()||value.codePointCount(0,value.length())!=syllables.size())return false;if(aiBefore==null)aiBefore=draft();display=value;revision++;return true;}
    public boolean restoreAi(){if(aiBefore==null)return false;display=aiBefore;aiBefore=null;revision++;return true;}
    public void replace(int start,int end,String value){
        if(start<0||end>syllables.size()||start>=end||value.codePointCount(0,value.length())!=end-start)throw new IllegalArgumentException("Invalid replacement");
        String old=draft();display=old.substring(0,old.offsetByCodePoints(0,start))+value+old.substring(old.offsetByCodePoints(0,end));revision++;
    }
    public void consume(int count){
        if(count<1||count>syllables.size())throw new IllegalArgumentException("Invalid prefix");
        String remaining=draft().substring(draft().offsetByCodePoints(0,count));syllables.subList(0,count).clear();display=remaining.isEmpty()?null:remaining;aiBefore=null;revision++;
    }
    public void clear(){clearBuffer();strokes.clear();}
}
