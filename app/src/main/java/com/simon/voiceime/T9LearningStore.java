package com.simon.voiceime;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.*;

/** App-private, bounded input telemetry. Caller must filter protected editor fields. */
final class T9LearningStore extends SQLiteOpenHelper {
    T9LearningStore(Context c) { super(c, "t9_learning.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE touch_events(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,page TEXT,key TEXT,x REAL,y REAL,cx REAL,cy REAL,package_name TEXT,sentence_id TEXT)");
        db.execSQL("CREATE TABLE commits(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,package_name TEXT,keys TEXT,first_candidate TEXT,ai_suggestion TEXT,final_text TEXT,bubble_changed INTEGER,changed_segment INTEGER)");
        db.execSQL("CREATE TABLE personal_lexicon(word TEXT PRIMARY KEY,pronunciation TEXT,use_count INTEGER,last_used INTEGER)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }
    synchronized void touch(long ts,String key,float x,float y,float cx,float cy,String pkg,String sid) {
        touch(ts,"bopomofo",key,x,y,cx,cy,pkg,sid);
    }
    synchronized void touch(long ts,String page,String key,float x,float y,float cx,float cy,String pkg,String sid) {
        SQLiteDatabase db=getWritableDatabase(); db.execSQL("INSERT INTO touch_events(ts,page,key,x,y,cx,cy,package_name,sentence_id) VALUES(?,?,?,?,?,?,?,?,?)",new Object[]{ts,page,key,x,y,cx,cy,pkg,sid});
        db.execSQL("DELETE FROM touch_events WHERE id NOT IN (SELECT id FROM touch_events ORDER BY id DESC LIMIT 50000)");
    }
    synchronized void commit(String pkg,String keys,String first,String ai,String text,boolean changed,int segment) {
        SQLiteDatabase db=getWritableDatabase(); long now=System.currentTimeMillis();
        db.execSQL("INSERT INTO commits(ts,package_name,keys,first_candidate,ai_suggestion,final_text,bubble_changed,changed_segment) VALUES(?,?,?,?,?,?,?,?)",new Object[]{now,pkg,keys,first,ai,text,changed?1:0,segment});
        db.execSQL("DELETE FROM commits WHERE id NOT IN (SELECT id FROM commits ORDER BY id DESC LIMIT 50000)");
        if(text!=null&&!text.isEmpty()) db.execSQL("INSERT INTO personal_lexicon(word,pronunciation,use_count,last_used) VALUES(?,?,1,?) ON CONFLICT(word) DO UPDATE SET pronunciation=excluded.pronunciation,use_count=use_count+1,last_used=excluded.last_used",new Object[]{text,keys,now});
        db.execSQL("DELETE FROM personal_lexicon WHERE word NOT IN (SELECT word FROM personal_lexicon ORDER BY use_count DESC,last_used DESC LIMIT 10000)");
    }
    synchronized List<String> rank(List<String> candidates) {
        if(candidates.size()<2)return candidates; Map<String,Integer> scores=new HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT word,use_count FROM personal_lexicon",null)){while(c.moveToNext())scores.put(c.getString(0),c.getInt(1));}
        Map<String,Integer> weight=new HashMap<>();for(String candidate:candidates){int score=0;for(Map.Entry<String,Integer> e:scores.entrySet())if(!candidate.isEmpty()&&e.getKey().contains(candidate))score+=e.getValue();weight.put(candidate,score);}
        ArrayList<String> out=new ArrayList<>(candidates); out.sort((a,b)->Integer.compare(weight.getOrDefault(b,0),weight.getOrDefault(a,0))); return out;
    }
    synchronized void clearLearning() { SQLiteDatabase db=getWritableDatabase(); db.delete("touch_events",null,null);db.delete("commits",null,null);db.delete("personal_lexicon",null,null); }
}
