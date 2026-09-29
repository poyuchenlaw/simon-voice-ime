package com.simon.voiceime;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Bounded on-device touch-offset corpus for improving the 41-key Zhuyin page. */
final class TouchLearningStore extends SQLiteOpenHelper {
    TouchLearningStore(Context context) { super(context, "zhuyin_touch_learning.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE touch_events(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER,page TEXT,key TEXT,x REAL,y REAL,cx REAL,cy REAL,package_name TEXT,session_id TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    synchronized void touch(long timestamp, String page, String key, float x, float y,
                            float centerX, float centerY, String packageName, String sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT INTO touch_events(ts,page,key,x,y,cx,cy,package_name,session_id) VALUES(?,?,?,?,?,?,?,?,?)",
                new Object[]{timestamp, page, key, x, y, centerX, centerY, packageName, sessionId});
        db.execSQL("DELETE FROM touch_events WHERE id NOT IN (SELECT id FROM touch_events ORDER BY id DESC LIMIT 50000)");
    }
}
