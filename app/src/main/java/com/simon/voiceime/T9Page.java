package com.simon.voiceime;
import android.view.View;
/** Phone-only implementation is resolved lazily; watch has no Rust dependency. */
interface T9Page extends AutoCloseable {View view();void clear();void reconfigure();void close();}
