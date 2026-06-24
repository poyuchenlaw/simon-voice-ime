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

    /**
     * v6.9.2 KILL-SWITCH: the on-device LLM punctuator is DISABLED.
     *
     * <p>Root cause (confirmed on the physical Fold6): once the ~1GB LLM model finished downloading,
     * {@link LlmPunctuator#nativeComplete} (llama.cpp autoregressive decode) could HANG forever on
     * this arm64 device, with no timeout → {@link #correct} blocked forever → APPEND committed
     * NOTHING (status froze at "辨識中"). We keep the {@link LlmPunctuator} class in the tree (it may
     * be re-enabled later behind a proper watchdog), but we do NOT invoke it: {@code init()} skips
     * {@code llm.init()} so {@code llm.isReady()} stays false and the chain never calls into the
     * native LLM. Punctuation now comes ONLY from the CT-Transformer tagger ({@link PunctuationHelper}),
     * a bounded ONNX forward pass that cannot hang like autoregressive decode.
     */
    private static final boolean USE_LLM_PUNCT = false;

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
        // v6.9.2: Smart primary on-device LLM is DISABLED (USE_LLM_PUNCT=false). It is the hang
        // source on the Fold6 (nativeComplete had no timeout). We do NOT call llm.init(), so
        // llm.isReady() stays false and the punctuation chain never invokes the native LLM.
        if (USE_LLM_PUNCT) {
            try {
                llm.init();
            } catch (Throwable t) {
                Log.w(TAG, "LLM 標點初始化失敗（退 CT-Transformer 標點）", t);
            }
        } else {
            Log.i(TAG, "LLM 標點已停用（USE_LLM_PUNCT=false）；標點僅用 CT-Transformer tagger");
        }
    }

    private static InputStream open(AssetManager am, String path) throws IOException {
        return am.open(path);
    }

    /** True once steps 1-4 (deterministic) are available. Punctuation may still be downloading. */
    public boolean isCorrectorReady() {
        return correctorReady && corrector != null;
    }

    /** True once any punctuation source is available. v6.9.2: LLM disabled, so this is the tagger. */
    public boolean isPunctuationReady() {
        return (USE_LLM_PUNCT && llm.isReady()) || punctuation.isReady();
    }

    /** v6.9.2: always false — the smart (LLM) punctuator is disabled (hang source on Fold6). */
    public boolean isSmartPunctuationReady() {
        return USE_LLM_PUNCT && llm.isReady();
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
        // v6.9.2: the LLM primary is hard-disabled (USE_LLM_PUNCT=false) — it never enters the chain,
        // so no path can invoke the hang-prone nativeComplete. Punctuation = CT-Transformer only.
        OnDeviceCorrector.Punctuator primary  = (USE_LLM_PUNCT && llm.isReady()) ? llm : null;
        OnDeviceCorrector.Punctuator fallback = punctuation.isReady() ? punctuation : null;
        return c.correct(senseVoiceText, precedingContext, primary, fallback);
    }

    public void release() {
        try { llm.release(); } catch (Throwable ignored) {}
        try { punctuation.release(); } catch (Throwable ignored) {}
    }
}
