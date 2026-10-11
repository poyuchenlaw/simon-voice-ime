package com.simon.voiceime;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.List;

/** Physical dual-hand surface and real editor; target methods explicitly, not inherited suites. */
public class DualHand686AndroidTest extends TapCursor677AndroidTest {
    DualHandKeyboard page;
    @Override void preparePreferences(){super.preparePreferences();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("dual_hand_enabled",true).putBoolean("dual_hand_mode",false).putBoolean("ime_auto_upload",false).commit();}
    void startDual()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();text("");tap("test_input");tap("雙");await("ㄅㄆㄇㄈ");
        inst.runOnMainSync(()->{android.content.Context c=root.getContext();while(c instanceof android.content.ContextWrapper&&!(c instanceof SimonIMEService))c=((android.content.ContextWrapper)c).getBaseContext();try{var f=SimonIMEService.class.getDeclaredField("dualHandPage");f.setAccessible(true);page=(DualHandKeyboard)f.get(c);}catch(Exception e){throw new RuntimeException(e);}});
    }
    Rect key(String key){final Rect r=new Rect();inst.runOnMainSync(()->{View v=page.view().findViewWithTag("dh-key:"+key);assertNotNull(v);assertTrue(v.getLocalVisibleRect(r));int[] at=new int[2];v.getLocationOnScreen(at);r.offset(at[0],at[1]);});return r;}
    void swipe(String key,String direction)throws Exception {
        Rect r=key(key);float density=inst.getTargetContext().getResources().getDisplayMetrics().density;
        float x=r.centerX(),y=r.centerY(),dx="right".equals(direction)?32*density:"left".equals(direction)?-32*density:0,dy="down".equals(direction)?32*density:"up".equals(direction)?-32*density:0;
        long down=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(down,down,0,x,y,0);try{assertTrue(ui.injectInputEvent(d,true));}finally{d.recycle();}
        Thread.sleep(100);MotionEvent move=MotionEvent.obtain(down,SystemClock.uptimeMillis(),2,x+dx,y+dy,0);try{assertTrue(ui.injectInputEvent(move,true));}finally{move.recycle();}
        Thread.sleep(20);MotionEvent up=MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,x+dx,y+dy,0);try{assertTrue(ui.injectInputEvent(up,true));}finally{up.recycle();}Thread.sleep(100);inst.waitForIdleSync();
    }
    public void testStaticDynamicCompositionAndBackspace()throws Exception {
        startDual();screenshot("dh-static");
        swipe("2","tap");screenshot("dh-after-d");inst.runOnMainSync(()->{assertEquals("ㄉ",page.buffer().pending());FrameLayout c=(FrameLayout)page.view().findViewWithTag("dh-key:C");boolean label=false;for(int i=0;i<c.getChildCount();i++)if(c.getChildAt(i) instanceof TextView&&"ㄨㄟ".contentEquals(((TextView)c.getChildAt(i)).getText()))label=true;assertTrue("dynamic ㄨㄟ",label);});
        swipe("C","right");inst.runOnMainSync(()->assertEquals(List.of("ㄉㄨㄟ"),page.buffer().syllables()));assertEquals("對",editorText(await("test_input")));screenshot("dh-draft");
        swipe("1","tap");swipe("core","up");inst.runOnMainSync(()->{assertNull(page.buffer().pending());assertEquals(1,page.buffer().syllables().size());});assertEquals("對",editorText(await("test_input")));
        swipe("core","up");assertEquals("",editorText(await("test_input")));swipe("symbols","right");assertEquals("，",editorText(await("test_input")));swipe("core","up");assertEquals("",editorText(await("test_input")));
        swipe("tools","up");await("ㄗ");
    }
    public void testPanelsReplaceSurface()throws Exception {
        startDual();swipe("symbols","tap");inst.runOnMainSync(()->assertEquals(View.GONE,page.view().getVisibility()));screenshot("dh-symbols");tap("，");inst.runOnMainSync(()->assertEquals(View.VISIBLE,page.view().getVisibility()));assertEquals("，",editorText(await("test_input")));
        swipe("symbols","up");screenshot("dh-clipboard");tap("✕");
        swipe("tools","tap");screenshot("dh-commands");
    }
}
