package com.simon.voiceime;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 剪貼簿歷史管理 — 保存最近 50 則剪貼內容
 */
public class ClipboardHelper {

    /** v6.20: 剪貼簿新增時回呼，供詞彙自動登錄 */
    public interface ClipVocabListener {
        void onNewClip(String text, String label);
    }

    private ClipVocabListener vocabListener;
    private boolean passwordField,closed;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener=()->captureCurrent("listener");

    public void setPasswordField(boolean password) { passwordField = password; }

    public void setVocabListener(ClipVocabListener listener) {
        this.vocabListener = listener;
    }

    private static final String TAG = "ClipboardHelper";
    private static final String PREFS_NAME = "simon_ime_clipboard";
    private static final String KEY_HISTORY = "history";
    private static final String BACKUP_FILENAME = "clipboard_backup.json";
    private static final int MAX_ITEMS = 50;

    private final java.util.concurrent.ExecutorService backups=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ClipboardBackup");t.setDaemon(true);return t;});
    private final Context context;
    private final List<String> history;

    public ClipboardHelper(Context context) {
        this.context = context;
        this.history = loadHistory();
        if (history.isEmpty()) {
            List<String> restored = loadFromFileBackup();
            if (!restored.isEmpty()) {
                Log.i(TAG, "Restored " + restored.size() + " clipboard items from file backup");
                history.addAll(restored);
                saveHistory();
            }
        }
        listenClipboard();
    }

    private void listenClipboard() {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.addPrimaryClipChangedListener(clipListener);
    }

    public void captureCurrent(String source) {
        if (passwordField) return;
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm == null ? null : cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            ClipDescription description = clip.getDescription();
            // The literal also works before API 33 introduced the named constant.
            if (description.getExtras() != null && description.getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false)) return;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.toString().trim().isEmpty()) return;
            synchronized (this) {
                if (!history.isEmpty() && history.get(0).equals(text.toString())) return;
                addToHistory(text.toString());
            }
            Log.i(TAG, "capture source=" + source + " items=" + getHistory().size());
            if (vocabListener != null) {
                CharSequence label = description.getLabel();
                vocabListener.onNewClip(text.toString(), label == null ? "" : label.toString());
            }
        } catch (SecurityException unavailable) {
            Log.w(TAG, "Clipboard capture unavailable: " + source, unavailable);
        }
    }

    public synchronized void addToHistory(String text) {
        if (closed || passwordField || text == null || text.trim().isEmpty()) return;
        // Remove if already exists (move to top)
        history.remove(text);
        history.add(0, text);
        // Trim to max
        while (history.size() > MAX_ITEMS) {
            history.remove(history.size() - 1);
        }
        saveHistory();
    }

    public synchronized List<String> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized void remove(int index) {
        if (index >= 0 && index < history.size()) {
            history.remove(index);
            saveHistory();
        }
    }

    public synchronized void clear() {
        history.clear();
        saveHistory();
    }

    public synchronized void removeByContent(String text) {
        history.remove(text);
        saveHistory();
    }

    private List<String> loadHistory() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (JSONException e) {
            // ignore
        }
        return list;
    }

    private void saveHistory() {
        if(closed)return;
        JSONArray arr = new JSONArray();
        for (String s : history) {
            arr.put(s);
        }
        String json = arr.toString();
        backups.execute(()->{
            if(!context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY,json).commit())Log.w(TAG,"Clipboard preference backup rejected",new IOException("commit rejected"));
            saveToFileBackup(json);
        });
    }
    public void close(){
        synchronized(this){closed=true;ClipboardManager cm=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.removePrimaryClipChangedListener(clipListener);backups.shutdown();}try{if(!backups.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))Log.w(TAG,"Clipboard final backup still pending",new IOException("shutdown timeout"));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();Log.w(TAG,"Clipboard shutdown interrupted",interrupted);}
    }
    private void saveToFileBackup(String json){
        writeBackup(new File(context.getFilesDir(),BACKUP_FILENAME),json);
        File external=context.getExternalFilesDir(null);if(external!=null)writeBackup(new File(new File(external,"backup"),BACKUP_FILENAME),json);
    }
    private void writeBackup(File file,String json){
        try{
            File dir=file.getParentFile();if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Backup directory unavailable");
            File temp=new File(dir,file.getName()+".tmp");
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(json.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            if(!temp.renameTo(file))throw new IOException("Atomic clipboard replacement rejected");
        }catch(IOException failure){Log.w(TAG,"Failed to save clipboard backup",failure);}
    }

    private List<String> loadFromFileBackup() {
        // Try internal first
        File backup = new File(context.getFilesDir(), BACKUP_FILENAME);
        if (backup.exists()) {
            List<String> result = loadBackupFile(backup);
            if (!result.isEmpty()) return result;
        }
        // Try external backup
        File extBackup = new File(new File(context.getExternalFilesDir(null), "backup"), BACKUP_FILENAME);
        if (extBackup.exists()) {
            return loadBackupFile(extBackup);
        }
        return new ArrayList<>();
    }

    private List<String> loadBackupFile(File backup) {
        try (FileInputStream fis = new FileInputStream(backup)) {
            byte[] data = new byte[(int) backup.length()];
            fis.read(data);
            String json = new String(data, StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
            return list;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load clipboard backup: " + backup.getPath(), e);
            return new ArrayList<>();
        }
    }
}
