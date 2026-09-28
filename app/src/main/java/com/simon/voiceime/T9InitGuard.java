package com.simon.voiceime;

import android.content.SharedPreferences;

/** Persistent crash marker for the optional native T9 engine. */
final class T9InitGuard {
    interface Preferences {
        boolean getBoolean(String key, boolean fallback);
        boolean commitBoolean(String key, boolean value);
    }

    private static final String PENDING = "t9_init_pending";
    private static final String DISABLED = "t9_disabled_after_crash";

    private T9InitGuard() {}

    static Preferences adapt(SharedPreferences prefs) {
        return new Preferences() {
            @Override public boolean getBoolean(String key, boolean fallback) {
                return prefs.getBoolean(key, fallback);
            }
            @Override public boolean commitBoolean(String key, boolean value) {
                return prefs.edit().putBoolean(key, value).commit();
            }
        };
    }

    static boolean onImeStartup(Preferences prefs) {
        recoverAfterProcessRestart(prefs);
        prefs.commitBoolean("t9_last_used", false);
        return false;
    }

    static boolean recoverAfterProcessRestart(Preferences prefs) {
        if (!prefs.getBoolean(PENDING, false)) return false;
        if (!prefs.commitBoolean(DISABLED, true)) return false;
        prefs.commitBoolean(PENDING, false);
        return true;
    }

    static boolean isDisabled(Preferences prefs) {
        return prefs.getBoolean(DISABLED, false);
    }

    static boolean markInitializationPending(Preferences prefs) {
        return prefs.commitBoolean(PENDING, true);
    }

    static void markInitializationFinished(Preferences prefs) {
        prefs.commitBoolean(PENDING, false);
    }

    static boolean reenable(Preferences prefs) {
        boolean disabledCleared = prefs.commitBoolean(DISABLED, false);
        boolean pendingCleared = prefs.commitBoolean(PENDING, false);
        return disabledCleared && pendingCleared;
    }
}
