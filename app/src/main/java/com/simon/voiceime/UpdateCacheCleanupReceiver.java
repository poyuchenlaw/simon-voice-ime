package com.simon.voiceime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.File;

public final class UpdateCacheCleanupReceiver extends BroadcastReceiver {
    private static final String TAG = "UpdateCacheCleanup";
    private static final String[] INSTALLER_FILES = {
            "update.apk", "update-patched.apk", "update.patch", "patch-uncompress.tmp"
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            cleanupUpdateCache(context.getCacheDir());
        }
    }

    static void cleanupUpdateCache(File cacheDir) {
        for (String name : INSTALLER_FILES) {
            File file = new File(cacheDir, name);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete installer cache file: " + name);
            }
        }
    }
}
