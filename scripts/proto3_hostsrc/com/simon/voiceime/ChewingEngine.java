package com.simon.voiceime;

/** Host-only declarations matching the phone JNI ABI. */
final class ChewingEngine {
    static { System.loadLibrary("chewing"); System.loadLibrary("chewing_jni"); }
    static native long nativeCreate(String systemPath, String userPath);
    static native void nativeDestroy(long handle);
    static native void nativeKey(long handle, int key);
    static native void nativeBackspace(long handle);
    static native void nativeSpace(long handle);
    static native void nativeEnter(long handle);
    static native void nativeChoose(long handle, int index);
    static native void nativeMoveCursor(long handle, boolean right);
    static native int nativeCursor(long handle);
    static native byte[] nativeComposing(long handle);
    static native byte[][] nativeCandidates(long handle);
    static native byte[] nativeTakeCommit(long handle);
    static native void nativeClear(long handle);
}
