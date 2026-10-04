package com.simon.voiceime;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Literal expectations at the public controller commit boundary. */
public class CommitTelemetryTest {
    static class Conversion implements ZhuyinInputController.Engine {
        String text="你好",keys="ㄋㄧˇㄏㄠˇ",commit="";
        public String previewText(){return text;}
        public String composingText(){return text;}
        public String sentenceKeys(){return keys;}
        public List<String> phoneticSyllables(){return keys.isEmpty()?Collections.emptyList():Arrays.asList("ㄋㄧˇ","ㄏㄠˇ");}
        public List<String> candidates(){return text.isEmpty()?Arrays.asList("下一句"):Arrays.asList("你好","你號");}
        public void enter(){commit=text;text=keys="";}
        public void space(){enter();}
        public void choose(int i){text=candidates().get(i);}
        public void chooseAndCommit(int i){choose(i);enter();}
        public void key(String key){text="您好";keys="ㄋㄧㄣˊㄏㄠˇ";}
        public void backspace(){text="你";keys="ㄋㄧˇ";}
        public void moveCursor(String d){}
        public int cursorPosition(){return keys.length();}
        public String takeCommit(){String out=commit;commit="";return out;}
        public void clear(){text=keys=commit="";}
        public boolean commitsAreRendered(){return true;}
    }
    @Test public void plainCommitUsesPreCommitPreviewRatherThanNextCandidates() throws Exception {
        ZhuyinInputController c=new ZhuyinInputController(new Conversion(),null);
        ZhuyinInputController.State state=c.press("enter");
        assertEquals("你好",state.commitText);
        fields(state,"你好","",false,false);
    }
    static void fields(ZhuyinInputController.State s,String top,String ai,boolean taken,boolean corrected){
        assertEquals(top,s.engineTop1);assertEquals(ai,s.aiSuggestion);
        assertEquals(taken,s.aiTaken);assertEquals(corrected,s.corrected);
    }
    @Test public void candidateTwoRetainsFirstConversion(){
        ZhuyinInputController c=new ZhuyinInputController(new Conversion(),null);
        ZhuyinInputController.State s=c.chooseCandidate(1);
        assertEquals("你號",s.commitText);fields(s,"你好","",false,true);
    }
    @Test public void shownSuggestionNotTakenStillRecorded(){
        ZhuyinInputController c=new ZhuyinInputController(new Conversion(),null);
        c.shownAiSuggestion("你號");fields(c.press("enter"),"你好","你號",false,false);
    }
    @Test public void installedAiUsesOriginalConversion(){
        ZhuyinInputController before=new ZhuyinInputController(new Conversion(),null);
        before.shownAiSuggestion("你號");
        Conversion engine=new Conversion();engine.text="你號";
        ZhuyinInputController installed=new ZhuyinInputController(engine,null);
        installed.inheritCommitTelemetry(before);
        fields(installed.press("enter"),"你好","你號",true,true);
    }
    @Test public void caretEditUsesNewConversionAndClearsOldAi(){
        Conversion engine=new Conversion(){public boolean keyCaret(int at){return true;}};
        ZhuyinInputController c=new ZhuyinInputController(engine,null);
        c.shownAiSuggestion("你號");c.moveCursorToKey(2);c.press("ㄣ");
        fields(c.press("enter"),"您好","",false,false);
    }
    @Test public void partialChoiceDescribesConsumedSpanNotWholeOrNextPreview(){
        Conversion e=new Conversion(){
            public void chooseAndCommit(int i){commit=i==0?"你":"妳";text="好";keys="ㄏㄠˇ";}
            public List<String> phoneticSyllables(){return text.equals("你好")?Arrays.asList("ㄋㄧˇ","ㄏㄠˇ"):Arrays.asList("ㄏㄠˇ");}
        };
        ZhuyinInputController c=new ZhuyinInputController(e,null);
        fields(c.chooseCandidate(1),"你","",false,true);
        fields(c.press("enter"),"好","",false,false);
    }
    @Test public void focusedChoiceThenEnterRetainsUnchosenPreview(){
        Conversion e=new Conversion(){public void chooseAndCommit(int i){choose(i);}};
        ZhuyinInputController c=new ZhuyinInputController(e,null);
        assertEquals("",c.chooseCandidate(1).commitText);
        fields(c.press("enter"),"你好","",false,true);
    }
    @Test public void newCompositionAndClearDoNotReuseSuggestion(){
        Conversion e=new Conversion();ZhuyinInputController c=new ZhuyinInputController(e,null);
        c.shownAiSuggestion("你號");fields(c.press("space"),"你好","你號",false,false);
        c.press("ㄣ");fields(c.press("enter"),"您好","",false,false);
        c.shownAiSuggestion("你號");c.clear();c.press("ㄣ");fields(c.press("enter"),"您好","",false,false);
    }
    @Test public void idlePunctuationUsesItsOwnLiteral(){
        Conversion e=new Conversion();e.clear();ZhuyinInputController c=new ZhuyinInputController(e,null);
        fields(c.punctuation("，"),"，","",false,false);
    }
    @Test public void idleAssociationChoiceUsesFirstAssociation(){
        Conversion e=new Conversion();e.clear();ZhuyinInputController c=new ZhuyinInputController(e,null);
        c.showAssociations(Arrays.asList("你好","你號"));
        fields(c.chooseAssociation(1),"你好","",false,true);
    }
    @Test public void selectedWordThenPunctuationKeepsUnselectedEngineText(){
        Conversion e=new Conversion(){
            public void chooseAndCommit(int i){choose(i);}
            public boolean punctuation(String p){text+=p;keys+=p;return true;}
        };
        ZhuyinInputController c=new ZhuyinInputController(e,null);
        c.chooseCandidate(1);c.punctuation("，");
        fields(c.press("enter"),"你好，","",false,true);
    }
}
