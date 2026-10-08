package com.simon.voiceime;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reproduce the reported preedit using real key and candidate screen events. */
public class Bianshi678AndroidTest extends ExternalSelection675AndroidTest {
 public void testReportedCandidateTap() throws Exception {
  var args=InstrumentationRegistry.getArguments();
  begin();tap("test_input");awaitStableWindow();
  for(String key:new String[]{"ㄑ","ㄧ","ㄥ","空白","ㄒ","ㄧ","空白","ㄅ","ㄧ","ㄢ"})tap(key);
  if(!"true".equals(args.getString("bian_untoned")))tap("ˋ");
  if(args.getString("initial_bian")!=null){
   Thread.sleep(400);inst.waitForIdleSync();final Rect initial=new Rect();
   inst.runOnMainSync(()->{View target=null;for(int n=0;n<characters.getChildCount();n++)if(args.getString("initial_bian").contentEquals(((TextView)characters.getChildAt(n)).getText())){target=characters.getChildAt(n);break;}assertNotNull("requested typed prefix candidate",target);initial.set(screenRect(target));assertTrue("prefix candidate visible",initial.intersect(screenRect(charScroll)));});
   tap(initial);inst.waitForIdleSync();screenshot("selected-prefix");readback("selected-prefix-editor-text.json");
  }
  tap("ㄕ");
  if(!"true".equals(args.getString("untoned")))tap("ˋ");
  Thread.sleep(600);inst.waitForIdleSync();
  rowTags("before-row-tags.json");readback("before-editor-text.json");screenshot("before-candidate-tap");
  final LinearLayout row="characters".equals(args.getString("row","words"))?characters:words;
  final int[] chosen={Integer.parseInt(args.getString("index","0"))};
  if(args.getString("label")!=null)inst.runOnMainSync(()->{chosen[0]=-1;for(int n=0;n<row.getChildCount();n++)if(args.getString("label").contentEquals(((TextView)row.getChildAt(n)).getText())){chosen[0]=n;break;}assertTrue("requested label present",chosen[0]>=0);});
  final int index=chosen[0];
  if("true".equals(args.getString("scroll"))){
   final Rect[] bounds={null,null};final int[] scrollBefore={0};
   inst.runOnMainSync(()->{bounds[0]=screenRect(row.getChildAt(index));bounds[1]=screenRect((View)row.getParent());scrollBefore[0]=((android.widget.HorizontalScrollView)row.getParent()).getScrollX();});
   JSONArray swipes=new JSONArray();
   for(int n=0;n<8;n++){
    inst.runOnMainSync(()->bounds[0]=screenRect(row.getChildAt(index)));
    int delta=bounds[0].left-bounds[1].left-6,y=bounds[1].centerY(),start=bounds[1].centerX();
    if(Math.abs(delta)<=10)break;
    delta=Math.max(-bounds[1].width()/3,Math.min(bounds[1].width()/3,delta));
    swipes.put(new JSONObject().put("from_x",start).put("to_x",start-delta).put("y",y));
    shell("input swipe "+start+" "+y+" "+(start-delta)+" "+y+" 400");Thread.sleep(600);inst.waitForIdleSync();
   }
   save("scroll-state.json",new JSONObject().put("before",scrollBefore[0]).put("after",((android.widget.HorizontalScrollView)row.getParent()).getScrollX()).put("swipes",swipes).toString());
   screenshot("scrolled-before-candidate-tap");
  }
  final Rect hit=new Rect();final TextView[] item={null};
  inst.runOnMainSync(()->{assertTrue("candidate exists",row.getChildCount()>index);item[0]=(TextView)row.getChildAt(index);int[] at=new int[2];item[0].getLocationOnScreen(at);hit.set(at[0],at[1],at[0]+item[0].getWidth(),at[1]+item[0].getHeight());assertTrue("target physically visible",hit.intersect(screenRect((View)row.getParent())));});
  String label=item[0].getText().toString();JSONArray events=new JSONArray();

  String before=shown();int x="edge".equals(args.getString("position"))?hit.left+2:hit.centerX();int y=hit.centerY();
  int hold=Integer.parseInt(args.getString("hold","20"));int drift=Integer.parseInt(args.getString("drift","0"));
  long t=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(t,t,0,x,y,0),up=MotionEvent.obtain(t,t+hold,1,x+drift,y,0);
  assertTrue(ui.injectInputEvent(down,true));Thread.sleep(hold);
  if(drift!=0){MotionEvent move=MotionEvent.obtain(t,SystemClock.uptimeMillis(),2,x+drift,y,0);assertTrue(ui.injectInputEvent(move,true));move.recycle();}
  assertTrue(ui.injectInputEvent(up,true));down.recycle();up.recycle();Thread.sleep(400);inst.waitForIdleSync();
  assertCandidateDiagnostics(label.length());
  save("candidate-touch-events.json",events.toString());rowTags("after-row-tags.json");readback("after-editor-text.json");screenshot("after-candidate-tap");
  save("tap-result.json",new JSONObject().put("before_preview",before).put("after_preview",shown()).put("label",label).put("row",args.getString("row","words")).put("index",index).put("x",x).put("y",y).put("hold",hold).put("drift",drift).put("rect",hit.toShortString()).put("apk_sha256",args.getString("apk_sha256")).toString());
  String expected=args.getString("expected");
  if(expected!=null)assertEquals("candidate must replace only the intended suffix",expected,shown());
  tap("↵");Thread.sleep(300);readback("committed-editor-text.json");screenshot("after-enter");
  if(expected!=null)assertEquals("Enter commits exact selected preview",expected,editorText(await("test_input")));
 }
 void assertCandidateDiagnostics(int length)throws Exception{
  java.io.File file=new java.io.File(inst.getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");
  long deadline=SystemClock.uptimeMillis()+3000;
  while(SystemClock.uptimeMillis()<deadline){
   boolean down=false,up=false,replaced=false;
   if(file.exists())for(String line:java.nio.file.Files.readAllLines(file.toPath())){
    JSONObject event=new JSONObject(line);
    if(!"candidate_touch".equals(event.optString("type")))continue;
    assertFalse("candidate diagnostic must omit plaintext",event.has("candidate"));
    if(event.optInt("candidate_length")!=length)continue;
    down|="down".equals(event.optString("phase"))&&event.optLong("down_uptime_ms")>0;
    up|="up".equals(event.optString("phase"))&&event.optLong("up_uptime_ms")>0;
    replaced|="replacement".equals(event.optString("phase"))&&event.optBoolean("replacement_success")
      &&event.optLong("down_uptime_ms")>0&&event.optLong("up_uptime_ms")>0&&event.optInt("row")>0;
   }
   if(down&&up&&replaced)return;
   Thread.sleep(50);
  }
  fail("production down/up/replacement diagnostics missing");
 }
 Rect screenRect(View view){int[] at=new int[2];view.getLocationOnScreen(at);return new Rect(at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight());}
 public void testLearningOverflowDiagnostic()throws Exception{
  begin();
  android.content.Context context=inst.getTargetContext();
  TouchLearningStore learning=new TouchLearningStore(context);
  android.database.sqlite.SQLiteDatabase blocker=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("zhuyin_touch_learning.db"),null);
  learning.getWritableDatabase();
  blocker.beginTransaction();
  try{for(int n=0;n<137;n++)learning.touch(n,"bopomofo","",0,0,0,0,"fixture","fixture");}
  finally{blocker.endTransaction();blocker.close();}
  learning.close();Thread.sleep(500);
  save("overflow-workload-text.json",new org.json.JSONObject().put("submitted",137).put("expected_skipped",9).put("text",editorText(await("test_input"))).toString());
  screenshot("overflow-diagnostic");
 }
}
