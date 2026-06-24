package com.simon.voiceime.correct;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/**
 * Android wiring for on-device APPEND correction.
 *
 * <p>Loads {@link OnDeviceCorrector} from bundled assets (always available — no download), and
 * owns the {@link PunctuationHelper} (downloads its model on first run). The deterministic
 * corrector is ready immediately; punctuation joins once its model finishes downloading.
 *
 * <p>Usage from {@link com.simon.voiceime.SimonIMEService}:
 * <pre>
 *   engine = new OnDeviceCorrectionEngine(this);
 *   new Thread(engine::init).start();            // background init + model download
 *   ...
 *   if (engine.isCorrectorReady()) {
 *       String out = engine.correct(spokenText); // steps 1-6, punctuation iff ready
 *       commitAppendRaw(out, "");
 *   } else {
 *       sendTextProcessOrCommitRaw(spokenText, Mode.APPEND); // server fallback
 *   }
 * </pre>
 */
public final class OnDeviceCorrectionEngine {

    private static final String TAG = "OnDeviceCorrect";
    private static final String DIR = "correction";

    private final Context context;
    private final PunctuationHelper punctuation;
    private volatile OnDeviceCorrector corrector;
    private volatile boolean correctorReady = false;

    public OnDeviceCorrectionEngine(Context context) {
        this.context = context.getApplicationContext();
        this.punctuation = new PunctuationHelper(this.context);
    }

    /** Background init: load asset dicts (fast) then download/init punctuation model (slow). */
    public void init() {
        AssetManager am = context.getAssets();
        try (InputStream para = open(am, DIR + "/paraformer_legal.txt");
             InputStream stp = open(am, DIR + "/opencc/STPhrases.txt");
             InputStream stc = open(am, DIR + "/opencc/STCharacters.txt");
             InputStream twv = open(am, DIR + "/opencc/TWVariants.txt");
             InputStream ovr = open(am, DIR + "/legal_overrides.txt");
             InputStream mis = open(am, DIR + "/legal_dict_mishear.txt");
             InputStream prot = open(am, DIR + "/protected_terms.txt")) {
            corrector = OnDeviceCorrector.load(para, stp, stc, twv, ovr, mis, prot);
            correctorReady = true;
            Log.i(TAG, "端上確定性校正就緒（標點待模型下載）");
        } catch (Throwable t) {
            Log.e(TAG, "端上校正載入失敗，APPEND 退回伺服器", t);
            correctorReady = false;
            return;
        }
        // Punctuation model (first-run download). Until ready, correct() runs steps 1-4 only.
        try {
            punctuation.init();
        } catch (Throwable t) {
            Log.w(TAG, "標點初始化失敗（確定性校正仍可用）", t);
        }
    }

    private static InputStream open(AssetManager am, String path) throws IOException {
        return am.open(path);
    }

    /** True once steps 1-4 (deterministic) are available. Punctuation may still be downloading. */
    public boolean isCorrectorReady() {
        return correctorReady && corrector != null;
    }

    public boolean isPunctuationReady() {
        return punctuation.isReady();
    }

    /**
     * Run the on-device pipeline. Steps 1-4 always; step 5 punctuation iff the model is ready
     * (behind the 改字 guard). Caller MUST check {@link #isCorrectorReady()} first; if false,
     * use the server fallback.
     */
    public String correct(String senseVoiceText) {
        OnDeviceCorrector c = corrector;
        if (c == null) return senseVoiceText;
        OnDeviceCorrector.Punctuator p = punctuation.isReady() ? punctuation : null;
        return c.correct(senseVoiceText, p);
    }

    public void release() {
        punctuation.release();
    }
}
