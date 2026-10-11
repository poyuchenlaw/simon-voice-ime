package com.simon.voiceime;
import android.view.View;
/** Phone implementation is lazy so watch and 41-key input keep their existing path. */
interface DualHandPage extends AutoCloseable {
    View view();
    void clear();
    boolean finish();
    void reconfigure();
    void selection(int start,int end,int composingStart,int composingEnd);
    void close();
}
