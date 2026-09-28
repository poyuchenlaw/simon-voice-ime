package com.simon.voiceime;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class T9InitGuardTest {
    @Test public void serviceStartupDoesNotEagerlyCreateEngine() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("t9_last_used", true);

        assertFalse(T9InitGuard.onImeStartup(prefs));
        assertFalse(prefs.getBoolean("t9_last_used", true));
    }

    @Test public void stalePendingInitializationDisablesT9AndClearsPending() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("t9_init_pending", true);

        assertTrue(T9InitGuard.recoverAfterProcessRestart(prefs));
        assertTrue(prefs.getBoolean("t9_disabled_after_crash", false));
        assertFalse(prefs.getBoolean("t9_init_pending", false));
    }

    @Test public void reenableClearsCrashDisableAndPendingFlags() {
        MemoryPreferences prefs = new MemoryPreferences();
        prefs.values.put("t9_disabled_after_crash", true);
        prefs.values.put("t9_init_pending", true);

        assertTrue(T9InitGuard.reenable(prefs));

        assertFalse(prefs.getBoolean("t9_disabled_after_crash", false));
        assertFalse(prefs.getBoolean("t9_init_pending", false));
    }

    private static final class MemoryPreferences implements T9InitGuard.Preferences {
        final Map<String, Boolean> values = new HashMap<>();
        @Override public boolean getBoolean(String key, boolean fallback) {
            Boolean value = values.get(key);
            return value == null ? fallback : value;
        }
        @Override public boolean commitBoolean(String key, boolean value) {
            values.put(key, value);
            return true;
        }
    }
}
