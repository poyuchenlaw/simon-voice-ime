package com.simon.voiceime;

import java.util.*;

/** Down-time ordering survives reversed finger release. No Android or AI dependencies. */
public final class DualHandState {
    private final DualHandTable table;
    private final List<Stroke> strokes=new ArrayList<>();
    private final List<String> syllables=new ArrayList<>();
    private String pending;
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
    public List<Output> drain(long now){
        List<Output> out=new ArrayList<>();
        while(!strokes.isEmpty()){
            Stroke stroke=strokes.get(0);
            if(!stroke.ended || DualHandTable.isRight(stroke.key)&&now<=stroke.down+DualHandGesture.RACE_MS)break;
            strokes.remove(0);if(stroke.cancelled)continue;
            if(DualHandTable.isLeft(stroke.key)){
                String next=table.left(stroke.key,stroke.direction);if(!next.isEmpty())pending=next;
                out.add(new Output(stroke,pending,"",next.isEmpty()));
            }else if(DualHandTable.isRight(stroke.key)){
                String state=pending==null?"Ø":pending, syllable=table.right(state,stroke.key,stroke.direction);
                if(!syllable.isEmpty()){syllables.add(syllable);pending=null;}
                out.add(new Output(stroke,state,syllable,syllable.isEmpty()));
            }else {out.add(new Output(stroke,pending,"",false));break;}
        }
        return out;
    }
    public String pending(){return pending;}
    public List<String> syllables(){return Collections.unmodifiableList(new ArrayList<>(syllables));}
    public String draft(){StringBuilder text=new StringBuilder();for(String s:syllables)text.append(table.draft(s));return text.toString();}
    public enum Delete {PENDING,SYLLABLE,EDITOR}
    public Delete backspace(){if(pending!=null){pending=null;return Delete.PENDING;}if(!syllables.isEmpty()){syllables.remove(syllables.size()-1);return Delete.SYLLABLE;}return Delete.EDITOR;}
    public void cancelTouches(){strokes.clear();}
    public void clearBuffer(){pending=null;syllables.clear();}
    public void clear(){clearBuffer();strokes.clear();}
}
