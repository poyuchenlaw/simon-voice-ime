package com.simon.voiceime;

import java.nio.charset.StandardCharsets;

/** Small JNI boundary shared by the phone engine and host-side controller replay. */
final class RimeZhuyinNative implements AutoCloseable {
    // librime process_key consumes X11 keysyms, not Android keycodes or ASCII controls.
    static final int KEYSYM_BACKSPACE = 0xff08;
    static final int KEYSYM_RETURN = 0xff0d;
    static final int KEYSYM_SPACE = 0x0020;
    static {
        System.loadLibrary("rime");
        System.loadLibrary("rime_jni");
    }
    private long handle;
    private final java.io.File userDirectory;
    private long vocabularyVersion = -1L;
    private String commitReading = "";

    RimeZhuyinNative(String shared, String user) {
        userDirectory = new java.io.File(user);
        handle = nativeCreate(shared, user);
        vocabularyVersion = vocabularyVersion();
        if (handle == 0L) throw new IllegalStateException("Rime bopomofo_express session unavailable");
    }
    void key(int code) {
        long version = vocabularyVersion();
        if (version != vocabularyVersion && nativeRefreshVocabulary(handle)) vocabularyVersion = version;
        if (code == KEYSYM_RETURN || code == KEYSYM_SPACE) {
            String reading = decode(nativeVocabularyReading(handle));
            if (RimeVocabularyInstaller.isPhoneticReading(reading)) commitReading = reading;
        }
        nativeProcessKey(handle, code);
    }
    private long vocabularyVersion() {
        java.io.File table = new java.io.File(userDirectory, "custom_phrase.txt");
        return table.lastModified() * 31 + table.length() + RimeVocabularyInstaller.revision();
    }
    void backspace() { key(KEYSYM_BACKSPACE); }
    void enter() { key(KEYSYM_RETURN); }
    void space() { key(KEYSYM_SPACE); }
    void choose(int index) { commitReading = decode(nativeVocabularyReading(handle)); nativeSelect(handle, index); }
    boolean focusCharacter(int index) { return nativeFocusCharacter(handle,index); }
    boolean regroup(int boundary) { return nativeRegroup(handle,boundary); }
    boolean chooseRegroup(int index) { return nativeChooseRegroup(handle,index); }
    String[] regroupLabels() { byte[][] values=nativeRegroupLabels(handle);String[] out=new String[values.length];for(int i=0;i<values.length;i++)out[i]=decode(values[i]);return out; }
    String preview() { return decode(nativePreview(handle)); }
    int[] editRange() { return nativeEditRange(handle); }
    String[] readingSyllables() { byte[][] values=nativeReadingSyllables(handle);String[] out=new String[values.length];for(int i=0;i<values.length;i++)out[i]=decode(values[i]);return out; }
    String reading() { return decode(nativeReading(handle)); }
    void clear() { nativeClear(handle); commitReading = ""; }
    int cursor() { return nativeCursor(handle); }
    void moveCursor(boolean right) { nativeMoveCursor(handle, right); }
    boolean moveCursorToPreviewCharacter(int codePointIndex) {
        return nativeMoveCursorToPreviewCharacter(handle, codePointIndex);
    }
    void moveCursorToEnd() { nativeMoveCursorToEnd(handle); }
    int[] previewSelectionRange() { return nativePreviewSelectionRange(handle); }
    String composing() { return decode(nativeComposing(handle)); }
    String[] candidates() {
        byte[][] values = nativeCandidates(handle);
        if (values == null) return new String[0];
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) out[i] = decode(values[i]);
        return out;
    }
    String takeCommit() {
        String text = decode(nativeTakeCommit(handle));
        if (!text.isEmpty() && !commitReading.isEmpty()) {
            try { RimeVocabularyInstaller.remember(userDirectory, text, commitReading); }
            catch (java.io.IOException error) { System.err.println("Rime committed vocabulary not persisted; retry on next commit"); }
            commitReading = "";
        }
        return text;
    }
    @Override public void close() {
        long old = handle;
        handle = 0L;
        if (old != 0L) nativeDestroy(old);
    }
    private static String decode(byte[] value) {
        return value == null ? "" : new String(value, StandardCharsets.UTF_8);
    }
    private static native boolean nativeFocusCharacter(long h,int index);
    private static native boolean nativeRegroup(long h,int boundary);
    private static native boolean nativeChooseRegroup(long h,int index);
    private static native byte[][] nativeRegroupLabels(long h);
    private static native byte[] nativePreview(long h);
    private static native byte[] nativeReading(long h);
    private static native int[] nativeEditRange(long h);
    private static native byte[][] nativeReadingSyllables(long h);
    private static native long nativeCreate(String shared, String user);
    private static native boolean nativeRefreshVocabulary(long handle);
    private static native byte[] nativeVocabularyReading(long handle);
    private static native void nativeDestroy(long handle);
    private static native void nativeProcessKey(long handle, int key);
    private static native void nativeSelect(long handle, int index);
    private static native void nativeClear(long handle);
    private static native int nativeCursor(long handle);
    private static native void nativeMoveCursor(long handle, boolean right);
    private static native boolean nativeMoveCursorToPreviewCharacter(long handle, int codePointIndex);
    private static native void nativeMoveCursorToEnd(long handle);
    private static native int[] nativePreviewSelectionRange(long handle);
    private static native byte[] nativeComposing(long handle);
    private static native byte[][] nativeCandidates(long handle);
    private static native byte[] nativeTakeCommit(long handle);
}
