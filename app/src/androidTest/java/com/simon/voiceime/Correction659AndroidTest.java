package com.simon.voiceime;
import junit.framework.TestCase;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputConnection;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.text.style.BackgroundColorSpan;
import java.io.File;

/** Production Android adapter and native engine with an isolated, owned test host. */
public class Correction659AndroidTest extends TestCase {
    AiSentencePhone phone;TestHost host;android.app.Instrumentation inst;
    class TestHost implements AiSentencePhone.Host {
        ZhuyinInputController c;String committed="";
        public ZhuyinInputController controller(){return c;}
        public InputConnection ownedConnection(){return null;}
        public boolean allowed(){return true;}
        public void replace(ZhuyinInputController next){c=next;phone.changed(false);}
        public void render(){}
        public void commitSuggestion(){committed=c.press("enter").commitText;}
    }
    void main(Runnable work){inst.runOnMainSync(work);}
    @Override protected void setUp()throws Exception{
        inst=InstrumentationRegistry.getInstrumentation();android.content.Context context=inst.getTargetContext();
        // Asset copying is owned by the production engine; the test user store is isolated.
        try(RimeZhuyinEngine assets=new RimeZhuyinEngine(context)){}
        new File(context.getFilesDir(),"v659-correction-user").mkdirs();
        String shared=new File(context.getFilesDir(),"rime/shared").toString(),user=new File(context.getFilesDir(),"v659-correction-user").toString();
        main(()->{host=new TestHost();host.c=new ZhuyinInputController(new RimeZhuyinEngine(shared,user));host.c.setRetypeEngineFactory(()->new RimeZhuyinEngine(shared,user));
            try{phone=new AiSentencePhone(context,new Handler(Looper.getMainLooper()),host);}catch(Exception e){throw new AssertionError(e);}
            context.getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("auto_correction",false).putString("ai_sentence_mode","off").commit();});
    }
    @Override protected void tearDown(){main(()->{phone.close();host.c.close();});}
    void invalid(){for(String k:new String[]{"ㄒ","ㄧ","ㄦ","ˇ"})host.c.press(k);phone.changed(false);}
    void auto(boolean on){inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("auto_correction",on).commit();}
    public void testOffLocalTapCommitsAndNoFill(){main(()->{invalid();assertNotNull(phone.localOption());String before=host.c.previewText();phone.applyLocal(true);assertEquals(before,host.c.previewText());phone.applyLocal(false);assertEquals("顯",host.committed);assertEquals("",host.c.previewText());});}
    public void testAutoTapAndBackspaceUndoSuppressionAndEdit(){main(()->{auto(true);invalid();String typed=host.c.previewText();phone.applyLocal(true);assertEquals("顯",host.c.previewText());Spanned marked=phone.mark("顯");assertEquals(1,marked.getSpans(0,1,UnderlineSpan.class).length);assertEquals(0,marked.getSpans(0,1,BackgroundColorSpan.class).length);
        assertTrue(phone.tapRevert(0));assertEquals(typed,host.c.previewText());phone.changed(false);phone.applyLocal(true);assertEquals(typed,host.c.previewText());
        host.c.press("backspace");phone.changed(false);host.c.press("ˇ");phone.changed(false);phone.applyLocal(true);assertEquals("顯",host.c.previewText());assertTrue(phone.beforeKey("backspace"));assertEquals(typed,host.c.previewText());});}
    public void testEarlyEnterRevertsAndInvalidates(){main(()->{auto(true);invalid();String typed=host.c.previewText();phone.applyLocal(true);assertEquals("顯",host.c.previewText());assertFalse(phone.beforeKey("enter"));assertEquals(typed,host.c.previewText());host.committed=host.c.press("enter").commitText;assertEquals(typed,host.committed);assertEquals("",host.c.previewText());});}
    public void testDwelledEnterKeepsDisplayed()throws Exception{main(()->{auto(true);invalid();phone.applyLocal(true);});Thread.sleep(850);main(()->{String shown=host.c.previewText();phone.beforeKey("enter");assertEquals(shown,host.c.press("enter").commitText);});}
}
