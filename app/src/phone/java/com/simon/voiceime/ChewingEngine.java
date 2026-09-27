package com.simon.voiceime;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.nio.charset.StandardCharsets;

/** Java adapter over the unmodified LGPL libchewing shared library. */
final class ChewingEngine implements ZhuyinInputController.Engine, AutoCloseable {
    private static final String TAG = "ChewingEngine";
    private static final boolean LIBRARY_READY;
    static {
        boolean loaded;
        try { System.loadLibrary("chewing"); System.loadLibrary("chewing_jni"); loaded = true; }
        catch (Throwable error) { loaded = false; Log.e(TAG, "libchewing native load failed", error); }
        LIBRARY_READY = loaded;
    }

    private long handle;

    ChewingEngine(Context context) {
        try {
            File systemDir = new File(context.getFilesDir(), "libchewing");
            copyDictionaryAssets(context, systemDir);
            File userDict = new File(context.getFilesDir(), "chewing.dat");
            handle = LIBRARY_READY ? nativeCreate(systemDir.getAbsolutePath(), userDict.getAbsolutePath()) : 0L;
            if (handle == 0L) Log.e(TAG, "libchewing context initialization failed");
        } catch (Throwable error) {
            Log.e(TAG, "libchewing initialization failed; keeping keyboard usable", error);
            handle = 0L;
        }
    }

    private static void copyDictionaryAssets(Context context, File target) throws Exception {
        if (!target.isDirectory() && !target.mkdirs()) throw new IllegalStateException("Cannot create dictionary directory");
        String[] names = context.getAssets().list("libchewing");
        if (names == null) return;
        for (String name : names) {
            File out = new File(target, name);
            if (out.isFile() && out.length() > 0) continue;
            try (InputStream in = context.getAssets().open("libchewing/" + name);
                 FileOutputStream stream = new FileOutputStream(out)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) stream.write(buffer, 0, count);
            }
        }
    }

    @Override public void key(String zhuyin) { safe(() -> nativeKey(handle, ZhuyinKeyMap.physicalKey(zhuyin))); }
    @Override public void backspace() { safe(() -> nativeBackspace(handle)); }
    @Override public void space() { safe(() -> nativeSpace(handle)); }
    @Override public void enter() { safe(() -> nativeEnter(handle)); }
    @Override public void choose(int index) { safe(() -> nativeChoose(handle, index)); }
    @Override public void moveCursor(String direction) {
        if ("left".equals(direction)) safe(() -> nativeMoveCursor(handle, false));
        else if ("right".equals(direction)) safe(() -> nativeMoveCursor(handle, true));
    }
    @Override public int cursorPosition() { return safeInt(() -> nativeCursor(handle)); }
    @Override public String composingText() { return decode(safeBytes(() -> nativeComposing(handle))); }
    @Override public List<String> candidates() {
        if (handle == 0L) return Collections.emptyList();
        try {
            byte[][] values = nativeCandidates(handle);
            List<String> result = new ArrayList<>();
            if (values != null) for (byte[] value : values) {
                String decoded = decode(value);
                if (!decoded.isEmpty()) result.add(decoded);
            }
            return result;
        } catch (Throwable error) { Log.w(TAG, "Candidate lookup failed", error); return Collections.emptyList(); }
    }
    @Override public String takeCommit() { return decode(safeBytes(() -> nativeTakeCommit(handle))); }
    @Override public void clear() { safe(() -> nativeClear(handle)); }
    @Override public void close() { long old = handle; handle = 0L; safe(() -> nativeDestroy(old)); }

    private interface NativeAction { void run(); }
    private interface NativeBytes { byte[] get(); }
    private interface NativeInt { int get(); }
    private void safe(NativeAction action) {
        if (handle == 0L) return;
        try { action.run(); } catch (Throwable error) { Log.w(TAG, "Input event failed", error); }
    }
    private static String decode(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
    private byte[] safeBytes(NativeBytes call) {
        if (handle == 0L) return new byte[0];
        try { byte[] value = call.get(); return value == null ? new byte[0] : value; }
        catch (Throwable error) { Log.w(TAG, "Native text lookup failed", error); return new byte[0]; }
    }
    private int safeInt(NativeInt call) {
        if (handle == 0L) return 0;
        try { return Math.max(0, call.get()); }
        catch (Throwable error) { Log.w(TAG, "Native cursor lookup failed", error); return 0; }
    }

    private static native long nativeCreate(String systemPath, String userPath);
    private static native void nativeDestroy(long handle);
    private static native void nativeKey(long handle, int key);
    private static native void nativeBackspace(long handle);
    private static native void nativeSpace(long handle);
    private static native void nativeEnter(long handle);
    private static native void nativeChoose(long handle, int index);
    private static native void nativeMoveCursor(long handle, boolean right);
    private static native int nativeCursor(long handle);
    private static native byte[] nativeComposing(long handle);
    private static native byte[][] nativeCandidates(long handle);
    private static native byte[] nativeTakeCommit(long handle);
    private static native void nativeClear(long handle);
}
