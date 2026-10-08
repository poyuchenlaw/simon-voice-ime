package com.simon.voiceime;
import android.graphics.Rect;import android.os.SystemClock;import android.view.*;import org.json.*;import java.io.File;import java.util.*;import java.util.concurrent.*;import androidx.test.platform.app.InstrumentationRegistry;
/** Identical physical input and render-commit observer on both release APKs. */
public class KeyLatency670AndroidTest extends TextRows669AndroidTest {
 ViewTreeObserver.OnPreDrawListener keyPreObserver;volatile CountDownLatch draw;volatile String priorKeys="";volatile long downNs,commitNs;java.lang.reflect.Field controller;Object service;JSONArray samples=new JSONArray();
 String keys(){try{return ((ZhuyinInputController)controller.get(service)).sentenceKeys();}catch(Exception e){throw new RuntimeException(e);}}
 public void testKeyToFrame()throws Exception {
  ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();startEndpoint();inst.runOnMainSync(()->{try{bindWindow();android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();service=c;controller=SimonIMEService.class.getDeclaredField("zhuyinInput");controller.setAccessible(true);assertTrue(root.isHardwareAccelerated());keyPreObserver=()->{CountDownLatch pending=draw;if(pending!=null&&!keys().equals(priorKeys)){draw=null;root.getViewTreeObserver().registerFrameCommitCallback(()->{commitNs=System.nanoTime();pending.countDown();});}return true;};root.getViewTreeObserver().addOnPreDrawListener(keyPreObserver);}catch(Exception e){throw new RuntimeException(e);}});
  int length=Integer.parseInt(InstrumentationRegistry.getArguments().getString("length","15"));String[] base={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};Map<Character,Rect> bounds=new HashMap<>();
  for(String s:base)for(char cp:s.toCharArray())if(!bounds.containsKey(cp)){Rect r=new Rect();await(cp==' '?"空白":String.valueOf(cp)).getBoundsInScreen(r);bounds.put(cp,r);}
  int seed=Integer.parseInt(InstrumentationRegistry.getArguments().getString("touch_rows","-1"));
  long initialLearningId=0,initialLearningCount=0;
  if(seed>=0){
   Thread.sleep(1000);
   File dbFile=inst.getTargetContext().getDatabasePath("zhuyin_touch_learning.db");dbFile.getParentFile().mkdirs();
   android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile,null);
   db.execSQL("CREATE TABLE IF NOT EXISTS touch_events(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,page TEXT,key TEXT,x REAL,y REAL,cx REAL,cy REAL,package_name TEXT,session_id TEXT)");
   db.beginTransaction();try{db.execSQL("DELETE FROM touch_events");
    android.database.sqlite.SQLiteStatement insert=db.compileStatement("INSERT INTO touch_events(ts,page,key,x,y,cx,cy,package_name,session_id) VALUES(1,'bopomofo','ㄐ',0,0,0,0,'fixture','fixture')");
    for(int n=0;n<seed;n++)insert.executeInsert();insert.close();db.setVersion(1);db.setTransactionSuccessful();
   }finally{db.endTransaction();}
   assertEquals(1,db.getVersion());
   initialLearningCount=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM touch_events",null);
   assertEquals(seed,initialLearningCount);
   initialLearningId=android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(MAX(id),0) FROM touch_events",null);db.close();
  }
  Thread.sleep(500);int step=0;
  for(int n=0;n<length;n++)for(char cp:base[n%base.length].toCharArray()){
   final String[] before={null};CountDownLatch pending=new CountDownLatch(1);inst.runOnMainSync(()->before[0]=keys());priorKeys=before[0];draw=pending;Rect r=bounds.get(cp);long t=SystemClock.uptimeMillis();downNs=System.nanoTime();MotionEvent d=MotionEvent.obtain(t,t,0,r.centerX(),r.centerY(),0),u=MotionEvent.obtain(t,t+20,1,r.centerX(),r.centerY(),0);assertTrue(ui.injectInputEvent(d,false)&&ui.injectInputEvent(u,false));d.recycle();u.recycle();
   assertTrue("updated controller must reach committed frame",pending.await(10,TimeUnit.SECONDS));double ms=(commitNs-downNs)/1e6;samples.put(new JSONObject().put("key",step++).put("character_index",n).put("latency_ms",ms).put("down_ns",downNs).put("frame_commit_ns",commitNs));long rest=197-(SystemClock.uptimeMillis()-t);if(rest>0)Thread.sleep(rest);
  }
  if(seed>=0){
   Thread.sleep(1500);
   android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(inst.getTargetContext().getDatabasePath("zhuyin_touch_learning.db").getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
   try{long count=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM touch_events",null),lastId=android.database.DatabaseUtils.longForQuery(db,"SELECT COALESCE(MAX(id),0) FROM touch_events",null);
    long newRows=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM touch_events WHERE id > ?",new String[]{String.valueOf(initialLearningId)});
    save("learning-workload.json",new JSONObject().put("version",db.getVersion()).put("seed",seed).put("initial_count",initialLearningCount).put("count",count).put("new_rows",newRows).put("initial_id",initialLearningId).put("last_id",lastId).toString());
    assertEquals(1,db.getVersion());assertTrue("learning writes must advance id",lastId>initialLearningId);assertTrue("learning rows must actually be inserted",newRows>0);assertTrue("row count must grow unless at cap",seed==50000||count>initialLearningCount);assertTrue(count<=50000);
   }finally{db.close();}
  }
  save("latency-final-text.json",new JSONObject().put("text",String.valueOf(await("test_input").getText())).toString());
  save("key-latency.json",new JSONObject().put("touch_rows",seed).put("characters",length).put("cadence_ms",197).put("clock","System.nanoTime down to updated-controller pre-draw registration, same-frame commit callback").put("samples",samples).toString());
 }
 @Override protected void tearDown()throws Exception{if(out!=null)save("partial-key-latency.json",samples.toString());stop=true;if(endpoint!=null)endpoint.close();if(server!=null)server.join(5000);if(root!=null&&keyPreObserver!=null)inst.runOnMainSync(()->root.getViewTreeObserver().removeOnPreDrawListener(keyPreObserver));super.tearDown();}
}
