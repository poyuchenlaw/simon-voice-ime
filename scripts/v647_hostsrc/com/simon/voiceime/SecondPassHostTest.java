package com.simon.voiceime;

/** Real librime sessions, real controller, no mock composition or candidates. */
public class SecondPassHostTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        CompositionKeysHostTest.Engine engine=new CompositionKeysHostTest.Engine(args[0],args[1]);
        ZhuyinInputController c=new ZhuyinInputController(engine);
        c.setRetypeEngineFactory(()->new CompositionKeysHostTest.Engine(args[0],args[1]));
        // Literal standard-keyboard strokes for 你好嗎, separate from any mapping helper oracle.
        for(String key:new String[]{"ㄋ","ㄧ","ˇ","ㄏ","ㄠ","ˇ","ㄇ","ㄚ","˙"})c.press(key);
        String original=c.state().composingText;check(original.codePointCount(0,original.length())>=3,"need complete preedit: "+original);
        ZhuyinInputController.State focused=c.moveCursorToPreviewCharacter(5);
        int a=focused.targetStart,b=focused.targetEnd;check(a>0&&b<original.codePointCount(0,original.length()),"must target a middle syllable, not the whole sentence: "+a+":"+b);check(b>a,"K2 native caret must identify a segment");
        String before=original.substring(0,original.offsetByCodePoints(0,a)),after=original.substring(original.offsetByCodePoints(0,b));
        c.press("backspace");check(c.isSecondPassActive(),"native segment must start second pass");
        check(c.state().composingText.equals(before+after),"delete only targeted segment: original="+original+" focused="+focused.composingText+" range="+a+":"+b+" expected="+(before+after)+" actual="+c.state().composingText);
        for(String key:new String[]{"ㄏ","ㄨ","ㄟ","ˊ"})c.press(key);
        check(!c.state().candidates.isEmpty(),"real retyped candidates");
        String replacement=c.state().candidates.get(0);
        check(c.cancelSecondPass().composingText.equals(original),"cancel preserves real original engine/preedit");
        c.moveCursorToPreviewCharacter(5);c.press("backspace");
        for(String key:new String[]{"ㄏ","ㄨ","ㄟ","ˊ"})c.press(key);
        replacement=c.state().candidates.get(0);String expected=before+replacement+after;
        check(c.chooseCandidate(0).composingText.equals(expected),"native second pass changed surrounding text");
        check(c.press("enter").commitText.equals(expected.replace(" ","ˉ")),"whole committed result must match accepted segment edit");
        c.close();System.out.println("PASS real librime caret + middle retype + cancel + candidate + commit: original="+original+" replacement="+replacement+" result="+expected);
    }
}
