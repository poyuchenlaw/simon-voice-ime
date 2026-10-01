package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.function.Supplier;

public class ZhuyinSecondPassTest {
    static class Engine implements ZhuyinInputController.Engine {
        String text="現在會辨識",commit="";int[] range={2,3};boolean replacement;
        Engine(boolean r){replacement=r;if(r)text="";}
        public void key(String k){text+=k;}
        public void backspace(){text="";}
        public void space(){text="回";}
        public void enter(){commit=text;text="";}
        public void choose(int i){text=candidates().get(i);}
        public void moveCursor(String d){}
        public boolean moveCursorToPreviewCharacter(int i){return true;}
        public int[] previewSelectionRange(){return range;}
        public int cursorPosition(){return text.codePointCount(0,text.length());}
        public String composingText(){return text;}
        public List<String> candidates(){return replacement&&!text.isEmpty()?Arrays.asList("回","會"):Collections.emptyList();}
        public String takeCommit(){String v=commit;commit="";return v;}
        public void clear(){text="";}
    }
    @Test public void deletingAndRetypingMiddleKeepsBothSurroundingSegments() throws Exception {
        Engine original=new Engine(false);
        ZhuyinInputController c=new ZhuyinInputController(original);
        c.setRetypeEngineFactory(()->new Engine(true));
        c.moveCursorToPreviewCharacter(2);
        assertEquals("deletion must remove only the selected word", "現在辨識",c.press("backspace").composingText);
        c.press("ㄏ");assertEquals("現在回辨識",c.press("space").composingText);
        assertEquals("現在回辨識",c.chooseCandidate(0).composingText);
        assertEquals("現在回辨識",c.press("enter").commitText);
    }
    @Test public void cancelRestoresOriginalSentenceWithoutCommittingDraft() {
        Engine original=new Engine(false);ZhuyinInputController c=new ZhuyinInputController(original);
        c.setRetypeEngineFactory(()->new Engine(true));c.moveCursorToPreviewCharacter(2);c.press("backspace");c.press("ㄏ");c.press("space");
        ZhuyinInputController.State cancel=c.cancelSecondPass();
        assertEquals("現在會辨識",cancel.composingText);assertEquals("",cancel.commitText);
        assertEquals("現在會辨識",original.text);assertFalse(c.isSecondPassActive());
    }
    @Test public void multiCharacterWordWithAstralPrefixReplacesOnlySelectedWordAndCanBeRetypedAgain() {
        Engine original=new Engine(false);original.text="😀錯誤尾";original.range=new int[]{1,3};
        ZhuyinInputController c=new ZhuyinInputController(original);c.setRetypeEngineFactory(()->new Engine(true));
        c.moveCursorToPreviewCharacter(1);assertEquals("😀尾",c.press("backspace").composingText);
        c.press("ㄏ");c.press("space");assertEquals("😀回尾",c.chooseCandidate(0).composingText);
        c.moveCursorToPreviewCharacter(1);c.press("backspace");c.press("ㄏ");c.press("space");
        assertEquals("😀會尾",c.chooseCandidate(1).composingText);assertEquals("😀會尾",c.press("enter").commitText);
    }
    @Test public void punctuationCommitsFixedSentenceInsteadOfOverwritingIt() {
        Engine original=new Engine(false);ZhuyinInputController c=new ZhuyinInputController(original);
        c.setRetypeEngineFactory(()->new Engine(true));c.moveCursorToPreviewCharacter(2);c.press("backspace");c.press("ㄏ");c.press("space");c.chooseCandidate(0);
        assertEquals("現在回辨識",c.flushForPunctuation().commitText);assertEquals("",c.state().composingText);
    }
    @Test public void enterConfirmsSecondPassRatherThanTrappingUser() {
        Engine e=new Engine(false);ZhuyinInputController c=new ZhuyinInputController(e);
        c.setRetypeEngineFactory(()->new Engine(true));c.moveCursorToPreviewCharacter(2);c.press("backspace");c.press("ㄏ");c.press("space");
        assertEquals("現在回辨識",c.press("enter").composingText);assertFalse("Enter exits second pass",c.isSecondPassActive());
        assertEquals("現在回辨識",c.press("enter").commitText);
    }
    @Test public void multiCharacterReplacementRetainsWordBoundaryOnNextEdit() {
        Engine e=new Engine(false);ZhuyinInputController c=new ZhuyinInputController(e);
        c.setRetypeEngineFactory(()->new Engine(true){public List<String> candidates(){return text.isEmpty()?Collections.emptyList():Arrays.asList("回覆","答覆");}});
        c.moveCursorToPreviewCharacter(2);c.press("backspace");c.press("ㄏ");c.press("space");c.chooseCandidate(0);
        assertEquals("現在回覆辨識",c.state().composingText);
        ZhuyinInputController.State focus=c.moveCursorToPreviewCharacter(3);
        assertEquals(2,focus.targetStart);assertEquals(4,focus.targetEnd);
        assertEquals("現在辨識",c.press("backspace").composingText);
        assertEquals("現在回覆辨識",c.cancelSecondPass().composingText);
    }
    @Test public void repeatedBackspaceCanDeleteAnUntouchedMultiCharacterWordAndCancelRestoresIt() {
        Engine e=new Engine(false);e.text="現在會辨識完";ZhuyinInputController c=new ZhuyinInputController(e);
        c.setRetypeEngineFactory(()->new Engine(true){public List<String> candidates(){return text.isEmpty()?Collections.emptyList():Arrays.asList("回覆");}});
        c.moveCursorToPreviewCharacter(2);c.press("backspace");c.press("ㄏ");c.press("space");c.chooseCandidate(0);
        c.moveCursorToPreviewCharacter(5);assertEquals("現在回覆辨完",c.press("backspace").composingText);
        assertEquals("現在回覆完",c.press("backspace").composingText);
        assertEquals("現在回覆辨識完",c.cancelSecondPass().composingText);
        c.moveCursorToPreviewCharacter(5);c.press("backspace");c.press("backspace");c.press("ㄏ");c.press("space");
        assertEquals("現在回覆回覆完",c.chooseCandidate(0).composingText);
    }
    @Test public void unavailableNativeCaretStillAllowsSafeVisibleSegmentRetype() {
        Engine e=new Engine(false){public boolean moveCursorToPreviewCharacter(int i){return false;}};
        ZhuyinInputController c=new ZhuyinInputController(e);c.setRetypeEngineFactory(()->new Engine(true));
        c.moveCursorToPreviewCharacter(2);assertEquals("現在辨識",c.press("backspace").composingText);
        assertEquals("現在會辨識",c.cancelSecondPass().composingText);
    }
    @Test public void subsequentInputAndPunctuationKeepConfirmedMiddleReplacement() {
        Engine original=new Engine(false);ZhuyinInputController c=new ZhuyinInputController(original);
        c.setRetypeEngineFactory(()->new Engine(true));c.moveCursorToPreviewCharacter(2);c.press("backspace");c.press("ㄏ");c.press("space");c.chooseCandidate(0);
        assertEquals("現在回辨識ㄅ",c.press("ㄅ").composingText);
        assertEquals("現在回辨識ㄅ",c.press("space").commitText);
    }
}
