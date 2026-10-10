package com.simon.voiceime;
import java.io.File;
import org.json.JSONObject;
/** Release exam: screen taps only, first-tone syllables use the shipped space key. */
public class Release41Sentences683AndroidTest extends Preview679AndroidTest {
    @Override void preparePreferences(){super.preparePreferences();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("t9_mode",false).commit();}
    void sentence(String expected,String reading)throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        keys(reading);Thread.sleep(400);inst.waitForIdleSync();
        String first=shown();rowTags("sentence-candidates.json");screenshot("sentence-first-choice");readback("sentence-before-commit.json");
        tap("↵");Thread.sleep(300);inst.waitForIdleSync();
        String actual=editorText(await("test_input"));readback("sentence-committed.json");screenshot("sentence-committed");
        save("sentence-result.json",new JSONObject().put("expected",expected).put("reading",reading).put("first_choice",first).put("committed",actual).put("physical_41_key",true).toString());
        assertEquals("first choice",expected,first);assertEquals("editor readback",expected,actual);
    }
    public void testExamOne()throws Exception {sentence("我測驗一下","ㄨㄛˇㄘㄜˋㄧㄢˋㄧ ㄒㄧㄚˋ");}
    public void testExamTwo()throws Exception {sentence("確認違約條款","ㄑㄩㄝˋㄖㄣˋㄨㄟˊㄩㄝ ㄊㄧㄠˊㄎㄨㄢˇ");}
    public void testExamThree()throws Exception {sentence("今天開會","ㄐㄧㄣ ㄊㄧㄢ ㄎㄞ ㄏㄨㄟˋ");}
}
