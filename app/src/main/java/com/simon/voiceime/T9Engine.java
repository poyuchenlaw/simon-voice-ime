package com.simon.voiceime;

import java.util.List;

interface T9Engine extends AutoCloseable {
    boolean available(); boolean ready(); boolean twoPressMode(); boolean setTwoPressMode(boolean enabled); void finishSegment();
    default String initializationStep(){ return ready()?"完成":"初始化"; }
    default long initializationElapsedMs(){ return 0L; }
    default String initializationError(){ return ""; }
    void key(String key); void backspace(); void clear();
    String snapshot(); String candidatesAt(int offset); boolean chooseAt(int offset,int index);
    boolean chooseCurrent(int index); String selected(); String candidatesFor(String keys); String keySequence(); List<String> segmentKeys();
    @Override void close();
}
