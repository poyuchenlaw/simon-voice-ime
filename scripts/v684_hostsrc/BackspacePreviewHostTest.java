package com.simon.voiceime;
import java.util.*;

public class BackspacePreviewHostTest {
    static void keys(ZhuyinInputController c,String s){for(char k:s.toCharArray())if(k=='，')c.punctuation("，");else c.press(k==' '?"space":String.valueOf(k));}
    public static void main(String[] a)throws Exception {
        try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])) {
            ZhuyinInputController c=new ZhuyinInputController(e);c.setTextLayout(true);c.setLearningEnabled(false);
            c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
            keys(c,"ㄧˇㄐㄧˊㄈㄨˋㄐㄧㄣˋㄉㄜ˙ㄈㄥㄐㄧㄥˇㄅㄨˋㄉㄠˋㄎㄜˇㄧˇㄘㄦ");
            c.press("backspace");keys(c,"ㄢㄈㄤ");c.press("backspace");c.press("backspace");c.press("backspace");
            keys(c,"ㄘㄢ，ㄈㄤˇㄉㄜ˙ㄊㄧˇㄧㄢˋ");
            for(ZhuyinInputController.TextChoice choice:c.textChoices())if("體驗".equals(choice.label)){c.chooseTextCandidate(choice);break;}
            String text=c.previewText();List<String> readings=new ArrayList<>(c.phoneticSyllables());
            java.lang.reflect.Field field=ZhuyinInputController.class.getDeclaredField("engine");field.setAccessible(true);
            RimeZhuyinEngine live=(RimeZhuyinEngine)field.get(c);
            if(text.codePointCount(0,text.length())!=17)throw new AssertionError("telemetry-shaped fixture has seventeen glyphs: "+text);
            System.out.println("FIXTURE "+text+" readings="+readings);
            // Already mapped original text/readings, with only the comma removed.
            String expected=text.substring(0,12)+text.substring(13);readings.remove(12);
            boolean accepted=live.prepareSentence(String.join("",readings).replace('ˉ',' '),expected);
            System.out.println("DIRECT_MAPPED_DELETE accepted="+accepted+" actual="+live.previewText()+" expected="+expected);
            if(!accepted||!expected.equals(live.previewText()))throw new AssertionError("known unchanged mapping must survive removal of a literal boundary");
            c.moveCursorToPreviewBoundary(12);
            for(int n=0;n<4;n++) {
                int at=12-n;expected=expected.substring(0,at-1)+expected.substring(at);
                if(!c.press("backspace").accepted||!expected.equals(c.previewText()))throw new AssertionError("subsequent delete "+n+": "+c.previewText());
                if(!c.textChoices().isEmpty())throw new AssertionError("delete must dismiss the caret choices");
            }
            System.out.println("PASS mapped punctuation and four subsequent caret deletes");
            c.close();
        }
        String original="今天，開會，我測",reading="ㄐㄧㄣ ㄊㄧㄢ ，ㄎㄞ ㄏㄨㄟˋ，ㄨㄛˇㄘㄜˋ";
        for(int boundary=0;boundary<=original.length();boundary++) {
            try(RimeZhuyinEngine e=new RimeZhuyinEngine(a[0],a[1])) {
                ZhuyinInputController c=new ZhuyinInputController(e);c.setTextLayout(true);
                if(!e.prepareSentence(reading,original))throw new AssertionError("independent known punctuation fixture");
                c.moveCursorToPreviewBoundary(boundary);
                String expected=boundary==0?original:original.substring(0,boundary-1)+original.substring(boundary);
                if(!c.press("backspace").accepted||!expected.equals(c.previewText()))throw new AssertionError("punctuation retained at boundary="+boundary+" actual="+c.previewText());
                if(c.phoneticSyllables().size()!=expected.length())throw new AssertionError("one reading per retained character");
            }
        }
        System.out.println("PASS all nine boundaries: start no-op, retained/removed commas, first/last character");
    }
}
