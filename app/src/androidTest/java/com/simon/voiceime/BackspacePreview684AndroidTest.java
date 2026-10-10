package com.simon.voiceime;

import android.widget.TextView;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replay the reported ordering with screen events; controller access is read-only. */
public class BackspacePreview684AndroidTest extends Preview679AndroidTest {
    @Override void preparePreferences() {
        super.preparePreferences();
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("t9_mode",false).commit();
    }
    void replay(boolean pickCandidate,boolean telemetry) throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        if(telemetry) {
            keys("ㄧˇㄐㄧˊㄈㄨˋㄐㄧㄣˋㄉㄜ˙ㄈㄥㄐㄧㄥˇㄅㄨˋㄉㄠˋㄎㄜˇㄧˇㄘㄦ");
            tap("⌫");keys("ㄢㄈㄤ");tap("⌫");tap("⌫");tap("⌫");
            keys("ㄘㄢ，ㄈㄤˇㄉㄜ˙ㄊㄧˇㄧㄢˋ");
        } else {keys(MainStall683AndroidTest.KEYS);keys(MainStall683AndroidTest.KEYS);}
        Thread.sleep(400);inst.waitForIdleSync();
        assertTrue("at least fifteen uncommitted preview characters",shown().codePointCount(0,shown().length())>=15);
        if(pickCandidate) {
            final TextView[] item={null};
            inst.runOnMainSync(()->{for(int n=0;n<words.getChildCount();n++)if(words.getChildAt(n).isEnabled()){item[0]=(TextView)words.getChildAt(n);break;}});
            assertNotNull("real row2 word candidate",item[0]);clickCandidate(item[0]);
        }
        tapBoundary(13);
        inst.runOnMainSync(()->assertEquals("physical preview tap opens the reported boundary",13,actualController().previewBoundary()));
        rowTags("boundary-before-tags.json");readback("boundary-before-editor.json");screenshot("boundary-before");
        final JSONObject[] beforeState={null};inst.runOnMainSync(()->{try{
            ZhuyinInputController c=actualController();beforeState[0]=new JSONObject().put("raw_preview",c.previewText()).put("shown",row1.getText()).put("keys",c.sentenceKeys()).put("readings",new JSONArray(c.phoneticSyllables()));
        }catch(Exception e){throw new RuntimeException(e);}});save("boundary-controller.json",beforeState[0].toString());
        String expected=shown();JSONArray steps=new JSONArray();int failures=0;
        for(int n=0;n<5;n++) {
            int cursor=13-n;
            expected=expected.substring(0,expected.offsetByCodePoints(0,cursor-1))+expected.substring(expected.offsetByCodePoints(0,cursor));
            String before=shown();tap("⌫");Thread.sleep(250);inst.waitForIdleSync();
            String actual=shown(),editor=editorText(await("test_input"));
            final int[] state=new int[4];inst.runOnMainSync(()->{state[0]=actualController().previewBoundary();state[1]=actualController().phoneticSyllables().size();state[2]=words.getChildCount();state[3]=characters.getChildCount();});
            steps.put(new JSONObject().put("tap",n+1).put("before",before).put("expected",expected).put("actual",actual).put("editor",editor).put("boundary",state[0]).put("reading_count",state[1]).put("word_options",state[2]).put("character_options",state[3]));
            save("backspace-steps.json",steps.toString());readback("delete-"+(n+1)+"-editor.json");screenshot("delete-"+(n+1));
            if(!expected.equals(actual)||!expected.equals(editor)||state[0]!=cursor-1||state[2]+state[3]!=0)failures++;
        }
        assertEquals("every physical delete removes exactly the character before the caret and preserves its neighbours: "+steps,0,failures);
    }
    public void testCandidateThenPreviewFiveDeletes() throws Exception {replay(true,false);}
    public void testPreviewOnlyFiveDeletes() throws Exception {replay(false,false);}
    public void testTelemetryCandidateThenPreviewFiveDeletes() throws Exception {replay(true,true);}
    public void testTelemetryPreviewOnlyFiveDeletes() throws Exception {replay(false,true);}
    public void testChoicesCloseAndExplicitTapReopens() throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        keys(MainStall683AndroidTest.KEYS);keys(MainStall683AndroidTest.KEYS);tapBoundary(13);
        inst.runOnMainSync(()->assertTrue("explicit caret tap opens choices",words.getChildCount()+characters.getChildCount()>0));
        tap("⌫");Thread.sleep(250);inst.waitForIdleSync();
        inst.runOnMainSync(()->assertEquals("delete closes choices while retaining caret",0,words.getChildCount()+characters.getChildCount()));
        rowTags("choices-closed.json");screenshot("choices-closed");tapBoundary(12);
        inst.runOnMainSync(()->assertTrue("next explicit tap reopens choices",words.getChildCount()+characters.getChildCount()>0));
        rowTags("choices-reopened.json");tap("ㄅ");Thread.sleep(250);inst.waitForIdleSync();
        inst.runOnMainSync(()->assertTrue("typing resumes normal choices",words.getChildCount()+characters.getChildCount()>0));
        rowTags("choices-after-typing.json");readback("choices-after-typing-editor.json");
    }
}
