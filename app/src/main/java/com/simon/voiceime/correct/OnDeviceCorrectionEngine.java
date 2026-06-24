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
    private final LlmPunctuator llm;             // primary (smart, context-aware)
    private final PunctuationHelper punctuation;  // fallback (CT-Transformer tagger)
    private volatile OnDeviceCorrector corrector;
    private volatile boolean correctorReady = false;

    public OnDeviceCorrectionEngine(Context context) {
        this.context = context.getApplicationContext();
        this.llm = new LlmPunctuator(this.context);
        this.punctuation = new PunctuationHelper(this.context);
    }

    /**
     * Background init: load asset dicts (fast), then init BOTH punctuators (slow, first-run
     * downloads). The CT-Transformer tagger (~72MB) is the fast floor; the LLM (~1GB) is the smart
     * primary. Until each is ready, the punctuation chain simply skips it.
     */
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
        // Fast floor first: CT-Transformer tagger (~72MB). Smart punctuation joins once the
        // LLM model has downloaded; until then the chain falls through to the tagger.
        try {
            punctuation.init();
        } catch (Throwable t) {
            Log.w(TAG, "CT-Transformer 標點初始化失敗（確定性校正仍可用）", t);
        }
        // Smart primary: on-device LLM (~1GB, one-time download). Errors degrade to the tagger.
        try {
            llm.init();
        } catch (Throwable t) {
            Log.w(TAG, "LLM 標點初始化失敗（退 CT-Transformer 標點）", t);
        }
    }

    private static InputStream open(AssetManager am, String path) throws IOException {
        return am.open(path);
    }

    /** True once steps 1-4 (deterministic) are available. Punctuation may still be downloading. */
    public boolean isCorrectorReady() {
        return correctorReady && corrector != null;
    }

    /** True once any punctuation source (LLM primary or CT-Transformer fallback) is available. */
    public boolean isPunctuationReady() {
        return llm.isReady() || punctuation.isReady();
    }

    /** True once the smart (LLM) punctuator is loaded. */
    public boolean isSmartPunctuationReady() {
        return llm.isReady();
    }

    public String correct(String senseVoiceText) {
        return correct(senseVoiceText, null);
    }

    /**
     * Run the on-device pipeline. Steps 1-4 (deterministic) always; step 5 punctuation via the
     * CAGED chain LLM(primary) -> CT-Transformer(fallback) -> none, all behind the 改字 guard.
     * Caller MUST check {@link #isCorrectorReady()} first; if false, use the server fallback.
     *
     * @param precedingContext the user's already-typed text before the cursor (前文; from
     *                         {@code getTextBeforeCursor}); passed to the LLM punctuator so junction
     *                         punctuation is correct. May be {@code null}.
     */
    public String correct(String senseVoiceText, String precedingContext) {
        OnDeviceCorrector c = corrector;
        if (c == null) return senseVoiceText;
        // Chain order = primary first. Only include ready punctuators; the chain skips nulls.
        OnDeviceCorrector.Punctuator primary  = llm.isReady() ? llm : null;
        OnDeviceCorrector.Punctuator fallback = punctuation.isReady() ? punctuation : null;
        return c.correct(senseVoiceText, precedingContext, primary, fallback);
    }

    public void release() {
        try { llm.release(); } catch (Throwable ignored) {}
        try { punctuation.release(); } catch (Throwable ignored) {}
    }
}
