package com.simon.ime.host690;
import android.os.Bundle;
/** Keep the existing sandbox's layout and editor; only expose test-only Layout readback. */
public class App690HostActivity extends com.simon.voiceime.DualHand689SandboxActivity {
 @Override public void onCreate(Bundle state){super.onCreate(state);TruthProvider.activity=this;}
 @Override public void onDestroy(){if(TruthProvider.activity==this)TruthProvider.activity=null;super.onDestroy();}
}
