package com.simon.voiceime;
import android.graphics.*;import android.os.*;import android.view.*;import android.widget.*;import java.util.*;import java.net.*;import java.io.*;
/** X1 targets test methods explicitly; inherited suites retain their own entrypoints. */
public class App689AndroidTest extends DualHandTracks686AndroidTest {
 @Override void preparePreferences(){super.preparePreferences();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("dual_hand_landscape_split",true).commit();}
 private Object field(Object owner,String name){try{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception failure){throw new RuntimeException(failure);}}
 private SimonIMEService service(){android.content.Context c=page.view().getContext();while(c instanceof android.content.ContextWrapper&&!(c instanceof SimonIMEService))c=((android.content.ContextWrapper)c).getBaseContext();return (SimonIMEService)c;}
 public void testLongDeleteRepeatsOneUnitAndReleaseDoesNotDeleteHost()throws Exception{
  startDual();text("甲");swipe("3","tap");swipe("A","right");Rect r=key("core");float density=inst.getTargetContext().getResources().getDisplayMetrics().density;long down=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0);MotionEvent move=MotionEvent.obtain(down,down+100,MotionEvent.ACTION_MOVE,r.centerX(),r.centerY()-32*density,0);try{assertTrue(ui.injectInputEvent(d,true));Thread.sleep(100);assertTrue(ui.injectInputEvent(move,true));Thread.sleep(480);}finally{d.recycle();move.recycle();}MotionEvent up=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,r.centerX(),r.centerY()-32*density,0);try{assertTrue(ui.injectInputEvent(up,true));}finally{up.recycle();}inst.waitForIdleSync();inst.runOnMainSync(()->{assertTrue(page.buffer().syllables().isEmpty());assertNull(page.buffer().pending());});assertEquals("甲",editorText(await("test_input")));
 }
 public void testPreviewTwentyPendingSelectionAndFont()throws Exception{
  startDual();inst.runOnMainSync(()->{for(int i=0;i<20;i++){var s=page.buffer().begin("Ø",i*200);page.buffer().end(s,"right",false);page.buffer().drain(i*200+100);}var s=page.buffer().begin("1",5000);page.buffer().end(s,"tap",false);page.buffer().drain(5100);page.refresh();});inst.waitForIdleSync();
  inst.runOnMainSync(()->{assertEquals(20,page.buffer().syllables().size());assertEquals(String.join(" ",Collections.nCopies(20,"ㄨ"))+" ㄅ",page.buffer().preview());HorizontalScrollView preview=page.view().findViewWithTag("dh-phonetic-preview");assertTrue(preview.getScrollX()>0);LinearLayout items=page.view().findViewWithTag("dh-preview-items");assertEquals(21,items.getChildCount());Object bundled=field(page,"typeface");for(int i=0;i<21;i++)assertSame(bundled,((TextView)items.getChildAt(i)).getTypeface());items.getChildAt(3).performClick();assertEquals(3,page.buffer().selected());assertNull(page.buffer().pending());});screenshot("689-preview-20");
 }
 public void testNameSingleScrollAndLockedAiZeroRequests()throws Exception{
  startDual();try(ServerSocket fake=new ServerSocket(0,2,InetAddress.getByName("127.0.0.1"))){var prefs=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);String old=prefs.getString("server_url","");prefs.edit().putString("server_url","http://127.0.0.1:"+fake.getLocalPort()).commit();
   try{swipe("5","up");swipe("E","left");swipe("1","tap");swipe("B","tap");swipe("Ø","down");inst.runOnMainSync(()->{page.buffer().select(0);page.refresh();});waitCandidates();choose("陳");choose("柏");choose("宇");swipe("core","down");fake.setSoTimeout(300);try{Socket leaked=fake.accept();leaked.close();fail("locked name emitted a network request");}catch(SocketTimeoutException expected){}swipe("symbols","tap");assertEquals("陳柏宇",editorText(await("test_input")));}
   finally{prefs.edit().putString("server_url",old).commit();}
  }
 }
 private void choose(String text)throws Exception{final boolean[] found={false};inst.runOnMainSync(()->{for(int i=0;i<page.candidateRow().getChildCount();i++){View v=page.candidateRow().getChildAt(i);if(v instanceof TextView&&text.contentEquals(((TextView)v).getText())){v.performClick();found[0]=true;break;}}});assertTrue("all readings available: "+text,found[0]);inst.waitForIdleSync();}
 public void testClipboardStrictModeAndThirtyReopenNoRebuild()throws Exception{
  startDual();final Object[] key={null};final int[] width={0};inst.runOnMainSync(()->{key[0]=page.view().findViewWithTag("dh-key:A");width[0]=(Integer)field(page,"cachedWidth");});
  inst.runOnMainSync(()->{SimonIMEService service=service();ClipboardHelper h=(ClipboardHelper)field(service,"clipboardHelper");StrictMode.ThreadPolicy previous=StrictMode.getThreadPolicy();StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectDiskWrites().penaltyDeath().build());try{h.addToHistory("689 synthetic strictmode");for(int i=0;i<30;i++){page.reconfigure();assertSame(key[0],page.view().findViewWithTag("dh-key:A"));assertEquals(width[0],field(page,"cachedWidth"));}}finally{StrictMode.setThreadPolicy(previous);}});
 }
 private void holdMove(String key,String direction,java.util.function.Consumer<FrameLayout> check)throws Exception{
  Rect r=key(key);float density=inst.getTargetContext().getResources().getDisplayMetrics().density;float x=r.centerX(),y=r.centerY(),dx="right".equals(direction)?32*density:"left".equals(direction)?-32*density:0,dy="up".equals(direction)?-32*density:"down".equals(direction)?32*density:0;long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,x,y,0),move=MotionEvent.obtain(t,t+100,MotionEvent.ACTION_MOVE,x+dx,y+dy,0);
  try{assertTrue(ui.injectInputEvent(down,true));Thread.sleep(100);assertTrue(ui.injectInputEvent(move,true));Thread.sleep(100);inst.runOnMainSync(()->check.accept(page.view().findViewWithTag("dh-key:"+key)));screenshot("689-hold-"+key+"-"+direction);}
  finally{down.recycle();move.recycle();MotionEvent cancel=MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,x+dx,y+dy,0);try{ui.injectInputEvent(cancel,true);}finally{cancel.recycle();}}
 }
 public void testHeldSwipeBubbleHighlightForceAndBottomExclusion()throws Exception{
  startDual();holdMove("A","up",cap->{TextView label=(TextView)cap.getChildAt(1);assertTrue(label.isActivated());assertEquals(1.15f,label.getScaleX(),.025f);if(cap.isHardwareAccelerated())assertTrue(cap.getTranslationY()<0);else assertEquals(0f,cap.getTranslationY(),0f);PopupWindow bubble=(PopupWindow)field(page,"bubble");assertTrue(bubble.isShowing());assertEquals("ㄧㄚ",((TextView)field(page,"bubbleLabel")).getText().toString());assertSame(field(page,"typeface"),((TextView)field(page,"bubbleLabel")).getTypeface());int[] at=new int[2],keyboard=new int[2];bubble.getContentView().getLocationOnScreen(at);page.view().getLocationOnScreen(keyboard);assertTrue("top-row bubble extends outside keyboard",at[1]<keyboard[1]);assertTrue(at[0]>=0&&at[0]+bubble.getWidth()<=page.view().getResources().getDisplayMetrics().widthPixels);});
  swipe("1","tap");holdMove("Ø","left",cap->{PopupWindow bubble=(PopupWindow)field(page,"bubble");assertFalse(bubble.isShowing());assertFalse(cap.getChildAt(2).isActivated());});
  for(String key:new String[]{"tools","symbols","clipboard","core"})for(String dir:new String[]{"up","left","down","right"})holdMove(key,dir,cap->{PopupWindow bubble=(PopupWindow)field(page,"bubble");assertTrue(bubble==null||!bubble.isShowing());});
 }
 public void testTypefaceDefaultSymbolsAndAllPendingLabels()throws Exception{
  startDual();inst.runOnMainSync(()->{Paint p=new Paint();p.setTypeface(Typeface.DEFAULT);for(String icon:new String[]{"⌫","⚙"})android.util.Log.i("IME689X1","systemGlyph "+icon+"="+p.hasGlyph(icon)+"; false uses vector fallback");Object type=field(page,"typeface");for(String key:new String[]{"1","2","3","4","5","6","A","B","C","D","E","F","Ø","tools","symbols","clipboard","core"}){FrameLayout cap=page.view().findViewWithTag("dh-key:"+key);for(int i=0;i<cap.getChildCount();i++)assertSame(type,((TextView)cap.getChildAt(i)).getTypeface());}});
 }
 public void testLifecycleStepsAndThirtyReopensMeasured()throws Exception{
  startDual();inst.runOnMainSync(()->{
   SimonIMEService service=service();ClipboardHelper clipboard=(ClipboardHelper)field(service,"clipboardHelper");
   String[] names={"clipboard_recovery","theme_apply_baseline_cost","reconfigure_cached","onStartInputView","onFinishInputView"};long[][] samples=new long[5][30];
   for(int i=0;i<30;i++){
    long t=System.nanoTime();clipboard.captureCurrent("689_profile");samples[0][i]=System.nanoTime()-t;
    t=System.nanoTime();KeyboardTheme.apply(service,service.rootView);samples[1][i]=System.nanoTime()-t;
    t=System.nanoTime();page.reconfigure();samples[2][i]=System.nanoTime()-t;
    t=System.nanoTime();service.onStartInputView(service.getCurrentInputEditorInfo(),true);samples[3][i]=System.nanoTime()-t;
    t=System.nanoTime();service.onFinishInputView(false);samples[4][i]=System.nanoTime()-t;
   }
   for(int j=0;j<names.length;j++){Arrays.sort(samples[j]);android.util.Log.i("IME689X1",names[j]+" median_ms="+samples[j][15]/1e6+" p90_ms="+samples[j][26]/1e6+" max_ms="+samples[j][29]/1e6+" raw_ns="+Arrays.toString(samples[j]));}
   assertTrue("30 starts p90 must be below 50ms",samples[3][26]<50_000_000);assertTrue("no >=100ms start stall",samples[3][29]<100_000_000);
  });
 }
 public void testThirtyCanvasDrawsMeasured()throws Exception{
  startDual();long[] times=new long[30];inst.runOnMainSync(()->{View v=page.view();Bitmap bitmap=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);for(int i=0;i<30;i++){long start=System.nanoTime();v.draw(canvas);times[i]=System.nanoTime()-start;}bitmap.recycle();});Arrays.sort(times);android.util.Log.i("IME689X1","canvas30 median_ms="+times[15]/1e6+" p90_ms="+times[26]/1e6+" max_ms="+times[29]/1e6+" (software drawing; gfxinfo separately required)");
 }
 public void testLandscapeInsetsNoExtractAndSettingExit()throws Exception{
  startDual();inst.runOnMainSync(()->{assertTrue("X1 must run this case on a feasible landscape device",page.landscapeSplit());assertFalse(service().onEvaluateFullscreenMode());android.inputmethodservice.InputMethodService.Insets in=new android.inputmethodservice.InputMethodService.Insets();service().onComputeInsets(in);assertEquals(android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_REGION,in.touchableInsets);int[] origin=new int[2];page.view().getLocationInWindow(origin);int y=origin[1]+32*Math.round(page.view().getResources().getDisplayMetrics().density)+10;assertFalse(in.touchableRegion.contains(origin[0]+page.view().getWidth()/2,y));assertTrue(in.touchableRegion.contains(origin[0]+10,y));assertTrue(in.touchableRegion.contains(origin[0]+page.view().getWidth()-10,y));var prefs=inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0);try{prefs.edit().putBoolean("dual_hand_landscape_split",false).commit();page.reconfigure();assertFalse(page.landscapeSplit());}finally{prefs.edit().putBoolean("dual_hand_landscape_split",true).commit();page.reconfigure();}});
 }
}
