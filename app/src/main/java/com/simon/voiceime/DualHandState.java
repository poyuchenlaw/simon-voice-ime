package com.simon.voiceime;

import java.util.*;

/** Down-time ordering survives reversed finger release. No Android or AI dependencies. */
public final class DualHandState {
    private final DualHandTable table;
    private final List<Stroke> strokes=new ArrayList<>();
    private final List<String> syllables=new ArrayList<>();
    private String pending,display,aiBefore;
    private final List<String> initials=new ArrayList<>(),locks=new ArrayList<>();
    private int cursor,selected=-1;
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
        if(!DualHandTable.isLeft(key)&&!DualHandTable.isRight(key)&&!DualHandTable.BOTTOM_KEYS.contains(key))throw new IllegalArgumentException("Unknown key");
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
                String next=table.left(stroke.key,stroke.direction);if(!next.isEmpty()){pending=next;revision++;}
                out.add(new Output(stroke,pending,"",next.isEmpty()));
            }else if(DualHandTable.isRight(stroke.key)){
                String state=resolved, syllable=table.right(state,stroke.key,stroke.direction);
                if(!syllable.isEmpty()){if(display!=null){int at=display.offsetByCodePoints(0,cursor);display=display.substring(0,at)+table.draft(syllable)+display.substring(at);}syllables.add(cursor,syllable);initials.add(cursor,"Ø".equals(state)?null:state);locks.add(cursor,null);cursor++;selected=-1;pending=null;revision++;}
                out.add(new Output(stroke,state,syllable,syllable.isEmpty()));
            }else {out.add(new Output(stroke,pending,"",false));break;}
        }
        return out;
    }
    public String pending(){return pending;}
    public List<String> syllables(){return Collections.unmodifiableList(new ArrayList<>(syllables));}
    public String draft(){return display!=null?display:baseDraft();}
    public String baseDraft(){StringBuilder text=new StringBuilder();for(int i=0;i<syllables.size();i++)text.append(locks.get(i)==null?table.draft(syllables.get(i)):locks.get(i));return text.toString();}
    public String aiDraft(){StringBuilder text=new StringBuilder();for(String s:syllables)text.append(table.aiDraft(s));return text.toString();}
    public int cursor(){return cursor;}
    public int selected(){return selected;}
    public String locked(int index){return locks.get(index);}
    public boolean hasLocks(){for(String ch:locks)if(ch!=null)return true;return false;}
    public void discardPending(){if(pending!=null){pending=null;revision++;}}
    public void select(int index){if(index<0||index>=syllables.size())throw new IllegalArgumentException("Invalid syllable");discardPending();cursor=index+1;selected=index;revision++;}
    public void setCursor(int at){if(at<0||at>syllables.size())throw new IllegalArgumentException("Invalid cursor");discardPending();cursor=at;selected=-1;revision++;}
    public String preview(){List<String> parts=new ArrayList<>(syllables);if(pending!=null)parts.add(cursor,pending);return String.join(" ",parts);}
    public void lock(int index,String value){
        if(index<0||index>=syllables.size()||value==null||value.codePointCount(0,value.length())!=1)throw new IllegalArgumentException("Invalid character lock");
        discardPending();if(aiShown())display=aiBefore=null;else if(display!=null){int start=display.offsetByCodePoints(0,index),end=display.offsetByCodePoints(0,index+1);display=display.substring(0,start)+value+display.substring(end);}locks.set(index,value);selected=index+1<syllables.size()?index+1:-1;cursor=selected<0?syllables.size():selected+1;revision++;
    }
    public enum Delete {PENDING,SYLLABLE,EDITOR,BLOCKED,RESTORED}
    public Delete backspace(){return delete(true);}
    public Delete eraseCharacter(){if(restoreAi())return Delete.RESTORED;return delete(false);}
    private Delete delete(boolean restoreInitial){
        if(pending!=null){discardPending();return Delete.PENDING;}
        if(cursor==0)return syllables.isEmpty()?Delete.EDITOR:Delete.BLOCKED;
        int at=--cursor;String initial=initials.remove(at);syllables.remove(at);locks.remove(at);selected=-1;
        if(display!=null)display=removePoint(display,at);if(aiBefore!=null)aiBefore=removePoint(aiBefore,at);
        if(syllables.isEmpty())display=aiBefore=null;
        pending=restoreInitial?initial:null;revision++;return Delete.SYLLABLE;
    }
    private static String removePoint(String text,int at){return text.substring(0,text.offsetByCodePoints(0,at))+text.substring(text.offsetByCodePoints(0,at+1));}
    public void cancelTouches(){strokes.clear();}
    public void clearBuffer(){pending=null;syllables.clear();initials.clear();locks.clear();cursor=0;selected=-1;display=aiBefore=null;revision++;}
    public boolean aiShown(){return aiBefore!=null;}
    public boolean applyAi(String value){if(hasLocks()||syllables.isEmpty()||value.codePointCount(0,value.length())!=syllables.size())return false;if(aiBefore==null)aiBefore=draft();display=value;revision++;return true;}
    public boolean restoreAi(){if(aiBefore==null)return false;display=aiBefore;aiBefore=null;revision++;return true;}
    public void replace(int start,int end,String value){
        if(start<0||end>syllables.size()||start>=end||value.codePointCount(0,value.length())!=end-start)throw new IllegalArgumentException("Invalid replacement");
        String old=draft();display=old.substring(0,old.offsetByCodePoints(0,start))+value+old.substring(old.offsetByCodePoints(0,end));for(int i=start;i<end;i++)if(locks.get(i)!=null)locks.set(i,value.substring(value.offsetByCodePoints(0,i-start),value.offsetByCodePoints(0,i-start+1)));revision++;
    }
    public void consume(int count){
        if(count<1||count>syllables.size())throw new IllegalArgumentException("Invalid prefix");
        String remaining=draft().substring(draft().offsetByCodePoints(0,count));syllables.subList(0,count).clear();initials.subList(0,count).clear();locks.subList(0,count).clear();pending=null;cursor=syllables.size();selected=-1;display=remaining.isEmpty()?null:remaining;aiBefore=null;revision++;
    }
    public void clear(){clearBuffer();strokes.clear();}
}
