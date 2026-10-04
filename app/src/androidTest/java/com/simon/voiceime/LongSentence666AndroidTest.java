package com.simon.voiceime;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.io.*;
import java.util.*;
import org.json.*;

/** Public rebuild seam: full-tone input, exact text preservation, measured emulator deadline. */
public class LongSentence666AndroidTest extends TestCase {
    public void testCompositionWidthReset() {
        android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        inst.runOnMainSync(()->{
            PreviewCursorView view=new PreviewCursorView(inst.getTargetContext(),null);view.setId(R.id.boStreamPreview);view.setLayoutParams(new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,100));
            int width=android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED);
            int height=android.view.View.MeasureSpec.makeMeasureSpec(100,android.view.View.MeasureSpec.EXACTLY);
            view.setText("國".repeat(60));view.measure(width,height);int longWidth=view.getMeasuredWidth();
            view.setText("國".repeat(59));view.measure(width,height);assertEquals(longWidth,view.getMeasuredWidth());
            view.setText("");view.setText("國".repeat(15));view.measure(width,height);assertTrue(view.getMeasuredWidth()<longWidth);
            view.setId(R.id.boPhoneticPreview);view.setText("ㄅ".repeat(60));view.measure(width,height);int readingWidth=view.getMeasuredWidth();
            view.setText("ㄅ");view.measure(width,height);assertTrue(view.getMeasuredWidth()<readingWidth);
        });
    }
    public void testLongEdits() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] base={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};
        JSONArray results=new JSONArray();File out=new File(context.getFilesDir(),"v666");out.mkdirs();
        try(RimeZhuyinEngine engine=new RimeZhuyinEngine(context)) {
            for(int length:new int[]{45,60}) {
                engine.clear();ZhuyinInputController c=new ZhuyinInputController(engine);c.setLearningEnabled(false);
                c.setRetypeEngineFactory(()->new RimeZhuyinEngine(new File(context.getFilesDir(),"rime/shared").toString(),new File(context.getFilesDir(),"rime/user").toString()));
                for(int i=0;i<length;i++)for(char glyph:base[i%10].toCharArray())c.press(glyph==' '?"space":String.valueOf(glyph));
                for(int at:new int[]{length-1,length/2,1}) {
                    assertTrue(c.moveCursorToPreviewBoundary(at).accepted);
                    long start=SystemClock.elapsedRealtimeNanos();ZhuyinInputController.State deleted=c.press("backspace");
                    double ms=(SystemClock.elapsedRealtimeNanos()-start)/1000000.0;
                    results.put(new JSONObject().put("length",length).put("at",at).put("delete_ms",ms).put("accepted",deleted.accepted).put("preview_chars",c.previewText().length()));
                    try(FileWriter f=new FileWriter(new File(out,"edit-mapping.json"))){f.write(results.toString());}
                    assertEquals("direct delete length="+length+" at="+at+" ms="+ms,length-1,c.previewText().length());
                    for(String glyph:new String[]{"ㄊ","ㄧ","ㄢ","space"})c.press(glyph);
                    assertEquals("direct insert",length,c.previewText().length());
                    int selected=c.state().candidates.indexOf("天");assertTrue(selected>=0);c.chooseCandidate(selected);
                    assertEquals("finalized insertion",length,c.previewText().length());
                }
            }
        }
    }
    public void testLongRebuild() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] base={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};
        JSONArray results=new JSONArray();
        File out=new File(context.getFilesDir(),"v666");out.mkdirs();
        try(RimeZhuyinEngine engine=new RimeZhuyinEngine(context)) {
            for(int length:new int[]{45,60}) {
                engine.clear();StringBuilder keys=new StringBuilder();
                for(int i=0;i<length;i++)for(char c:base[i%10].toCharArray()){if(c==' ')engine.space();else engine.key(String.valueOf(c));keys.append(c);}
                String text=engine.previewText();assertEquals(length,text.codePointCount(0,text.length()));
                List<Double> times=new ArrayList<>();
                for(int trial=0;trial<25;trial++) {
                    // Time the production mapping seam separately from native session initialization.
                    // prepareSentence on the outer engine also constructs a fresh session; report both.
                    double publicMs;boolean publicOk;
                    long publicStart=SystemClock.elapsedRealtimeNanos();publicOk=engine.prepareSentence(keys.toString(),text);
                    publicMs=(SystemClock.elapsedRealtimeNanos()-publicStart)/1000000.0;
                    assertTrue("outer rebuild",publicOk);
                    try(SingleRimeZhuyinEngine mapped=new SingleRimeZhuyinEngine(new File(context.getFilesDir(),"rime/shared").toString(),new File(context.getFilesDir(),"rime/user").toString())) {
                    long start=SystemClock.elapsedRealtimeNanos();boolean ok=mapped.prepareSentence(keys.toString(),text);
                    double ms=(SystemClock.elapsedRealtimeNanos()-start)/1000000.0;times.add(ms);
                    JSONObject sample=new JSONObject().put("length",length).put("trial",trial).put("ms",ms).put("accepted",ok).put("outer_ms",publicMs);
                    results.put(sample);try(FileWriter f=new FileWriter(new File(out,"mapping.json"))){f.write(results.toString());}
                    assertTrue("rebuild "+length+" full-tone chars trial="+trial+" ms="+ms,ok);
                    assertEquals("rebuild preserves literal",text,mapped.previewText());
                    }
                }
                // A refused rebuild must preserve both the sentence and its text caret.
                ZhuyinInputController controller=new ZhuyinInputController(engine);
                assertTrue(controller.moveCursorToPreviewBoundary(length/2).accepted);
                assertFalse(engine.prepareSentence(keys.toString(),"龘".repeat(length)));
                assertEquals(text,controller.previewText());
                assertEquals(length/2,controller.previewBoundary());
                Collections.sort(times);System.out.println("MAPPING length="+length+" p50="+times.get(12)+" p95="+times.get(23));
                assertTrue("native mapping p95 exceeds35ms",times.get(23)<=35);
            }
        }
    }
}
