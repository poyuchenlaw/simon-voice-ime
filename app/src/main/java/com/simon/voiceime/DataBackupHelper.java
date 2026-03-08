package com.simon.voiceime;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 資料持久化助手 v3.3
 *
 * 統一管理備份/還原到外部檔案目錄 (getExternalFilesDir)。
 * 此路徑在 APK 更新時保留，解除安裝時才刪除。
 *
 * 備份路徑：
 * /storage/emulated/0/Android/data/com.simon.voiceime/files/backup/
 * ├── commands_backup.json
 * ├── clipboard_backup.json
 * ├── corrections_backup.json
 * └── english_mapper_backup.json
 *
 * 搭配 AndroidManifest 的 allowBackup + fullBackupContent，
 * 可同時使用 Android Auto Backup 在雲端備份。
 */
public class DataBackupHelper {

    private static final String TAG = "DataBackup";
    private static final String BACKUP_DIR = "backup";

    private final Context context;
    private final File backupDir;

    public DataBackupHelper(Context context) {
        this.context = context;
        this.backupDir = new File(context.getExternalFilesDir(null), BACKUP_DIR);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
    }

    /**
     * 儲存資料到外部備份目錄。
     */
    public boolean saveBackup(String filename, String data) {
        try {
            File file = new File(backupDir, filename);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data.getBytes(StandardCharsets.UTF_8));
            }
            Log.d(TAG, "Backup saved: " + filename + " (" + data.length() + " chars)");
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed to save backup: " + filename, e);
            return false;
        }
    }

    /**
     * 從外部備份目錄讀取資料。
     * @return 資料內容，如果不存在或讀取失敗則回傳 null。
     */
    public String loadBackup(String filename) {
        File file = new File(backupDir, filename);
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            String content = new String(data, StandardCharsets.UTF_8);
            Log.d(TAG, "Backup loaded: " + filename + " (" + content.length() + " chars)");
            return content;
        } catch (Exception e) {
            Log.w(TAG, "Failed to load backup: " + filename, e);
            return null;
        }
    }

    /**
     * 檢查外部備份是否存在。
     */
    public boolean backupExists(String filename) {
        return new File(backupDir, filename).exists();
    }

    /**
     * 取得備份目錄路徑。
     */
    public File getBackupDir() {
        return backupDir;
    }

    /**
     * 列出所有備份檔案。
     */
    public File[] listBackups() {
        return backupDir.listFiles();
    }

    /**
     * 刪除特定備份。
     */
    public boolean deleteBackup(String filename) {
        File file = new File(backupDir, filename);
        if (file.exists()) {
            boolean deleted = file.delete();
            Log.d(TAG, "Backup deleted: " + filename + " (" + deleted + ")");
            return deleted;
        }
        return false;
    }

    /**
     * 將 Commands, Clipboard, Corrections 統一備份。
     * 由 SimonIMEService 在每次資料變更時呼叫。
     */
    public static void backupAll(Context context, CommandsHelper commands, ClipboardHelper clipboard) {
        DataBackupHelper helper = new DataBackupHelper(context);

        // Commands
        if (commands != null) {
            String json = commands.exportToJson();
            helper.saveBackup("commands_backup.json", json);
        }

        // Clipboard - already backed up internally by ClipboardHelper
        // Just ensure external copy exists too
    }
}
