package com.simon.voiceime;
import java.io.File;
/** Physical replay of a shipped default phrase, no controller edits. */
public class CommonPhrase683AndroidTest extends Preview679AndroidTest {
    @Override protected void runTest()throws Throwable {
        try{super.runTest();}finally{if(out!=null&&row1!=null){
            rowTags("cold-final-rows.json");
            inst.runOnMainSync(()->{try{
                save("cold-controller-state.json",new org.json.JSONObject()
                    .put("idle_shortcuts",actualController().showIdleShortcuts())
                    .put("preview",actualController().previewText())
                    .put("keys",actualController().sentenceKeys()).toString());
            }catch(Exception error){throw new RuntimeException(error);}});
        }}
    }
    public void testDefaultCommonPhrasePhysicalCommit()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        assertEquals("empty fixture", "",editorText(await("test_input")));
        tap("⚡ 常用詞");tap("書狀格式");tap("陳報狀開頭");inst.waitForIdleSync();
        readback("common-phrase-editor.json");screenshot("common-phrase");
        assertEquals("shipped phrase reaches editor exactly","為陳報事項，茲依法陳報如下：",editorText(await("test_input")));
    }
    public void testEditorClearKeepsCommonPhrase()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        text("甲");text("");tap("test_input");inst.waitForIdleSync();await("ㄗ");bind();awaitStableWindow();
        assertEquals("cleared public editor", "",editorText(await("test_input")));
        tap("⚡ 常用詞");tap("書狀格式");tap("陳報狀開頭");inst.waitForIdleSync();
        readback("common-phrase-after-clear.json");
        assertEquals("shipped phrase survives editor clear","為陳報事項，茲依法陳報如下：",editorText(await("test_input")));
    }
    public void testCommonPhraseCanBeUsedAgainAfterCommit()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        String phrase="為陳報事項，茲依法陳報如下：";
        for(int n=1;n<=2;n++){
            Thread.sleep(500);inst.waitForIdleSync();rowTags("repeat-"+n+"-before-rows.json");
            readback("repeat-"+n+"-before-editor.json");screenshot("repeat-"+n+"-before");
            tap("⚡ 常用詞");tap("書狀格式");tap("陳報狀開頭");inst.waitForIdleSync();
            assertEquals("each physical phrase tap appends exactly once",phrase.repeat(n),editorText(await("test_input")));
            readback("repeat-"+n+"-after-editor.json");
        }
    }
}
