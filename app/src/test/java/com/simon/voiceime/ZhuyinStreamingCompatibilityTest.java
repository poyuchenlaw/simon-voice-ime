package com.simon.voiceime;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ZhuyinStreamingCompatibilityTest {
    static class Fake implements ZhuyinInputController.Engine {
        String composing=""; int chosen=-1; String learned="";
        public void key(String k){composing+=k;} public void backspace(){if(!composing.isEmpty())composing=composing.substring(0,composing.length()-1);}
        public void space(){} public void enter(){} public void choose(int i){chosen=i;} public void moveCursor(String d){}
        public int cursorPosition(){return composing.codePointCount(0,composing.length());} public String composingText(){return composing;}
        public List<String> candidates(){return Arrays.asList("本音候選");} public String takeCommit(){return "";} public void clear(){composing="";}
        public void learnPhrase(String word,String pronunciation){learned=word;}
        public List<String[]> personalPhrases(){return Collections.singletonList(new String[]{"陳柏諭","ㄔㄣˊ ㄅㄛˊ ㄩˊ"});}
    }
    static final class ConvertedFake extends Fake {
        public void key(String k){super.key(k);if(composing.endsWith("ㄨ"))composing="知";}
    }
    @Test public void noTonePersonalPhraseRemainsFirstCandidate() {
        Fake engine=new Fake(); ZhuyinWordIndex index=ZhuyinWordIndex.forTesting();
        ZhuyinInputController c=new ZhuyinInputController(engine,index);
        c.press("ㄔ");c.press("ㄅ");ZhuyinInputController.State state=c.press("ㄩ");
        assertEquals("陳柏諭",state.candidates.get(0));
        assertEquals("陳柏諭",c.chooseCandidate(0).commitText);
    }
    @Test public void abbreviatedAndNativeCandidatesRemainVisibleTogether() {
        Fake engine=new ConvertedFake();
        ZhuyinWordIndex index=ZhuyinWordIndex.forTesting(new ZhuyinWordIndex.Entry("ㄓㄨ","諸拼候選","ㄓㄨ",10,false));
        ZhuyinInputController c=new ZhuyinInputController(engine,index);
        c.press("ㄓ");ZhuyinInputController.State state=c.press("ㄨ");
        assertTrue(state.candidates.contains("諸拼候選"));
        assertTrue(state.candidates.contains("本音候選"));
        assertEquals("mixed",state.candidateKind);
    }
}
