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
    String sentenceKeys(){return decode(nativeSentenceKeys(handle)).replace("ˉ"," ");}
    boolean prepareSentence(String physicalKeys,String text){return nativePrepareSentence(handle,physicalKeys,text);}
    private static native byte[] nativeSentenceKeys(long h);
    private static native boolean nativePrepareSentence(long h,String keys,String text);
    boolean focusAtKey(int at){return nativeFocusAtKey(handle,at,false);}
    boolean focusAfterKeyEdit(int at){return nativeFocusAtKey(handle,at,true);}
    private static native boolean nativeFocusAtKey(long h,int at,boolean edited);
    boolean keyCaret(int at){return nativeKeyCaret(handle,at);}
    int keyPreviewCaret(){return nativeKeyPreviewCaret(handle);}
    private static native boolean nativeKeyCaret(long h,int at);
    private static native int nativeKeyPreviewCaret(long h);
    void backspace() { key(KEYSYM_BACKSPACE); }
    void enter() { key(KEYSYM_RETURN); }
    void space() { key(KEYSYM_SPACE); }
    void chooseAndCommit(int index) { nativeSelectCommit(handle, index); commitReading = decode(nativeCommitReading(handle)); }
    private static native byte[] nativeCommitReading(long h);
    void copyTouches(RimeZhuyinNative source,int start,int end){nativeCopyTouches(source.handle,handle,start,end);}
    private static native void nativeCopyTouches(long source,long target,int start,int end);
    private static native void nativeSelectCommit(long h, int index);
    void choose(int index) { commitReading = decode(nativeVocabularyReading(handle)); nativeSelect(handle, index); }
    boolean focusCharacter(int index) { return nativeFocusCharacter(handle,index); }
    boolean focusCharacterOnly(int index) { return nativeFocusCharacterOnly(handle,index); }
    private static native boolean nativeFocusCharacterOnly(long h,int target);
    void recordTouch(int[] keys,double[] probabilities,boolean[] adjacent){nativeRecordTouch(handle,keys,probabilities,adjacent);}
    String[] regroupReadings(){byte[][] values=nativeRegroupReadings(handle);String[] out=new String[values.length];for(int i=0;i<values.length;i++)out[i]=decode(values[i]);return out;}
    boolean regroup(int boundary) { return nativeRegroup(handle,boundary); }
    boolean chooseRegroup(int index) { return nativeChooseRegroup(handle,index); }
    int[][] optionRanges(){return nativeOptionRanges(handle);}
    private static native int[][] nativeOptionRanges(long h);
    String[] optionGroups(){return nativeOptionGroups(handle);}
    private static native String[] nativeOptionGroups(long h);
    void restore(java.util.List<String> readings,String text){
        StringBuilder raw=new StringBuilder();int[] stops=new int[readings.size()+1];
        for(int i=0;i<readings.size();i++){String reading=readings.get(i);for(int j=0;j<reading.length();j++){String glyphs="ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙ˉ";String physical="1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347 ";int g=glyphs.indexOf(reading.charAt(j));int k=reading.charAt(j)==' '?32:g<0?-1:physical.charAt(g);if(k<0)throw new IllegalArgumentException("Invalid native reading");raw.append((char)k);}stops[i+1]=raw.length();}
        nativeRestore(handle,raw.toString(),text,stops);
    }
    private static native void nativeRestore(long h,String keys,String text,int[] stops);
    String[] optionKinds(){return nativeOptionKinds(handle);}
    private static native String[] nativeOptionKinds(long h);
    String[] localRepair(){return nativeLocalRepair(handle);}
    private static native String[] nativeLocalRepair(long h);
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
    int rowWordLimit(){return nativeRowWordLimit(handle);}
    private static native int nativeRowWordLimit(long handle);
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
            try { RimeVocabularyInstaller.rememberCommit(userDirectory, text, commitReading); }
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
    private static native void nativeRecordTouch(long h,int[] keys,double[] probabilities,boolean[] adjacent);
    private static native byte[][] nativeRegroupReadings(long h);
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
