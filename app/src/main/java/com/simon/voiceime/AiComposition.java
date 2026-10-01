package com.simon.voiceime;

/** Keeps the original native session and controller untouched until undo or the next edit. */
final class AiComposition {
    private ZhuyinInputController before,after;
    AiComposition(ZhuyinInputController before){this.before=before;}
    ZhuyinInputController apply(String keys,String text){
        if(before==null||after!=null)return null;
        after=before.preparedSentence(keys,text);return after;
    }
    ZhuyinInputController undo(ZhuyinInputController current){
        if(before==null||after==null||current!=after)return null;
        ZhuyinInputController restored=before;after.close();before=null;after=null;return restored;
    }
    void discard(){if(before!=null&&after!=null)before.close();before=null;after=null;}
}
