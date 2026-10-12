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
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();text("");tap("test_input");tap("雙");await("1 ㄅ up ㄆ left ㄇ down ㄈ");
        await("41鍵 up ⚙ left 英數 down 收起 right 語音");await("空白 up 、 left 符號 down 。 right ，");await("送出 up ⌫ left 刪字 down AI right 送出");
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
    public void testMixedLabelsAndIndependentBottomWidths()throws Exception {
        startDual();
        for(String pending:new String[]{"none","ㄅ","ㄐ","ㄨ"}){
            inst.runOnMainSync(()->{page.buffer().clearBuffer();
                String key=pending.equals("ㄅ")?"1":pending.equals("ㄐ")?"4":"3";
                String direction=pending.equals("ㄨ")?"right":"tap";
                if(!pending.equals("none")){DualHandState.Stroke stroke=page.buffer().begin(key,300);page.buffer().end(stroke,direction,false);page.buffer().drain(400);}page.refresh();});
            inst.waitForIdleSync();
            inst.runOnMainSync(()->{
                float density=inst.getTargetContext().getResources().getDisplayMetrics().density,scaled=inst.getTargetContext().getResources().getDisplayMetrics().scaledDensity;
                View left=page.view().findViewWithTag("dh-key:1"),right=page.view().findViewWithTag("dh-key:A");assertTrue(left.getWidth()<right.getWidth());assertTrue(left.getWidth()/density>=64);
                for(String key:new String[]{"1","2","3","4","5","6","A","B","C","D","E","F","Ø","tools","symbols","clipboard","core"}){
                    FrameLayout box=(FrameLayout)page.view().findViewWithTag("dh-key:"+key);if(key.equals("Ø"))assertTrue(box.getWidth()/density>=64);
                    java.util.List<Rect> occupied=new java.util.ArrayList<>();float sp=((TextView)box.getChildAt(0)).getTextSize()/scaled;
                    for(int i=0;i<5;i++){
                        TextView label=(TextView)box.getChildAt(i);assertTrue(label.getTextSize()/scaled>=12f);assertEquals(sp,label.getTextSize()/scaled,.001f);
                        if(i==1||i==3)assertFalse("up/down horizontal",label.getText().toString().contains("\n"));
                        if(label.getVisibility()!=View.VISIBLE)continue;
                        Rect r=new Rect(label.getLeft(),label.getTop(),label.getRight(),label.getBottom());assertTrue(r.left>=0&&r.top>=0&&r.right<=box.getWidth()&&r.bottom<=box.getHeight());for(Rect other:occupied)assertFalse("labels overlap",Rect.intersects(r,other));occupied.add(r);
                    }
                }
            });screenshot("dh-mixed-"+pending);
        }
    }
    public void testStaticDynamicCompositionAndBackspace()throws Exception {
        startDual();screenshot("dh-static");
        swipe("2","tap");screenshot("dh-after-d");inst.runOnMainSync(()->{assertEquals("ㄉ",page.buffer().pending());FrameLayout c=(FrameLayout)page.view().findViewWithTag("dh-key:C");boolean label=false;for(int i=0;i<c.getChildCount();i++)if(c.getChildAt(i) instanceof TextView&&"ㄨ\nㄟ".contentEquals(((TextView)c.getChildAt(i)).getText()))label=true;assertTrue("dynamic ㄨㄟ",label);});
        swipe("C","right");inst.runOnMainSync(()->assertEquals(List.of("ㄉㄨㄟ"),page.buffer().syllables()));assertEquals("對",editorText(await("test_input")));screenshot("dh-draft");
        swipe("1","tap");swipe("core","up");inst.runOnMainSync(()->{assertNull(page.buffer().pending());assertEquals(1,page.buffer().syllables().size());});assertEquals("對",editorText(await("test_input")));
        swipe("core","up");assertEquals("",editorText(await("test_input")));swipe("symbols","right");assertEquals("，",editorText(await("test_input")));swipe("core","up");assertEquals("",editorText(await("test_input")));
        swipe("tools","tap");await("ㄗ");
    }
    public void testDirectionalLabelsAndTwoPointInjection()throws Exception {
        startDual();
        inst.runOnMainSync(()->{
            String[][] expected={{"ㄅ","ㄆ","ㄇ","ㄈ",""},{"ㄉ","ㄊ","ㄋ","ㄌ",""},{"ㄍ","ㄎ","ㄏ","Ø","ㄨ"},{"ㄐ","ㄑ","ㄒ","ㄩ","ㄧ"},{"ㄓ","ㄔ","ㄕ","ㄖ",""},{"ㄗ","ㄘ","ㄙ","",""}};
            for(int k=0;k<6;k++){
                FrameLayout box=(FrameLayout)page.view().findViewWithTag("dh-key:"+(k+1));java.util.List<Rect> occupied=new java.util.ArrayList<>();
                for(int i=0;i<5;i++){
                    TextView label=(TextView)box.getChildAt(i);assertEquals(expected[k][i],label.getText().toString());
                    assertEquals(expected[k][i].isEmpty()?View.GONE:View.VISIBLE,label.getVisibility());if(label.getVisibility()!=View.VISIBLE)continue;
                    Rect r=new Rect(label.getLeft(),label.getTop(),label.getRight(),label.getBottom());assertTrue("label inside key",r.left>=0&&r.top>=0&&r.right<=box.getWidth()&&r.bottom<=box.getHeight());
                    for(Rect other:occupied)assertFalse("left labels must not overlap",Rect.intersects(r,other));occupied.add(r);
                }
            }
            try{assertEquals("dual_hand",geometry().getString("mode"));}catch(Exception e){throw new RuntimeException(e);}
        });
        Rect r=key("2");float distance=30*inst.getTargetContext().getResources().getDisplayMetrics().density;long t=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0),up=MotionEvent.obtain(t,t+40,MotionEvent.ACTION_UP,r.centerX()+distance,r.centerY(),0);
        try{assertTrue(ui.injectInputEvent(down,true)&&ui.injectInputEvent(up,true));}finally{down.recycle();up.recycle();}Thread.sleep(100);inst.waitForIdleSync();
        // Key 2 right is empty: a tap would incorrectly leave ㄉ pending.
        inst.runOnMainSync(()->assertNull("two-point right swipe is not a tap",page.buffer().pending()));
        swipe("2","tap");inst.runOnMainSync(()->{
            FrameLayout c=(FrameLayout)page.view().findViewWithTag("dh-key:C");TextView upLabel=(TextView)c.getChildAt(1);
            assertEquals("",upLabel.getText().toString());assertEquals(View.GONE,upLabel.getVisibility());
            assertEquals("ㄨ\nㄟ",((TextView)c.getChildAt(4)).getText().toString());
        });screenshot("dh-directional-labels");
    }
    public void testPanelsReplaceSurface()throws Exception {
        startDual();swipe("symbols","left");inst.runOnMainSync(()->assertEquals(View.GONE,page.view().getVisibility()));screenshot("dh-symbols");tap("，");inst.runOnMainSync(()->assertEquals(View.VISIBLE,page.view().getVisibility()));assertEquals("，",editorText(await("test_input")));
        swipe("clipboard","up");screenshot("dh-commands");tap("剪貼簿");screenshot("dh-clipboard");tap("✕");
    }
    void assertFunctionHints(boolean restore,boolean bright){
        inst.runOnMainSync(()->{
            String[] keys={"tools","symbols","clipboard","core"};String[][] expected={{"41","⚙","英","收","語"},{"空白","、","符","。","，"},{"剪貼簿","常用詞","","",""},{"送出","⌫",restore?"還":page.landscapeSplit()?"刪字":"刪\n字","AI","送"}};
            int normal=KeyboardTheme.resourceColor(inst.getTargetContext(),R.color.keyboard_key_text);
            for(int k=0;k<keys.length;k++){
                FrameLayout box=(FrameLayout)page.view().findViewWithTag("dh-key:"+keys[k]);assertEquals(5,box.getChildCount());java.util.List<Rect> occupied=new java.util.ArrayList<>();
                for(int i=0;i<5;i++){
                    TextView label=(TextView)box.getChildAt(i);assertEquals(expected[k][i],label.getText().toString());
                    assertEquals(expected[k][i].isEmpty()?View.GONE:View.VISIBLE,label.getVisibility());if(label.getVisibility()!=View.VISIBLE)continue;
                    Rect r=new Rect(label.getLeft(),label.getTop(),label.getRight(),label.getBottom());assertTrue("function label inside key",r.left>=0&&r.top>=0&&r.right<=box.getWidth()&&r.bottom<=box.getHeight());
                    for(Rect other:occupied)assertFalse("function labels must not overlap",Rect.intersects(r,other));occupied.add(r);
                    float scale=label.getResources().getDisplayMetrics().scaledDensity;
                    assertTrue("all function glyphs >=12sp",label.getTextSize()/scale>=12f);
                    float[] ink=DualHandLabelView.extent(label.getText().toString(),label.getPaint());assertTrue("full drawn label fits",ink[0]<=label.getWidth()&&ink[1]<=label.getHeight());
                    if(k==3)assertEquals((bright&&(i==0||i==3))||(restore&&i==2)?KeyboardTheme.resourceColor(inst.getTargetContext(),R.color.mode_append):DualHandVisuals.color(KeyboardTheme.isDark(inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).getString("keyboard_theme","system"),(inst.getTargetContext().getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES),i==0?"text":"secondary"),label.getCurrentTextColor());
                }
                if(k==3)assertEquals(bright,box.isActivated());
            }
        });
    }
    public void testFunctionHintsAndHighlights()throws Exception {
        startDual();assertFunctionHints(false,false);screenshot("dh-function-hints");
        for(int count=1;count<=9;count++){
            final int n=count;inst.runOnMainSync(()->{for(String key:new String[]{"1","A"}){long t=n*200+("A".equals(key)?100:0);DualHandState.Stroke s=page.buffer().begin(key,t);page.buffer().end(s,"tap",false);page.buffer().drain(t+100);}page.refresh();});
            inst.waitForIdleSync();if(count>=8)assertFunctionHints(false,count>8);
        }
        inst.runOnMainSync(()->{assertTrue(page.buffer().applyAi("一".repeat(9)));page.refresh();});inst.waitForIdleSync();await("送出 up ⌫ left 還原 down AI right 送出");assertFunctionHints(true,true);screenshot("dh-function-ai");
        inst.runOnMainSync(()->{assertTrue(page.buffer().restoreAi());page.buffer().backspace();page.refresh();});inst.waitForIdleSync();await("送出 up ⌫ left 刪字 down AI right 送出");assertFunctionHints(false,false);
        inst.runOnMainSync(()->{page.buffer().discardPending();assertTrue(page.buffer().applyAi("一".repeat(8)));page.refresh();});inst.waitForIdleSync();assertFunctionHints(true,false);
        inst.runOnMainSync(()->{page.buffer().clearBuffer();page.refresh();});inst.waitForIdleSync();assertFunctionHints(false,false);
    }
}
