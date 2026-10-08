package com.simon.voiceime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Bounded on-device touch-offset corpus for improving the 41-key Zhuyin page. */
final class TouchLearningStore extends SQLiteOpenHelper {
    // At most 128 accepted samples can be lost on process death (including the active batch).
    private static final int MAX_PENDING=128, BATCH_SIZE=32;
    private final ArrayDeque<Object[]> pending=new ArrayDeque<>();
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->{
        Thread t=new Thread(r,"TouchLearningIO");t.setDaemon(true);return t;
    });
    private int outstanding;
    private long retained=-1, lastCountNs, skipped, reportedSkipped;
    private int batchesSinceCount;
    private boolean scheduled,closed;
    TouchLearningStore(Context context) { super(context, "zhuyin_touch_learning.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE touch_events(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,page TEXT,key TEXT,x REAL,y REAL,cx REAL,cy REAL,package_name TEXT,session_id TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    synchronized void touch(long timestamp, String page, String key, float x, float y,
                            float centerX, float centerY, String packageName, String sessionId) {
        if(closed)return;
        // Learning is sampled under overload; typing never waits for storage.
        if(outstanding==MAX_PENDING){skipped++;return;}
        pending.addLast(new Object[]{timestamp,page,key,x,y,centerX,centerY,packageName,sessionId});
        outstanding++;
        if(!scheduled){scheduled=true;writer.schedule(this::writeBatches,50,TimeUnit.MILLISECONDS);}
    }
    private void writeBatches() {
        while(!Thread.currentThread().isInterrupted()) {
            List<Object[]> batch=new ArrayList<>(BATCH_SIZE);
            synchronized(this){
                while(batch.size()<BATCH_SIZE&&!pending.isEmpty())batch.add(pending.removeFirst());
                if(batch.isEmpty()){scheduled=false;return;}
            }
            try {
                SQLiteDatabase db=getWritableDatabase();
                if(retained<0||batchesSinceCount>=100||System.nanoTime()-lastCountNs>=TimeUnit.MINUTES.toNanos(5)){
                    retained=android.database.DatabaseUtils.longForQuery(db,"SELECT COUNT(*) FROM touch_events",null);
                    batchesSinceCount=0;lastCountNs=System.nanoTime();
                }
                long after=retained+batch.size();db.beginTransaction();
                try {
                    for(Object[] sample:batch)db.execSQL("INSERT INTO touch_events(ts,page,key,x,y,cx,cy,package_name,session_id) VALUES(?,?,?,?,?,?,?,?,?)",sample);
                    // Seek only the excess oldest rows, not all 50,000 retained rows.
                    if(after>50000)db.execSQL("DELETE FROM touch_events WHERE id <= (SELECT id FROM touch_events ORDER BY id LIMIT 1 OFFSET ?)",new Object[]{after-50000-1});
                    db.setTransactionSuccessful();
                }finally{db.endTransaction();}
                retained=Math.min(50000,after);batchesSinceCount++;
                synchronized(this){outstanding-=batch.size();}
                reportSkipped();
            }catch(RuntimeException failure){
                synchronized(this){for(int i=batch.size()-1;i>=0;i--)pending.addFirst(batch.get(i));}
                retained=-1;reportSkipped();
                System.err.println("TouchLearningStore batch retained for retry: "+failure);
                if(!writer.isShutdown())writer.schedule(this::writeBatches,1,TimeUnit.SECONDS);return;
            }
        }
    }
    private void reportSkipped() {
        long total;
        synchronized(this){total=skipped;}
        if(total==reportedSkipped)return;
        ImeTelemetry telemetry=ImeTelemetry.get();
        if(telemetry!=null)try{
            telemetry.record("learning","bopomofo",new org.json.JSONObject().put("step","touch_learning_overflow").put("skipped_total",total).put("max_pending",MAX_PENDING),false);
            reportedSkipped=total;
        }catch(org.json.JSONException e){System.err.println("TouchLearningStore diagnostic failed: "+e);}
    }
    @Override public void close() {
        synchronized(this){closed=true;}
        writer.shutdown();
        try{
            if(!writer.awaitTermination(2,TimeUnit.SECONDS)){
                System.err.println("TouchLearningStore close timed out; pending learning samples may be lost");writer.shutdownNow();
            }
        }catch(InterruptedException e){writer.shutdownNow();Thread.currentThread().interrupt();}
        finally{super.close();}
    }
}
