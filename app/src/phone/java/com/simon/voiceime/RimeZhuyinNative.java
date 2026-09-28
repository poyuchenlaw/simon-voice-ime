package com.simon.voiceime;

import java.nio.charset.StandardCharsets;

/** Small JNI boundary shared by the phone engine and host-side controller replay. */
final class RimeZhuyinNative implements AutoCloseable {
    static {
        System.loadLibrary("rime");
        System.loadLibrary("rime_jni");
    }
    private long handle;

    RimeZhuyinNative(String shared, String user) {
        handle = nativeCreate(shared, user);
        if (handle == 0L) throw new IllegalStateException("Rime bopomofo_express session unavailable");
    }
    void key(int code) { nativeProcessKey(handle, code); }
    void choose(int index) { nativeSelect(handle, index); }
    void clear() { nativeClear(handle); }
    int cursor() { return nativeCursor(handle); }
    void moveCursor(boolean right) { nativeMoveCursor(handle, right); }
    String composing() { return decode(nativeComposing(handle)); }
    String[] candidates() {
        byte[][] values = nativeCandidates(handle);
        if (values == null) return new String[0];
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = decode(values[i]);
        return out;
    }
    String takeCommit() { return decode(nativeTakeCommit(handle)); }
    @Override public void close() {
        long old = handle;
        handle = 0L;
        if (old != 0L) nativeDestroy(old);
    }
    private static String decode(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
    private static native long nativeCreate(String shared, String user);
    private static native void nativeDestroy(long handle);
    private static native void nativeProcessKey(long handle, int key);
    private static native void nativeSelect(long handle, int index);
    private static native void nativeClear(long handle);
    private static native int nativeCursor(long handle);
    private static native void nativeMoveCursor(long handle, boolean right);
    private static native byte[] nativeComposing(long handle);
    private static native byte[][] nativeCandidates(long handle);
    private static native byte[] nativeTakeCommit(long handle);
}
