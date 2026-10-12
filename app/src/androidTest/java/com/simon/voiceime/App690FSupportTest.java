package com.simon.voiceime;

/** Device regression for command parsing and the independent host's exact readback. */
public class App690FSupportTest extends App690FSupport {
    public void testShellAndHostReadback()throws Exception {
        assertEquals("one twothree",shell("printf '%s' 'one two'; printf '%s' three"));
        String expected="測試 '字串' 與 空白";
        text(expected);
        assertEquals(expected,hostCall("state",new android.os.Bundle()).getString("text"));
        main(()->{android.widget.TextView label=new android.widget.TextView(inst.getTargetContext());for(float sp:new float[]{12,18,19}){label.setTextSize(sp);assertEquals(sp,sizeSp(label),.0001f);}return null;});
    }
}
