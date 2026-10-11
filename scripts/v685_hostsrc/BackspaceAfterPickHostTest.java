package com.simon.voiceime;

/** Actual Rime sessions: text-mode deletion removes glyphs after a candidate and punctuation. */
public class BackspaceAfterPickHostTest {
    public static void main(String[] args) throws Exception {
        try(RimeZhuyinEngine engine=new RimeZhuyinEngine(args[0],args[1])) {
            ZhuyinInputController c=new ZhuyinInputController(engine);
            c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(args[0],args[1]));
            BackspaceStuckHostTest.keys(c,"ㄐㄧㄣ ㄊㄧㄢ ");
            ZhuyinInputController.TextChoice pick=null;
            for(var choice:c.textChoices())if("word".equals(choice.kind)&&"今天".equals(choice.label)){pick=choice;break;}
            if(pick==null||!c.chooseTextCandidate(pick).accepted)throw new AssertionError("real 今天 word selection required");
            BackspaceStuckHostTest.keys(c,"，ㄍㄜ");
            for(String expected:new String[]{"今天，","今天","今",""}) {
                String before=c.textPreview();c.press("backspace");
                System.out.println("before="+before+" after="+c.textPreview()+" expected="+expected+" keyCaret="+c.keyCaret());
                if(!expected.equals(c.textPreview()))throw new AssertionError("delete must remove the last displayed glyph, preserving its prefix");
            }
            System.out.println("PASS real candidate, punctuation and four exact glyph deletions");
        }
    }
}
