package com.simon.voiceime;

import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Physical 41-key input and all three visible selection rows; no controller writes. */
public class BackspaceAfterPick685AndroidTest extends BackspaceStuck685AndroidTest {
    void replayPick(int row, boolean literal) throws Exception {
        replayPick(row,literal,false);
    }
    void replayPick(int row, boolean literal, boolean held) throws Exception {
        ready();out=new java.io.File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();awaitStableWindow();
        keys("ㄐㄧㄣ ㄊㄧㄢ ");
        assertEquals("known full-code input", "今天",editorText(await("test_input")));
        if(literal&&!held)keys("，ㄍㄜ");
        if(row==1)tapBoundary(2);
        else {
            if(literal&&!held)tapBoundary(2);
            final TextView[] chosen={null};LinearLayout choices=row==2?words:characters;
            long deadline=android.os.SystemClock.uptimeMillis()+5000;
            while(chosen[0]==null&&android.os.SystemClock.uptimeMillis()<deadline){
                inst.runOnMainSync(()->{for(int i=0;i<choices.getChildCount();i++){
                    android.view.View view=choices.getChildAt(i);
                    if(view.isEnabled()&&view.getTag() instanceof ZhuyinInputController.TextChoice){chosen[0]=(TextView)view;break;}
                }});if(chosen[0]==null)Thread.sleep(50);
            }
            assertNotNull("physical candidate required in row "+row,chosen[0]);clickCandidate(chosen[0]);
        }
        JSONArray steps=new JSONArray();int stuck=0;
        readback("after-pick.json");
        if(held){
            keys("，ㄍㄜ");readback("before-hold.json");
            android.graphics.Rect r=new android.graphics.Rect();await("⌫").getBoundsInScreen(r);
            long down=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent d=android.view.MotionEvent.obtain(down,down,0,r.centerX(),r.centerY(),0);
            try{assertTrue(ui.injectInputEvent(d,true));}finally{d.recycle();}
            try{android.os.SystemClock.sleep(Math.max(0,down+850-android.os.SystemClock.uptimeMillis()));}
            finally{android.view.MotionEvent u=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),1,r.centerX(),r.centerY(),0);try{assertTrue(ui.injectInputEvent(u,true));}finally{u.recycle();}}
            Thread.sleep(250);inst.waitForIdleSync();readback("after-hold.json");
        }
        for(int n=0;n<8;n++){
            String before=editorText(await("test_input")),keysBefore=rawKeys();
            int cursor=await("test_input").getTextSelectionStart();
            final int[] boundary={-1};inst.runOnMainSync(()->boundary[0]=actualController().previewBoundary());
            int deletionCursor=boundary[0]>=0?boundary[0]:cursor;
            readback("delete-"+n+"-before.json");tap("⌫");Thread.sleep(150);inst.waitForIdleSync();
            String after=editorText(await("test_input")),keysAfter=rawKeys();
            int afterCursor=await("test_input").getTextSelectionStart();
            readback("delete-"+n+"-after.json");
            String expected=before.isEmpty()||deletionCursor==0?before:before.substring(0,deletionCursor-1)+before.substring(deletionCursor);
            boolean progress=after.equals(expected);
            steps.put(new JSONObject().put("tap",n+1).put("row",row).put("literal",literal)
                .put("before",before).put("after",after).put("cursor_before",cursor).put("cursor_after",afterCursor)
                .put("preview_boundary_before",boundary[0]).put("expected",expected)
                .put("length_delta",after.length()-before.length()).put("keys_before_len",keysBefore.length())
                .put("keys_after_len",keysAfter.length()).put("progress",progress));
            if(!progress)stuck++;
            save("pick-delete-steps.json",steps.toString());
            if(deletionCursor==0||after.isEmpty())break;
        }
        assertEquals("each press at a nonempty left-of-caret position makes progress: "+steps,0,stuck);
    }
    public void testPreviewPickDelete() throws Exception {replayPick(1,false);}
    public void testWordPickDelete() throws Exception {replayPick(2,false);}
    public void testCharacterPickDelete() throws Exception {replayPick(3,false);}
    public void testPreviewPickLiteralDelete() throws Exception {replayPick(1,true);}
    public void testWordPickLiteralDelete() throws Exception {replayPick(2,true);}
    public void testCharacterPickLiteralDelete() throws Exception {replayPick(3,true);}
    public void testPreviewPickHoldDelete() throws Exception {replayPick(1,true,true);}
    public void testWordPickHoldDelete() throws Exception {replayPick(2,true,true);}
    public void testCharacterPickHoldDelete() throws Exception {replayPick(3,true,true);}
}
