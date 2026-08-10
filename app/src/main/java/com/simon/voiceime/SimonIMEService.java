package com.simon.voiceime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.simon.voiceime.correct.OnDeviceCorrectionEngine;
import com.simon.voiceime.correct.TimeoutWall;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Simon Voice IME v4.2.1
 *
 * 功能：
 * - 語音輸入（追加/替換/拼字/翻譯四模式）
 * - 追加模式串流上傳：VAD 分段 → SenseVoice 辨識 → stream-chunk → stream-finalize
 * - 英文詞彙本地映射（SenseVoice 中文諧音 → 英文）
 * - 空格、退格、Enter
 * - 剪貼簿歷史（50 則）
 * - 常用指令（分組可自訂）
 * - 資料持久化（外部備份 + Auto Backup）
 * - 跳轉其他輸入法
 * - 設定
 */
public class SimonIMEService extends InputMethodService {

    private static final String TAG = "SimonIME";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    enum Mode { APPEND, REPLACE, SPELL, TRANSLATE }
    enum KeyboardMode { VOICE, ENGLISH, NUMBERS }

    private static final String PREF_MODE_KEY = "last_mode";
    private Mode currentMode = Mode.APPEND;
    private KeyboardMode currentKeyboardMode = KeyboardMode.VOICE;
    private boolean isRecording = false;
    private AudioRecord audioRecord;
    private Thread recordingThread;
    // v5.6: 鎖屏期間保 CPU，避免 AudioRecord underrun + WebSocket 心跳逾時
    private PowerManager.WakeLock recordingWakeLock;
    private ByteArrayOutputStream pcmBuffer;
    private OkHttpClient httpClient;
    private Handler mainHandler;

    private static final long CORRECTION_CAPTURE_WINDOW_MS = 15_000;
    private static final long CORRECTION_CAPTURE_DEBOUNCE_MS = 1_500;
    private static final long CONNECTION_WARM_UP_DEBOUNCE_MS = 10_000;
    private static final long CONNECTION_WARM_UP_INTERVAL_MS = 25_000;
    private volatile boolean mIgnoreNextUpdateSelection = false;
    private volatile long lastWarmUpMs = 0L;
    private String mLastVoiceCommittedText = null;
    private long mLastVoiceCommittedTs = 0L;
    private int mLastVoiceCommitStart = -1;
    private int mLastVoiceCommitEnd = -1;
    private int mLastVoiceCommitFieldLength = -1;
    private boolean mPendingCorrectionCapture = false;
    private long mPendingCorrectionCommitTs = 0L;
    private long mCapturePostedCommitTs = 0L;
    private final Runnable mPendingCorrectionCaptureRunnable = this::flushPendingCorrectionCapture;
    private final Runnable connectionWarmUpRunnable = new Runnable() {
        @Override
        public void run() {
            warmUpConnection();
            if (mainHandler != null) {
                mainHandler.postDelayed(this, CONNECTION_WARM_UP_INTERVAL_MS);
            }
        }
    };

    // Helpers
    private ClipboardHelper clipboardHelper;
    private CommandsHelper commandsHelper;
    private LocalSTTHelper localSTT;
    private volatile boolean localSTTReady = false;
    private EnglishMapper englishMapper;
    private OnDeviceCorrectionEngine onDeviceCorrection;
    private StreamingUploadHelper streamingUpload;
    private DataBackupHelper dataBackupHelper;
    private QwenHelper qwenHelper;
    private VocabHelper vocabHelper;

    // v6.23: English predictive input
    private EnglishDictionary englishDict;
    private final StringBuilder enWordBuffer = new StringBuilder();
    // Current suggestion strings (3 slots); null/empty = no suggestion
    private final String[] enSuggestions = new String[3];
    private TextView enSuggest0, enSuggest1, enSuggest2;

    // Streaming state (APPEND mode with VAD)
    private volatile boolean streamingMode = false;
    private volatile boolean streamQwenActive = false;
    private final List<String> streamChunkTexts = new ArrayList<>();

    // v4.1: Audio streaming state (APPEND mode → WebSocket PCM chunks)
    private WebSocket audioStreamWs = null;
    private final List<String> streamedChunks = Collections.synchronizedList(new ArrayList<>());
    private int streamChunkTotal = 0;
    private volatile boolean audioStreamActive = false;
    // v6.4: APPEND 即時預覽改由手機端 VAD+SenseVoice 供應；WS chunk 仍保留給伺服器 final 路徑。
    private volatile boolean onDeviceAppendPreviewEnabled = false;
    private final Object onDeviceAppendPreviewLock = new Object();
    private final List<String> onDeviceAppendPreviewSegments = new ArrayList<>();

    // v6.1: 全程保留整段音訊 → WS 失敗 / final 為空時做一次「乾淨重轉錄」(有標點、走伺服器校正)，
    //       絕不再把無標點的串流預覽倒進輸入框。streamFailed = WS 中途斷線旗標。
    private ByteArrayOutputStream fullPcmBuffer;
    private volatile boolean streamFailed = false;
    // v6.25: 每段口述用獨立 generation；同世代仍只允許一次終局動作，不同世代互不干擾。
    private final java.util.concurrent.atomic.AtomicInteger utteranceGeneration =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile int activeUtteranceGeneration = 0;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> committedGenerations =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> fullPcmByGeneration =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Runnable> serverWaitBudgetCallbacks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Object utteranceCommitLock = new Object();
    private int nextGenToCommit = 1;
    private final java.util.TreeMap<Integer, String> pendingCommits = new java.util.TreeMap<>();
    private final Set<Integer> completedGenerations = new HashSet<>();
    // v6.1: fullPcmBuffer 封頂 ~10 分鐘（19.2MB）防無界成長
    private static final int MAX_FULL_PCM_BYTES = SAMPLE_RATE * 2 * 600;

    // v6.20 fields
    private final Set<String> markedClips = new LinkedHashSet<>();
    private String aiContextText = null;
    private int aiContextCount = 0;
    private int fieldGeneration = 0;
    private boolean autoVocabEnabled = true;
    private Set<String> enrolledVocab = new HashSet<>();
    private long lastEnrollTimeMs = 0;
    private int enrollCountThisMinute = 0;
    private long enrollMinuteStartMs = 0;
    private int enrollCountToday = 0;
    private long enrollDayStartMs = 0;
    private boolean folderNamingMode = false;

    // UI elements
    private View rootView;
    private TextView statusText;
    private TextView previewText;
    private View btnMic;
    private TextView btnMode;
    private TextView btnClipboard;   // v6.20: promoted to field (was local in onCreateInputView)
    private FrameLayout panelContainer;
    private PopupWindow symbolPopup;
    private View clipMarkFooter;
    private TextView clipMarkCount;

    // Keyboard switching
    private View voiceKeyboard;
    private View englishKeyboard;
    private View numbersKeyboard;
    private boolean shiftActive = false;
    private boolean capsLock = false;

    // Panel state
    private enum Panel { NONE, CLIPBOARD, COMMANDS }
    private Panel activePanel = Panel.NONE;

    // Long press / double tap
    private long lastTapTime = 0;
    private static final long DOUBLE_TAP_THRESHOLD = 400;
    private boolean longPressTriggered = false;
    private Runnable longPressRunnable;
    private Runnable pendingFinalizeRunnable;  // v5.3: 延遲 finalize
    private static final long LONG_PRESS_THRESHOLD = 500;
    // v6.14: APPEND now flushes audio while speaking every ~2s, so the release tail is small.
    // Keep a short grace for final syllables without adding a full post-stop second.
    private static final long FINALIZE_DELAY_MS = 400;

    // Backspace repeat acceleration
    private boolean backspacePressed = false;
    private int backspaceRepeatCount = 0;
    private Runnable backspaceRepeatRunnable;

    private static final String ORIGINAL_COMMA = "，";
    private static final String ORIGINAL_PERIOD = "。";
    private static final String[] FULL_WIDTH_SYMBOLS = {
            "，", "。", "、", "？", "！", "：", "；", "「", "」", "『", "』", "（", "）",
            "《", "》", "〈", "〉", "─", "…", "～", "％", "＃", "＠", "＆", "＊"
    };
    private static final String[] HALF_WIDTH_SYMBOLS = {
            ",", ".", "?", "!", ":", ";", "'", "\"", "(", ")", "[", "]", "{", "}", "/",
            "\\", "-", "_", "~", "@", "#", "%", "&", "*", "+", "=", "<", ">"
    };
    private static final long OFFLINE_CORRECTION_TIMEOUT_MS = 2_000L;
    // 等伺服器語意校正的預算。實測伺服器 p50 約 650ms、尾端可達 1.8s；
    // 端上確定性校正約 11ms。超過此預算就先給端上結果，不讓使用者空等。
    private static final long SERVER_WAIT_BUDGET_MS = 1_200L;
    private static final String OFFLINE_CORRECTION_FAILED_SENTINEL =
            "\uE000OFFLINE_CORRECTION_FAILED\uE000";

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        mainHandler = new Handler(Looper.getMainLooper());
        clipboardHelper = new ClipboardHelper(this);
        commandsHelper = new CommandsHelper(this);
        englishMapper = new EnglishMapper(this);
        onDeviceCorrection = new OnDeviceCorrectionEngine(this);
        new Thread(() -> {
            onDeviceCorrection.init();
            if (onDeviceCorrection.isCorrectorReady()) {
                Log.i(TAG, "端上 APPEND 校正就緒"
                        + (onDeviceCorrection.isPunctuationReady() ? "（含標點）" : "（標點待下載）"));
            }
        }, "OnDeviceCorrect-Init").start();
        streamingUpload = new StreamingUploadHelper();
        dataBackupHelper = new DataBackupHelper(this);
        qwenHelper = new QwenHelper(this);
        vocabHelper = new VocabHelper(this);
        SharedPreferences v620prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        autoVocabEnabled = v620prefs.getBoolean("auto_vocab_enabled", true);
        loadEnrolledVocab();
        clipboardHelper.setVocabListener((t, l) -> maybeAutoEnrollVocab(t, l));

        // 載入上次使用的模式
        loadSavedMode();

        // v6.23: 背景載入英文預測字典
        englishDict = new EnglishDictionary(this);
        new Thread(() -> englishDict.loadAsync(), "EnglishDict-Load").start();

        // 背景初始化本機 STT
        localSTT = new LocalSTTHelper(this);
        new Thread(() -> {
            localSTT.init();
            localSTTReady = localSTT.isReady();
            if (localSTTReady) {
                Log.i(TAG, "本機 STT 就緒" +
                        (localSTT.isStreamingReady() ? "（含 VAD 串流）" : "（單次辨識）"));
            }

            // v5.4: 預熱音訊引擎 — 提前初始化 AudioRecord，暖機 HAL
            // 首次按麥克風時 AudioRecord 初始化更快（~100-200ms 改善）
            try {
                int warmBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
                if (warmBuf > 0) {
                    AudioRecord warmRec = new AudioRecord(
                            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, warmBuf);
                    if (warmRec.getState() == AudioRecord.STATE_INITIALIZED) {
                        warmRec.startRecording();
                        Thread.sleep(50); // 短暫啟動讓 HAL 完成初始化
                        warmRec.stop();
                        warmRec.release();
                        Log.i(TAG, "音訊引擎預熱完成");
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "音訊預熱失敗（不影響功能）: " + e.getMessage());
            }
        }, "LocalSTT-Init").start();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        // v6.20: only treat a genuinely new field (not an internal restart) as a field switch.
        // On a real switch, bump the generation guard and disarm any pending AI material so it
        // never leaks into an unrelated field. Keep state on restarting==true.
        if (!restarting) {
            fieldGeneration++;
            aiContextText = null;
            aiContextCount = 0;
            markedClips.clear();
            updateArmedIndicator();
            // v6.23: clear English prediction buffer on field switch
            clearEnWordBuffer();
        }
    }

    @Override
    public View onCreateInputView() {
        rootView = LayoutInflater.from(this).inflate(R.layout.keyboard_view, null);

        statusText = rootView.findViewById(R.id.statusText);
        previewText = rootView.findViewById(R.id.previewText);
        btnMic = rootView.findViewById(R.id.btnMic);
        btnMode = rootView.findViewById(R.id.btnMode);
        panelContainer = rootView.findViewById(R.id.panelContainer);
        View btnSpace = rootView.findViewById(R.id.btnSpace);
        View btnBackspace = rootView.findViewById(R.id.btnBackspace);
        View btnEnter = rootView.findViewById(R.id.btnEnter);
        View btnSettings = rootView.findViewById(R.id.btnSettings);
        btnClipboard = (TextView) rootView.findViewById(R.id.btnClipboard);
        View btnCommands = rootView.findViewById(R.id.btnCommands);
        View btnSwitchIME = rootView.findViewById(R.id.btnSwitchIME);

        // --- 麥克風 ---
        btnMic.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    handleTouchDown();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handleTouchUp();
                    return true;
            }
            return false;
        });

        // --- 模式切換 ---
        btnMode.setOnClickListener(v -> cycleMode());

        // --- 空格 ---
        btnSpace.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) commitTextProgrammatically(ic, " ");
        });

        // --- 逗號 ---
        View btnComma = rootView.findViewById(R.id.btnComma);
        setupSymbolLauncher(btnComma, FULL_WIDTH_SYMBOLS, 5, ORIGINAL_COMMA);

        // --- 句號 ---
        View btnPeriod = rootView.findViewById(R.id.btnPeriod);
        setupSymbolLauncher(btnPeriod, HALF_WIDTH_SYMBOLS, 6, ORIGINAL_PERIOD);

        // --- 退格（長按加速連刪） ---
        btnBackspace.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    backspacePressed = true;
                    backspaceRepeatCount = 0;
                    // 先刪一個字
                    InputConnection ic0 = getCurrentInputConnection();
                    if (ic0 != null) {
                        if (!deleteSelectionIfAny(ic0)) deleteSurroundingTextProgrammatically(ic0, 1, 0);
                    }
                    // 啟動連刪
                    backspaceRepeatRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (!backspacePressed) return;
                            InputConnection ic = getCurrentInputConnection();
                            if (ic != null) {
                                backspaceRepeatCount++;
                                // 加速：前5次刪1字，5-15次刪2字，15次以上刪5字
                                int deleteCount = backspaceRepeatCount < 5 ? 1
                                        : backspaceRepeatCount < 15 ? 2 : 5;
                                deleteSurroundingTextProgrammatically(ic, deleteCount, 0);
                            }
                            // 加速間隔：初始 120ms → 最低 30ms
                            long delay = Math.max(30, 120 - backspaceRepeatCount * 6);
                            mainHandler.postDelayed(this, delay);
                        }
                    };
                    mainHandler.postDelayed(backspaceRepeatRunnable, 400); // 首次延遲
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    backspacePressed = false;
                    if (backspaceRepeatRunnable != null) {
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                    }
                    v.setPressed(false);
                    return true;
            }
            return false;
        });

        // --- Enter ---
        btnEnter.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                EditorInfo ei = getCurrentInputEditorInfo();
                if (ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                        && (ei.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
                    markProgrammaticTextChange();
                    ic.performEditorAction(ei.imeOptions & EditorInfo.IME_MASK_ACTION);
                } else {
                    commitTextProgrammatically(ic, "\n");
                }
            }
        });

        // --- 設定 ---
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        // --- 剪貼簿 ---
        btnClipboard.setOnClickListener(v -> togglePanel(Panel.CLIPBOARD));

        // --- 常用指令 ---
        btnCommands.setOnClickListener(v -> togglePanel(Panel.COMMANDS));

        // --- 切換英文鍵盤（短按）/ 跳轉輸入法（長按） ---
        btnSwitchIME.setOnClickListener(v -> switchKeyboard(KeyboardMode.ENGLISH));
        btnSwitchIME.setOnLongClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
            return true;
        });

        // --- 鍵盤切換設定 ---
        voiceKeyboard = rootView.findViewById(R.id.voiceKeyboard);
        englishKeyboard = rootView.findViewById(R.id.englishKeyboard);
        numbersKeyboard = rootView.findViewById(R.id.numbersKeyboard);

        // 設定英文鍵盤和數字鍵盤的按鍵處理
        setupTypingKeyboard(englishKeyboard);
        setupTypingKeyboard(numbersKeyboard);

        // v6.23: 英文預測列
        enSuggest0 = rootView.findViewById(R.id.enSuggest0);
        enSuggest1 = rootView.findViewById(R.id.enSuggest1);
        enSuggest2 = rootView.findViewById(R.id.enSuggest2);
        if (enSuggest0 != null) enSuggest0.setOnClickListener(v -> applyEnglishSuggestion(0));
        if (enSuggest1 != null) enSuggest1.setOnClickListener(v -> applyEnglishSuggestion(1));
        if (enSuggest2 != null) enSuggest2.setOnClickListener(v -> applyEnglishSuggestion(2));

        updateModeUI();
        updateArmedIndicator();
        return rootView;
    }

    // ==================== Symbol Launchers ====================

    private void setupSymbolLauncher(View key, String[] symbols, int columns, String fallbackText) {
        if (key == null) return;
        key.setOnClickListener(v -> showSymbolPopupOrFallback(v, symbols, columns, fallbackText));
        key.setOnLongClickListener(v -> {
            dismissSymbolPopup();
            commitTextSafely(fallbackText);
            return true;
        });
    }

    private void showSymbolPopupOrFallback(View anchor, String[] symbols, int columns, String fallbackText) {
        try {
            dismissSymbolPopup();
            if (activePanel != Panel.NONE) {
                closePanel();
            }

            View content = buildSymbolPopupContent(symbols, columns, fallbackText);
            content.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

            int popupWidth = content.getMeasuredWidth();
            int popupHeight = content.getMeasuredHeight();
            if (popupWidth <= 0 || popupHeight <= 0) {
                throw new IllegalStateException("symbol popup measured empty");
            }

            symbolPopup = new PopupWindow(content, popupWidth, popupHeight, false);
            symbolPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            symbolPopup.setOutsideTouchable(true);
            symbolPopup.setClippingEnabled(false);
            symbolPopup.setElevation(dp(8));

            int xOffset = calculateSymbolPopupXOffset(anchor, popupWidth);
            int yOffset = -(popupHeight + anchor.getHeight() + dp(6));
            symbolPopup.showAsDropDown(anchor, xOffset, yOffset);
        } catch (Exception e) {
            Log.w(TAG, "Symbol popup failed; committing fallback punctuation", e);
            dismissSymbolPopup();
            commitTextSafely(fallbackText);
        }
    }

    private View buildSymbolPopupContent(String[] symbols, int columns, String fallbackText) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        panel.setBackgroundColor(0xFF1A1A2E);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(columns);
        grid.setRowCount((int) Math.ceil(symbols.length / (double) columns));

        for (String symbol : symbols) {
            TextView button = new TextView(this);
            button.setText(symbol);
            button.setTextColor(0xFFE0E0E0);
            button.setTextSize(18);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(0xFF16213E);
            button.setClickable(true);
            button.setFocusable(true);
            button.setOnClickListener(v -> {
                commitSymbolFromPopup(symbol, fallbackText);
                dismissSymbolPopup();
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(40);
            lp.height = dp(38);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            grid.addView(button, lp);
        }

        panel.addView(grid);
        return panel;
    }

    private int calculateSymbolPopupXOffset(View anchor, int popupWidth) {
        if (rootView == null || rootView.getWidth() <= 0) {
            return -Math.max(0, popupWidth - anchor.getWidth()) / 2;
        }

        int[] anchorLocation = new int[2];
        int[] rootLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        rootView.getLocationOnScreen(rootLocation);

        int margin = dp(4);
        int anchorLeft = anchorLocation[0] - rootLocation[0];
        int desiredLeft = anchorLeft + anchor.getWidth() / 2 - popupWidth / 2;
        int maxLeft = Math.max(margin, rootView.getWidth() - popupWidth - margin);
        int clampedLeft = Math.max(margin, Math.min(desiredLeft, maxLeft));
        return clampedLeft - anchorLeft;
    }

    private void commitSymbolFromPopup(String symbol, String fallbackText) {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                commitTextProgrammatically(ic, symbol);
            }
        } catch (Exception e) {
            Log.w(TAG, "Symbol commit failed; committing fallback punctuation", e);
            commitTextSafely(fallbackText);
        }
    }

    private boolean commitTextSafely(String text) {
        try {
            InputConnection ic = getCurrentInputConnection();
            return ic != null && commitTextProgrammatically(ic, text);
        } catch (Exception e) {
            Log.w(TAG, "Fallback punctuation commit failed", e);
            return false;
        }
    }

    /**
     * v6.17: Commit full transcription text with clipboard-fallback safety net.
     * Always copies text to clipboard first (never-lose guarantee), then:
     *   - Normal apps: ic.commitText; if that returns false -> paste fallback.
     *   - Problematic apps (Termux, Gemini): skip commitText, go straight to paste.
     *   - ic == null: text already on clipboard, show hint.
     * Small single-char / punctuation / space commits should NOT use this method.
     */
    private void commitFinalText(String text) {
        try {
            if (text == null || text.isEmpty()) return;
            settlePendingCorrectionCapture();
            TextSnapshot beforeSnapshot = getCurrentTextSnapshotSafely();

            // Step 1: Always copy to clipboard first as safety net.
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("simon-ime", text));
                }
            } catch (Exception ex) {
                Log.w(TAG, "commitFinalText: clipboard copy failed", ex);
            }

            // Step 2: Detect problematic apps that reject commitText.
            EditorInfo ei = getCurrentInputEditorInfo();
            String packageName = (ei != null) ? ei.packageName : null;
            boolean problematic = packageName != null
                    && (packageName.startsWith("com.termux")
                    || packageName.contains("bard")
                    || packageName.contains("gemini")
                    || packageName.equals("com.google.android.apps.bard"));

            // Step 3: Get input connection.
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                Log.w(TAG, "commitFinalText: ic == null, text on clipboard");
                updateStatus("已複製，可長按貼上");
                return;
            }

            // Step 4: Normal path.
            if (!problematic) {
                markProgrammaticTextChange();
                boolean ok = ic.commitText(text, 1);
                if (ok) {
                    recordVoiceCommit(text, beforeSnapshot);
                    return;
                }
                Log.w(TAG, "commitFinalText: commitText returned false for pkg=" + packageName + ", falling back to paste");
            }

            // Step 5: Paste fallback (text already on clipboard).
            markProgrammaticTextChange();
            ic.performContextMenuAction(android.R.id.paste);
            recordVoiceCommit(text, beforeSnapshot);
            updateStatus("已複製，可長按貼上");

        } catch (Exception e) {
            Log.e(TAG, "commitFinalText: unexpected exception, text should be on clipboard", e);
            updateStatus("已複製，可長按貼上");
        }
    }

    private boolean reserveUtteranceGeneration(int gen) {
        if (gen <= 0) return true;
        boolean reserved = committedGenerations.putIfAbsent(gen, Boolean.TRUE) == null;
        pruneUtteranceGenerationState();
        return reserved;
    }

    private void rememberFullPcmForGeneration(int gen, byte[] pcm) {
        if (gen <= 0 || pcm == null) return;
        fullPcmByGeneration.put(gen, pcm);
        pruneUtteranceGenerationState();
    }

    private byte[] getFullPcmForGeneration(int gen) {
        byte[] pcm = fullPcmByGeneration.get(gen);
        if (pcm != null) return pcm;
        if (gen == activeUtteranceGeneration && fullPcmBuffer != null) {
            return fullPcmBuffer.toByteArray();
        }
        return null;
    }

    private <T> void pruneConcurrentGenerationMap(java.util.concurrent.ConcurrentHashMap<Integer, T> map) {
        while (map.size() > 8) {
            Integer min = null;
            for (Integer key : map.keySet()) {
                if (min == null || key < min) min = key;
            }
            if (min == null) return;
            map.remove(min);
        }
    }

    private void pruneUtteranceGenerationState() {
        pruneConcurrentGenerationMap(committedGenerations);
        pruneConcurrentGenerationMap(fullPcmByGeneration);
    }

    private void clearServerWaitBudgetCallbacks() {
        if (mainHandler == null) return;
        for (Runnable callback : serverWaitBudgetCallbacks.values()) {
            mainHandler.removeCallbacks(callback);
        }
        serverWaitBudgetCallbacks.clear();
    }

    private void completeReservedUtteranceWithText(int gen, String text) {
        if (text == null || text.isEmpty()) {
            completeReservedUtteranceWithoutText(gen);
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> completeReservedUtteranceWithText(gen, text));
            return;
        }

        List<String> readyTexts = new ArrayList<>();
        synchronized (utteranceCommitLock) {
            if (gen < nextGenToCommit) return;
            completedGenerations.add(gen);
            pendingCommits.put(gen, text);
            collectReadyUtteranceCommitsLocked(readyTexts);
        }
        for (String readyText : readyTexts) {
            commitFinalText(readyText);
            updateStatus("完成: " + truncate(readyText, 20));
        }
    }

    private void completeReservedUtteranceWithoutText(int gen) {
        if (gen <= 0) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> completeReservedUtteranceWithoutText(gen));
            return;
        }

        List<String> readyTexts = new ArrayList<>();
        synchronized (utteranceCommitLock) {
            if (gen < nextGenToCommit) return;
            completedGenerations.add(gen);
            collectReadyUtteranceCommitsLocked(readyTexts);
        }
        for (String readyText : readyTexts) {
            commitFinalText(readyText);
            updateStatus("完成: " + truncate(readyText, 20));
        }
    }

    private void collectReadyUtteranceCommitsLocked(List<String> readyTexts) {
        while (completedGenerations.contains(nextGenToCommit)) {
            String text = pendingCommits.remove(nextGenToCommit);
            if (text != null && !text.isEmpty()) {
                readyTexts.add(text);
            }
            completedGenerations.remove(nextGenToCommit);
            nextGenToCommit++;
        }
    }

    private static class TextSnapshot {
        final String text;
        final int selectionStart;
        final int selectionEnd;

        TextSnapshot(String text, int selectionStart, int selectionEnd) {
            this.text = text != null ? text : "";
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }
    }

    private void markProgrammaticTextChange() {
        try {
            mIgnoreNextUpdateSelection = true;
        } catch (Exception ignored) {
        }
    }

    private boolean commitTextProgrammatically(InputConnection ic, String text) {
        try {
            if (ic == null) return false;
            markProgrammaticTextChange();
            return ic.commitText(text, 1);
        } catch (Exception e) {
            Log.w(TAG, "Programmatic commitText failed", e);
            return false;
        }
    }

    private boolean deleteSurroundingTextProgrammatically(InputConnection ic, int beforeLength, int afterLength) {
        try {
            if (ic == null) return false;
            markProgrammaticTextChange();
            return ic.deleteSurroundingText(beforeLength, afterLength);
        } catch (Exception e) {
            Log.w(TAG, "Programmatic deleteSurroundingText failed", e);
            return false;
        }
    }

    private TextSnapshot getCurrentTextSnapshotSafely() {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return null;

            android.view.inputmethod.ExtractedTextRequest req =
                    new android.view.inputmethod.ExtractedTextRequest();
            req.hintMaxChars = 20_000;
            req.hintMaxLines = 1_000;
            android.view.inputmethod.ExtractedText extracted = ic.getExtractedText(req, 0);
            if (extracted != null && extracted.text != null) {
                return new TextSnapshot(extracted.text.toString(),
                        extracted.selectionStart, extracted.selectionEnd);
            }

            CharSequence before = ic.getTextBeforeCursor(4_000, 0);
            CharSequence after = ic.getTextAfterCursor(4_000, 0);
            String beforeText = before != null ? before.toString() : "";
            String afterText = after != null ? after.toString() : "";
            int cursor = beforeText.length();
            return new TextSnapshot(beforeText + afterText, cursor, cursor);
        } catch (Exception e) {
            Log.w(TAG, "Correction capture snapshot failed", e);
            return null;
        }
    }

    private void recordVoiceCommit(String text, TextSnapshot beforeSnapshot) {
        try {
            if (text == null || text.isEmpty()) return;
            mLastVoiceCommittedText = text;
            mLastVoiceCommittedTs = System.currentTimeMillis();
            mPendingCorrectionCapture = false;
            mPendingCorrectionCommitTs = 0L;
            mCapturePostedCommitTs = 0L;
            if (mainHandler != null) {
                mainHandler.removeCallbacks(mPendingCorrectionCaptureRunnable);
            }

            mLastVoiceCommitStart = -1;
            mLastVoiceCommitEnd = -1;
            mLastVoiceCommitFieldLength = -1;
            if (beforeSnapshot != null && beforeSnapshot.selectionStart >= 0 && beforeSnapshot.selectionEnd >= 0) {
                int start = Math.min(beforeSnapshot.selectionStart, beforeSnapshot.selectionEnd);
                int end = Math.max(beforeSnapshot.selectionStart, beforeSnapshot.selectionEnd);
                int replacedLength = Math.max(0, end - start);
                mLastVoiceCommitStart = start;
                mLastVoiceCommitEnd = start + text.length();
                mLastVoiceCommitFieldLength =
                        beforeSnapshot.text.length() - replacedLength + text.length();
            }
        } catch (Exception e) {
            Log.w(TAG, "Correction capture recordVoiceCommit failed", e);
        }
    }

    private void settlePendingCorrectionCapture() {
        try {
            if (mainHandler != null) {
                mainHandler.removeCallbacks(mPendingCorrectionCaptureRunnable);
            }
            flushPendingCorrectionCapture();
        } catch (Exception e) {
            Log.w(TAG, "Correction capture settle failed", e);
        }
    }

    private void maybeScheduleCorrectionCapture(int newSelStart, int newSelEnd) {
        try {
            if (mLastVoiceCommittedText == null || mLastVoiceCommittedText.isEmpty()) return;
            if (mLastVoiceCommitStart < 0 || mLastVoiceCommitEnd < mLastVoiceCommitStart) return;
            long now = System.currentTimeMillis();
            if (now - mLastVoiceCommittedTs > CORRECTION_CAPTURE_WINDOW_MS) return;
            if (mCapturePostedCommitTs == mLastVoiceCommittedTs) return;

            TextSnapshot snapshot = getCurrentTextSnapshotSafely();
            String afterText = buildEditedVoiceText(snapshot, newSelStart, newSelEnd);
            if (afterText == null || afterText.isEmpty()) return;
            if (afterText.equals(mLastVoiceCommittedText)) return;

            mPendingCorrectionCapture = true;
            mPendingCorrectionCommitTs = mLastVoiceCommittedTs;
            if (mainHandler != null) {
                mainHandler.removeCallbacks(mPendingCorrectionCaptureRunnable);
                mainHandler.postDelayed(mPendingCorrectionCaptureRunnable, CORRECTION_CAPTURE_DEBOUNCE_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "Correction capture schedule failed", e);
        }
    }

    private String buildEditedVoiceText(TextSnapshot snapshot, int selStart, int selEnd) {
        try {
            if (snapshot == null || snapshot.text == null) return null;
            int textLength = snapshot.text.length();
            if (mLastVoiceCommitFieldLength < 0 || mLastVoiceCommitStart > textLength) return null;

            int lengthDelta = textLength - mLastVoiceCommitFieldLength;
            int adjustedEnd = mLastVoiceCommitEnd + lengthDelta;
            int start = clamp(mLastVoiceCommitStart, 0, textLength);
            int end = clamp(adjustedEnd, start, textLength);

            int cursorStart = selStart >= 0 ? selStart : snapshot.selectionStart;
            int cursorEnd = selEnd >= 0 ? selEnd : snapshot.selectionEnd;
            if (cursorStart >= 0 || cursorEnd >= 0) {
                int cursorMin = Math.min(cursorStart >= 0 ? cursorStart : cursorEnd,
                        cursorEnd >= 0 ? cursorEnd : cursorStart);
                int cursorMax = Math.max(cursorStart >= 0 ? cursorStart : cursorEnd,
                        cursorEnd >= 0 ? cursorEnd : cursorStart);
                int guardStart = Math.max(0, start - 2);
                int guardEnd = Math.min(textLength, Math.max(end, mLastVoiceCommitEnd) + 2);
                if (cursorMax < guardStart || cursorMin > guardEnd) return null;
                end = clamp(Math.max(end, cursorMax), start, textLength);
            }

            String edited = snapshot.text.substring(start, end).trim();
            if (edited.isEmpty()) return null;
            return edited;
        } catch (Exception e) {
            Log.w(TAG, "Correction capture build edited text failed", e);
            return null;
        }
    }

    private void flushPendingCorrectionCapture() {
        try {
            if (!mPendingCorrectionCapture) return;
            if (mPendingCorrectionCommitTs != mLastVoiceCommittedTs) {
                mPendingCorrectionCapture = false;
                return;
            }
            if (mCapturePostedCommitTs == mLastVoiceCommittedTs) {
                mPendingCorrectionCapture = false;
                return;
            }

            TextSnapshot snapshot = getCurrentTextSnapshotSafely();
            String afterText = buildEditedVoiceText(snapshot,
                    snapshot != null ? snapshot.selectionStart : -1,
                    snapshot != null ? snapshot.selectionEnd : -1);
            if (afterText == null || afterText.isEmpty()
                    || afterText.equals(mLastVoiceCommittedText)) {
                mPendingCorrectionCapture = false;
                return;
            }

            long committedTs = mLastVoiceCommittedTs;
            String beforeText = mLastVoiceCommittedText;
            mPendingCorrectionCapture = false;
            mCapturePostedCommitTs = committedTs;
            postCorrectionCapture(beforeText, afterText, committedTs, System.currentTimeMillis());
        } catch (Exception e) {
            mPendingCorrectionCapture = false;
            Log.w(TAG, "Correction capture flush failed", e);
        }
    }

    private void postCorrectionCapture(String beforeText, String afterText, long committedTs, long editTs) {
        try {
            if (beforeText == null || afterText == null) return;
            if (beforeText.isEmpty() || afterText.isEmpty() || beforeText.equals(afterText)) return;

            JSONObject payload = new JSONObject();
            payload.put("before_text", beforeText);
            payload.put("after_text", afterText);
            payload.put("committed_ts", committedTs);
            payload.put("edit_ts", editTs);
            payload.put("source", "ime_edit");
            payload.put("app_version", BuildConfig.VERSION_NAME);

            RequestBody body = RequestBody.create(payload.toString(),
                    MediaType.parse("application/json; charset=utf-8"));
            Request.Builder reqBuilder = new Request.Builder()
                    .url(getServerUrl() + "/v1/capture-correction")
                    .post(body);

            String auth = getAuthPassword();
            if (auth != null && !auth.isEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer " + auth);
            }

            httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.w(TAG, "Correction capture request failed");
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    response.close();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Correction capture post failed", e);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private void dismissSymbolPopup() {
        try {
            if (symbolPopup != null && symbolPopup.isShowing()) {
                symbolPopup.dismiss();
            }
        } catch (Exception e) {
            Log.w(TAG, "Symbol popup dismiss failed", e);
        } finally {
            symbolPopup = null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ==================== Panel Management ====================

    private void togglePanel(Panel panel) {
        if (activePanel == panel) {
            closePanel();
        } else {
            showPanel(panel);
        }
    }

    private void showPanel(Panel panel) {
        panelContainer.removeAllViews();
        panelContainer.setVisibility(View.VISIBLE);
        activePanel = panel;

        switch (panel) {
            case CLIPBOARD:
                showClipboardPanel();
                break;
            case COMMANDS:
                showCommandsPanel();
                break;
        }
    }

    private void closePanel() {
        panelContainer.removeAllViews();
        panelContainer.setVisibility(View.GONE);
        activePanel = Panel.NONE;
    }

    /**
     * v6.22: 剪貼簿點字貼上。commitText 為主（v6.19 一直用這條，多數 app 正常），
     * 失敗才走系統貼上兜底（Termux/Gemini 類拒收 commitText 的 app）。
     * 狀態訊息可遠端診斷：點了「完全沒訊息」＝觸擊沒進來(版面問題)；
     * 「無輸入連線」＝ic null；「📋 已貼上」＝commit 成功。
     */
    private boolean pasteClipboardText(String text) {
        if (text == null || text.isEmpty()) return false;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            copyToSystemClipboard(text);
            updateStatus("已複製，長按輸入框貼上（無輸入連線）");
            return true;
        }
        markProgrammaticTextChange();
        boolean ok = ic.commitText(text, 1);
        if (!ok) {
            // 部分 app 拒收 commitText → 用系統剪貼簿 + 貼上動作兜底
            copyToSystemClipboard(text);
            markProgrammaticTextChange();
            ic.performContextMenuAction(android.R.id.paste);
        }
        updateStatus("📋 已貼上");
        return true;
    }

    private void copyToSystemClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("simon-ime", text));
        } catch (Exception ignored) {}
    }

    // ==================== Clipboard Panel ====================

    private void showClipboardPanel() {
        View view = LayoutInflater.from(this).inflate(R.layout.clipboard_panel, panelContainer, true);

        // --- 標題列按鈕 ---
        Button btnClose = view.findViewById(R.id.btnClipClose);
        btnClose.setOnClickListener(v -> closePanel());

        Button btnVocabList = view.findViewById(R.id.btnClipVocabList);
        btnVocabList.setOnClickListener(v -> showVocabListInline());

        // --- 標記底欄 ---
        clipMarkFooter = view.findViewById(R.id.clipMarkFooter);
        clipMarkCount = view.findViewById(R.id.clipMarkCount);

        Button btnSetAiContext = view.findViewById(R.id.btnClipSetAiContext);
        btnSetAiContext.setOnClickListener(v -> armAiContext());

        Button btnAddVocab = view.findViewById(R.id.btnClipAddVocab);
        btnAddVocab.setOnClickListener(v -> {
            if (!markedClips.isEmpty()) {
                showBatchAddToCommandsInline(new ArrayList<>(markedClips));
            } else {
                updateStatus("請先標記剪貼內容");
            }
        });

        Button btnClearMark = view.findViewById(R.id.btnClipClearMark);
        btnClearMark.setOnClickListener(v -> {
            markedClips.clear();
            updateArmedIndicator();
            showPanel(Panel.CLIPBOARD);
        });

        // --- 列表 ---
        RecyclerView recycler = view.findViewById(R.id.clipRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        List<String> items = clipboardHelper.getHistory();
        ClipAdapter adapter = new ClipAdapter(items, position -> {
            if (position >= 0 && position < items.size()) {
                if (pasteClipboardText(items.get(position))) closePanel();
            }
        });
        recycler.setAdapter(adapter);

        updateClipMarkFooter();

        // 右滑 → 加入常用指令；左滑 → 標記/取消標記
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.RIGHT | ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos < 0 || pos >= items.size()) return;
                String clipText = items.get(pos);
                // 恢復 item（不真的移除）
                adapter.notifyItemChanged(pos);
                if (direction == ItemTouchHelper.RIGHT) {
                    showAddToCommandsInline(clipText);
                } else {
                    // 左滑：切換標記
                    if (markedClips.contains(clipText)) {
                        markedClips.remove(clipText);
                    } else {
                        markedClips.add(clipText);
                    }
                    updateArmedIndicator();
                    updateClipMarkFooter();
                    adapter.notifyItemChanged(pos);
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                    int actionState, boolean isActive) {
                if (dX > 0) {
                    // 右滑：綠色背景 ⚡+
                    Paint paint = new Paint();
                    paint.setColor(0xFF2d6a4f);
                    c.drawRect(vh.itemView.getLeft(), vh.itemView.getTop(),
                            vh.itemView.getLeft() + dX, vh.itemView.getBottom(), paint);
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(36f);
                    textPaint.setAntiAlias(true);
                    c.drawText("⚡+", vh.itemView.getLeft() + 24,
                            (vh.itemView.getTop() + vh.itemView.getBottom()) / 2f + 12, textPaint);
                } else if (dX < 0) {
                    // 左滑：紫色背景 ✓
                    Paint paint = new Paint();
                    paint.setColor(0xFF4a1060);
                    c.drawRect(vh.itemView.getRight() + dX, vh.itemView.getTop(),
                            vh.itemView.getRight(), vh.itemView.getBottom(), paint);
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(36f);
                    textPaint.setAntiAlias(true);
                    c.drawText("✓", vh.itemView.getRight() + dX + 16,
                            (vh.itemView.getTop() + vh.itemView.getBottom()) / 2f + 12, textPaint);
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive);
            }
        }).attachToRecyclerView(recycler);
    }

    /** 在 panelContainer 內顯示「加入常用指令」面板（不用 AlertDialog） */
    private void showAddToCommandsInline(String clipText) {
        panelContainer.removeAllViews();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF111122);
        panel.setPadding(24, 16, 24, 16);

        // 標題
        TextView title = new TextView(this);
        title.setText("加入常用指令");
        title.setTextColor(0xFF4ECCA3);
        title.setTextSize(16);
        panel.addView(title);

        // 預覽
        String autoLabel = clipText.length() > 10 ? clipText.substring(0, 10) + "…" : clipText;
        TextView preview = new TextView(this);
        preview.setText("標籤：" + autoLabel);
        preview.setTextColor(0xFFcccccc);
        preview.setTextSize(13);
        preview.setPadding(0, 8, 0, 12);
        panel.addView(preview);

        // 群組選擇按鈕列
        TextView groupLabel = new TextView(this);
        groupLabel.setText("選擇群組：");
        groupLabel.setTextColor(0xFF888888);
        groupLabel.setTextSize(12);
        panel.addView(groupLabel);

        LinearLayout groupRow = new LinearLayout(this);
        groupRow.setOrientation(LinearLayout.HORIZONTAL);
        groupRow.setPadding(0, 8, 0, 12);

        List<String> groupNames = commandsHelper.getGroupNames();
        final String[] selectedGroup = { groupNames.isEmpty() ? null : groupNames.get(0) };
        final Button[] groupButtons = new Button[groupNames.size()];

        for (int i = 0; i < groupNames.size(); i++) {
            String gName = groupNames.get(i);
            Button btn = new Button(this);
            btn.setText(gName);
            btn.setTextSize(12);
            btn.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 72);
            lp.setMarginEnd(8);
            btn.setLayoutParams(lp);
            btn.setPadding(16, 0, 16, 0);
            groupButtons[i] = btn;

            if (gName.equals(selectedGroup[0])) {
                btn.setTextColor(0xFF4ECCA3);
                btn.setBackgroundColor(0xFF1a1a2e);
            } else {
                btn.setTextColor(0xFF888888);
                btn.setBackgroundColor(0xFF16213e);
            }

            btn.setOnClickListener(v -> {
                selectedGroup[0] = gName;
                for (int j = 0; j < groupButtons.length; j++) {
                    if (groupNames.get(j).equals(gName)) {
                        groupButtons[j].setTextColor(0xFF4ECCA3);
                        groupButtons[j].setBackgroundColor(0xFF1a1a2e);
                    } else {
                        groupButtons[j].setTextColor(0xFF888888);
                        groupButtons[j].setBackgroundColor(0xFF16213e);
                    }
                }
            });
            groupRow.addView(btn);
        }
        // ＋ 新資料夾（語音命名）
        Button btnNewFolder = new Button(this);
        btnNewFolder.setText("＋ 新資料夾");
        btnNewFolder.setTextColor(0xFF4ECCA3);
        btnNewFolder.setTextSize(12);
        btnNewFolder.setAllCaps(false);
        LinearLayout.LayoutParams newFolderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 72);
        newFolderLp.setMarginEnd(8);
        btnNewFolder.setLayoutParams(newFolderLp);
        btnNewFolder.setBackgroundColor(0xFF0a1020);
        btnNewFolder.setOnClickListener(v -> {
            folderNamingMode = true;
            closePanel();
            startRecording();
            updateStatus("🎙 說出資料夾名稱…");
        });
        groupRow.addView(btnNewFolder);

        panel.addView(groupRow);

        // 確認 / 取消
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnConfirm = new Button(this);
        btnConfirm.setText("確認加入");
        btnConfirm.setTextColor(0xFF4ECCA3);
        btnConfirm.setTextSize(14);
        btnConfirm.setOnClickListener(v -> {
            if (selectedGroup[0] != null) {
                commandsHelper.addCommand(selectedGroup[0], autoLabel, clipText);
                updateStatus("⚡ 已加入「" + selectedGroup[0] + "」");
                showPanel(Panel.CLIPBOARD); // 回到剪貼簿
            }
        });

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(0xFF888888);
        btnCancel.setTextSize(14);
        btnCancel.setOnClickListener(v -> showPanel(Panel.CLIPBOARD));

        actionRow.addView(btnConfirm);
        actionRow.addView(btnCancel);
        panel.addView(actionRow);

        panelContainer.addView(panel);
    }

    /** v6.20: 列表點擊回呼介面（提升至 Service 層級，供 ClipAdapter 與 CmdAdapter 共用） */
    interface OnItemClick { void onClick(int position); }

    // v6.20: ClipAdapter 改為非靜態內部類別，以便存取外層 markedClips
    private class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.VH> {
        private final List<String> items;
        private final OnItemClick listener;

        ClipAdapter(List<String> items, OnItemClick listener) {
            this.items = items;
            this.listener = listener;
        }

        @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.clip_item, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            String text = items.get(position);
            holder.text.setText(text);
            boolean marked = markedClips.contains(text);
            holder.accent.setVisibility(marked ? View.VISIBLE : View.GONE);
            holder.checkBox.setVisibility(marked ? View.VISIBLE : View.GONE);
            holder.checkBox.setChecked(marked);
            // v6.22 fix: 只綁 itemView（整列）。clipText/checkBox 已在版面設 clickable=false，
            // 觸擊會冒泡到整列 → 點任何地方都貼上。切勿再對 clipText setOnClickListener
            // （那會把 clipText 重新變成 clickable，又回到 v6.20/6.21 吞觸擊的 bug）。
            holder.itemView.setOnClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onClick(p);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView text;
            View accent;
            CheckBox checkBox;
            VH(View v) {
                super(v);
                text = v.findViewById(R.id.clipText);
                accent = v.findViewById(R.id.clipAccent);
                checkBox = v.findViewById(R.id.clipCheck);
            }
        }
    }

    // ==================== Commands Panel ====================

    private String currentCmdGroup = null;

    private void showCommandsPanel() {
        View view = LayoutInflater.from(this).inflate(R.layout.commands_panel, panelContainer, true);

        Button btnClose = view.findViewById(R.id.btnCmdClose);
        btnClose.setOnClickListener(v -> closePanel());

        // v6.20: ＋ 資料夾（語音命名模式）
        Button btnCmdAddGroup = view.findViewById(R.id.btnCmdAddGroup);
        btnCmdAddGroup.setOnClickListener(v -> {
            folderNamingMode = true;
            closePanel();
            startRecording();
            updateStatus("🎙 說出資料夾名稱…");
        });

        LinearLayout tabContainer = view.findViewById(R.id.cmdGroupTabs);
        RecyclerView recycler = view.findViewById(R.id.cmdRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        List<String> groupNames = commandsHelper.getGroupNames();
        if (!groupNames.isEmpty()) {
            if (currentCmdGroup == null || !groupNames.contains(currentCmdGroup)) {
                currentCmdGroup = groupNames.get(0);
            }
        }

        // Build tabs
        tabContainer.removeAllViews();
        for (String name : groupNames) {
            Button tab = new Button(this);
            tab.setText(name);
            tab.setTextSize(12);
            tab.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(4);
            tab.setLayoutParams(lp);
            tab.setPadding(16, 4, 16, 4);

            if (name.equals(currentCmdGroup)) {
                tab.setTextColor(0xFF4ECCA3);
                tab.setBackgroundColor(0xFF1a1a2e);
            } else {
                tab.setTextColor(0xFF888888);
                tab.setBackgroundColor(0xFF16213e);
            }

            tab.setOnClickListener(v -> {
                currentCmdGroup = name;
                showPanel(Panel.COMMANDS); // Refresh
            });
            // v6.20: 長壓 tab → 刪除該資料夾
            tab.setOnLongClickListener(v -> {
                commandsHelper.removeGroup(name);
                if (name.equals(currentCmdGroup)) {
                    List<String> remaining = commandsHelper.getGroupNames();
                    currentCmdGroup = remaining.isEmpty() ? null : remaining.get(0);
                }
                showPanel(Panel.COMMANDS);
                updateStatus("已刪除「" + name + "」");
                return true;
            });
            tabContainer.addView(tab);
        }

        // Show commands for current group
        if (currentCmdGroup != null) {
            List<CommandsHelper.Command> cmds = commandsHelper.getCommands(currentCmdGroup);
            recycler.setAdapter(new CmdAdapter(cmds, position -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null && position < cmds.size()) {
                    String text = cmds.get(position).text;
                    if (!text.isEmpty()) {
                        commitTextProgrammatically(ic, text);
                        updateStatus("⚡ " + cmds.get(position).label);
                        closePanel();
                    }
                }
            }));
        }
    }

    // Simple RecyclerView Adapter for commands
    private static class CmdAdapter extends RecyclerView.Adapter<CmdAdapter.VH> {
        private final List<CommandsHelper.Command> items;
        private final OnItemClick listener;

        CmdAdapter(List<CommandsHelper.Command> items, OnItemClick listener) {
            this.items = items;
            this.listener = listener;
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.cmd_item, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH holder, int position) {
            CommandsHelper.Command cmd = items.get(position);
            holder.label.setText(cmd.label);
            holder.text.setText(cmd.text);
            holder.itemView.setOnClickListener(v -> listener.onClick(position));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView label, text;
            VH(View v) {
                super(v);
                label = v.findViewById(R.id.cmdLabel);
                text = v.findViewById(R.id.cmdText);
            }
        }
    }

    // ==================== Touch event handling ====================

    private void handleTouchDown() {
        // v5.3: 取消延遲 finalize（如果使用者在 1s 內再次按下）
        if (pendingFinalizeRunnable != null) {
            mainHandler.removeCallbacks(pendingFinalizeRunnable);
            pendingFinalizeRunnable = null;
        }

        long now = System.currentTimeMillis();
        longPressTriggered = false;

        // Double tap → spell mode
        if (now - lastTapTime < DOUBLE_TAP_THRESHOLD) {
            mainHandler.removeCallbacks(longPressRunnable);
            currentMode = Mode.SPELL;
            saveMode();
            updateModeUI();
            startRecording();
            lastTapTime = 0;
            return;
        }

        lastTapTime = now;

        // Schedule long press
        longPressRunnable = () -> {
            longPressTriggered = true;
            currentMode = Mode.REPLACE;
            saveMode();
            updateModeUI();
            startRecording();
        };
        mainHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD);
    }

    private void handleTouchUp() {
        mainHandler.removeCallbacks(longPressRunnable);

        if (isRecording) {
            // v5.4.1: APPEND 模式一律延遲 1s finalize（含短句）
            // 修正：短句 streamChunkTotal==0 時也要延遲，否則尾巴幾個字會被切掉
            if (currentMode == Mode.APPEND && audioStreamWs != null) {
                mainHandler.post(() -> updateStatus("收尾中..."));
                pendingFinalizeRunnable = () -> stopRecordingAndSend();
                mainHandler.postDelayed(pendingFinalizeRunnable, FINALIZE_DELAY_MS);
            } else {
                stopRecordingAndSend();
            }
        } else if (!longPressTriggered) {
            // Short tap toggle
            startRecording();
        }
    }

    // ==================== Recording ====================

    private void startRecording() {
        if (isRecording) {
            // v5.4.1: tap-toggle 停止也走延遲（和 handleTouchUp 一致）
            if (currentMode == Mode.APPEND && audioStreamWs != null) {
                mainHandler.post(() -> updateStatus("收尾中..."));
                pendingFinalizeRunnable = () -> stopRecordingAndSend();
                mainHandler.postDelayed(pendingFinalizeRunnable, FINALIZE_DELAY_MS);
            } else {
                stopRecordingAndSend();
            }
            return;
        }

        // Close any open panel
        closePanel();

        int rawBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        final int bufferSize = rawBufferSize > 0 ? rawBufferSize : SAMPLE_RATE * 2;

        try {
            // v2.x 收音改善：VOICE_RECOGNITION 是語音辨識專用來源（裝置端為 ASR 調校，
            // 距離/小聲時收音較 raw MIC 好）。若機型不支援則 fallback 回 MIC。
            AudioRecord rec;
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
                }
            } catch (Exception ve) {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
            }
            audioRecord = rec;
        } catch (SecurityException e) {
            updateStatus("需要麥克風權限");
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            updateStatus("麥克風初始化失敗");
            return;
        }

        // v2.x 收音改善：距離/小聲時開啟自動增益（若裝置支援；失敗不影響錄音）。
        try {
            int _sessionId = audioRecord.getAudioSessionId();
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl _agc =
                        android.media.audiofx.AutomaticGainControl.create(_sessionId);
                if (_agc != null) _agc.setEnabled(true);
            }
        } catch (Exception agcEx) {
            Log.w(TAG, "AGC unavailable: " + agcEx.getMessage());
        }

        pcmBuffer = new ByteArrayOutputStream();
        fullPcmBuffer = new ByteArrayOutputStream();  // v6.1: 整段音訊保留供乾淨 fallback
        streamFailed = false;
        final int myGen = utteranceGeneration.incrementAndGet();
        activeUtteranceGeneration = myGen;
        if (currentMode != Mode.APPEND) {
            completeReservedUtteranceWithoutText(myGen);
        }
        isRecording = true;

        // v6.1: 錄音期間維持螢幕常亮 → 長口述時螢幕不休眠、IME 視窗不被回收，
        //       根除「半句預覽被系統強制提交（無標點）」的元兇。停止錄音時釋放。
        if (rootView != null) rootView.setKeepScreenOn(true);

        // v5.6: 取 PARTIAL_WAKE_LOCK 保 CPU（不亮螢幕），鎖屏中 AudioRecord/WebSocket/Gemini 不中斷
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                recordingWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimonIME:Recording");
                recordingWakeLock.setReferenceCounted(false);
                recordingWakeLock.acquire(10 * 60 * 1000L);  // 10 min 安全上限
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock 取得失敗，繼續錄音", e);
        }

        audioRecord.startRecording();

        // v3.6: 停用舊串流模式（VAD 分段）
        streamingMode = false;
        prepareOnDeviceAppendPreview();

        // v4.2: 音訊串流 WebSocket（已修復文字消失 + 亂序 bug）
        if (currentMode == Mode.APPEND) {
            startAudioStreamWs(myGen);
        }

        updateStatus("🔴 錄音中...");
        btnMic.setBackgroundColor(getResources().getColor(R.color.mic_active, null));
        if (btnMic instanceof Button) ((Button) btnMic).setText("⏹");

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            int silentBytes = 0;           // 連續靜音 byte 計數
            final int SILENCE_THRESHOLD = 800;  // 16-bit PCM RMS 門檻（靜音偵測）
            final int BYTES_PER_MS = 32; // 16000*2/1000
            final int SILENCE_MS_TO_SPLIT = 500;
            final int SILENCE_BYTES_TO_SPLIT = SILENCE_MS_TO_SPLIT * BYTES_PER_MS; // 16000 bytes = 500ms
            final int MIN_CHUNK_BYTES = 32000;  // 最小 1 秒才送（避免 Whisper 幻覺）
            final int MAX_CHUNK_BYTES = 48000;  // v6.14: 最大 1.5 秒強制送，讓 GB10 在說話中先解碼
            // v5.4: 跳過前 400ms 音訊（避免按鈕點擊聲干擾 STT）
            final int SKIP_INITIAL_BYTES = 12800; // 400ms @ 16kHz 16-bit mono
            int totalBytesRead = 0;

            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    totalBytesRead += read;
                    // v5.4: 前 400ms 不寫入 buffer（丟掉點擊聲）
                    if (totalBytesRead <= SKIP_INITIAL_BYTES) {
                        continue;
                    }
                    pcmBuffer.write(buffer, 0, read);
                    // v6.1: APPEND 串流模式下，pcmBuffer 會每送一個 chunk 就 reset()，
                    //       fullPcmBuffer 不 reset → 保留整段音訊供失敗時乾淨重轉錄。
                    //       封頂 ~10 分鐘防無界成長（超過則停止累積，fallback 退化為前 10 分鐘，極端罕見）。
                    if (currentMode == Mode.APPEND && fullPcmBuffer != null
                            && fullPcmBuffer.size() < MAX_FULL_PCM_BYTES) {
                        fullPcmBuffer.write(buffer, 0, read);
                    }

                    if (currentMode == Mode.APPEND && onDeviceAppendPreviewEnabled) {
                        feedOnDeviceAppendPreview(buffer, read);
                    }

                    // v4.3: 音量偵測 — 依語音停頓分段，不依固定秒數
                    if (currentMode == Mode.APPEND && audioStreamActive && audioStreamWs != null) {
                        // 計算 RMS 音量
                        long sumSq = 0;
                        for (int i = 0; i < read - 1; i += 2) {
                            short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                            sumSq += (long) sample * sample;
                        }
                        double rms = Math.sqrt(sumSq / (double) (read / 2));

                        if (rms < SILENCE_THRESHOLD) {
                            silentBytes += read;
                        } else {
                            silentBytes = 0;
                        }

                        int bufSize = pcmBuffer.size();
                        // 送出條件：(停頓 ≥500ms deterministic 且累積 ≥1s) 或 (累積 ≥1.5s 強制送)
                        boolean pauseDetected = silentBytes >= SILENCE_BYTES_TO_SPLIT && bufSize >= MIN_CHUNK_BYTES;
                        boolean forceFlush = bufSize >= MAX_CHUNK_BYTES;

                        if (pauseDetected || forceFlush) {
                            byte[] chunkData = pcmBuffer.toByteArray();
                            pcmBuffer.reset();
                            silentBytes = 0;
                            audioStreamWs.send(ByteString.of(chunkData, 0, chunkData.length));
                            streamChunkTotal++;
                            Log.i(TAG, "[AudioStream] chunk #" + streamChunkTotal
                                    + " (" + chunkData.length + "B, "
                                    + (pauseDetected ? "pause" : "maxlen") + ")");
                        }
                    }
                }
            }
        }, "AudioRecorder");
        recordingThread.start();
    }

    /**
     * v4.1: 開啟音訊串流 WebSocket 連線。
     * APPEND 模式下，每 2 秒送 PCM chunk → Server Groq Whisper + Moonshot K2 → 即時回傳文字。
     */
    private void startAudioStreamWs(final int myGen) {
        streamedChunks.clear();
        streamChunkTotal = 0;
        audioStreamActive = false;

        // v5.4: 錄音開始前抓取游標上下文（送給 Gemini 當校正語境）
        String ctxBefore = "";
        String ctxAfter = "";
        try {
            InputConnection icCtx = getCurrentInputConnection();
            if (icCtx != null) {
                CharSequence b = icCtx.getTextBeforeCursor(100, 0);
                CharSequence a = icCtx.getTextAfterCursor(50, 0);
                if (b != null) ctxBefore = b.toString();
                if (a != null) ctxAfter = a.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "取得游標上下文失敗: " + e.getMessage());
        }
        final String contextBefore = ctxBefore;
        final String contextAfter = ctxAfter;

        String serverUrl = getServerUrl();
        String wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws/stream-audio";

        Request wsReq = new Request.Builder().url(wsUrl).build();
        audioStreamWs = httpClient.newWebSocket(wsReq, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                // Send auth + context
                String auth = getAuthPassword();
                try {
                    JSONObject authMsg = new JSONObject();
                    authMsg.put("type", "auth");
                    authMsg.put("password", auth != null ? auth : "");
                    if (!contextBefore.isEmpty()) authMsg.put("context_before", contextBefore);
                    if (!contextAfter.isEmpty()) authMsg.put("context_after", contextAfter);
                    ws.send(authMsg.toString());
                } catch (Exception e) {
                    ws.send("{\"type\":\"auth\",\"password\":\"" + (auth != null ? auth : "") + "\"}");
                }
                if (myGen == utteranceGeneration.get()) {
                    audioStreamActive = true;
                }
                Log.i(TAG, "[AudioStream] WebSocket 已連線，已送出認證" +
                        (contextBefore.isEmpty() ? "" : " (含上下文)"));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");

                    if ("auth_ok".equals(type)) {
                        Log.i(TAG, "[AudioStream] 認證成功");

                    } else if ("auth_fail".equals(type)) {
                        Log.e(TAG, "[AudioStream] 認證失敗");
                        if (myGen == utteranceGeneration.get()) {
                            streamFailed = true;
                            audioStreamActive = false;
                            audioStreamWs = null;
                        }
                        mainHandler.post(() -> {
                            if (myGen != utteranceGeneration.get()) {
                                httpFallbackFullAudio(myGen);
                            } else if (isRecording) {
                                updateStatus("🔴 錄音中…（連線中斷，本地暫存）");
                            } else {
                                updateStatus("整理中…");
                                httpFallbackFullAudio(myGen);
                            }
                        });

                    } else if ("chunk".equals(type)) {
                        if (myGen != utteranceGeneration.get()) return;
                        String chunkText = json.optString("text", "");
                        int idx = json.optInt("index", -1);
                        if (!chunkText.isEmpty()) {
                            streamedChunks.add(chunkText);
                            // v6.1: 不再寫進輸入框（移除 setComposingText）。串流預覽只顯示在鍵盤自己的
                            //       previewText 預覽列 → 輸入框在 final 之前保持乾淨、空無一物，
                            //       螢幕休眠/失焦時系統也沒有 composing text 可倒。
                            StringBuilder composing = new StringBuilder();
                            for (String c : streamedChunks) composing.append(c);
                            String live = composing.toString();
                            String tail = live.length() > 28 ? "…" + live.substring(live.length() - 28) : live;
                            final int liveLen = live.length();
                            // v6.4: 手機端 SenseVoice 預覽可用時，WS chunk 不再覆蓋預覽列；
                            //       WS 仍持續上傳/收集，final commit 路徑完全不變。
                            if (!onDeviceAppendPreviewEnabled) {
                                updatePreviewStrip("🎧 " + tail);
                            }
                            mainHandler.post(() -> updateStatus("聆聽中…（" + liveLen + " 字）"));
                        }
                        Log.i(TAG, "[AudioStream] chunk#" + idx + " 回傳: '" + chunkText + "'");

                    } else if ("final".equals(type)) {
                        String finalText = json.optString("text", "");
                        if (myGen == utteranceGeneration.get()) {
                            streamedChunks.clear();
                            updatePreviewStrip("");
                        }

                        mainHandler.post(() -> {
                            if (!finalText.isEmpty()) {
                                // v6.1: 一次性提交最終（雲端已拼接＋校正）結果。全程未動 composing text，故直接 commitText。
                                //       generation 守衛：晚到的 onFailure fallback 會被擋，不會重複提交。
                                // v6.17: 走 commitFinalText 以支援不接受 commitText 的 app（Termux/Gemini）。
                                if (reserveUtteranceGeneration(myGen)) {
                                    completeReservedUtteranceWithText(myGen, finalText);
                                }
                            } else {
                                // 伺服器最終結果為空 → 用保留的整段音訊做一次乾淨重轉錄（有標點），
                                //       而非把無標點串流文字倒進輸入框。
                                Log.w(TAG, "[AudioStream] final 為空，改用整段音訊 HTTP fallback");
                                updateStatus("整理中…");
                                httpFallbackFullAudio(myGen);
                            }
                        });
                        Log.i(TAG, "[AudioStream] 最終文字: '" + truncate(finalText, 50) + "'");

                    } else if ("error".equals(type)) {
                        String msg = json.optString("message", "unknown");
                        Log.e(TAG, "[AudioStream] 伺服器錯誤: " + msg);
                        if (myGen == utteranceGeneration.get()) {
                            streamFailed = true;
                            audioStreamActive = false;
                            audioStreamWs = null;
                        }
                        mainHandler.post(() -> {
                            if (myGen != utteranceGeneration.get()) {
                                httpFallbackFullAudio(myGen);
                            } else if (isRecording) {
                                updateStatus("🔴 錄音中…（連線中斷，本地暫存）");
                            } else {
                                updateStatus("整理中…");
                                httpFallbackFullAudio(myGen);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[AudioStream] 訊息解析錯誤", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.w(TAG, "[AudioStream] WebSocket 連線失敗（改走整段音訊 fallback）", t);
                if (myGen == utteranceGeneration.get()) {
                    streamFailed = true;
                    audioStreamActive = false;
                    audioStreamWs = null;
                    streamedChunks.clear();
                    if (!onDeviceAppendPreviewEnabled || !isRecording) {
                        updatePreviewStrip("");
                    }
                }
                // v6.1: 不再把已收到的「無標點串流文字」倒進輸入框（那正是 Simon 要根除的半成品）。
                //   - 仍在錄音：什麼都不提交，繼續本地累積整段音訊；停止時用整段走乾淨 HTTP。
                //   - 已停止（finalize 階段才斷）：立刻用整段保留音訊重轉錄（有標點、走校正）。
                mainHandler.post(() -> {
                    if (myGen != utteranceGeneration.get()) {
                        httpFallbackFullAudio(myGen);
                    } else if (isRecording) {
                        updateStatus("🔴 錄音中…（連線中斷，本地暫存）");
                    } else {
                        updateStatus("整理中…");
                        httpFallbackFullAudio(myGen);
                    }
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "[AudioStream] WebSocket 已關閉: " + code + " " + reason);
                if (myGen == utteranceGeneration.get()) {
                    audioStreamActive = false;
                    audioStreamWs = null;
                }
            }
        });
    }

    /**
     * 串流模式錄音：VAD 分段 → SenseVoice 辨識 → stream-chunk 上傳
     * VAD 的 windowSize=512 samples，所以每次讀 512 samples (1024 bytes)
     */
    private void recordWithVAD() {
        final int vadWindowSize = 512; // must match VAD windowSize
        final int bytesPerWindow = vadWindowSize * 2; // 16-bit PCM
        byte[] buffer = new byte[bytesPerWindow];

        while (isRecording) {
            int read = audioRecord.read(buffer, 0, bytesPerWindow);
            if (read <= 0) continue;

            // 同時寫入 pcmBuffer（作為 fallback 用）
            pcmBuffer.write(buffer, 0, read);

            // byte[] → float[] 供 VAD 使用
            int numSamples = read / 2;
            float[] floatSamples = new float[numSamples];
            for (int i = 0; i < numSamples; i++) {
                short sample = (short) ((buffer[i * 2] & 0xFF) | (buffer[i * 2 + 1] << 8));
                floatSamples[i] = sample / 32768.0f;
            }

            // 餵入 VAD + SenseVoice（回調在 segmentExecutor 執行緒）
            localSTT.feedAudioChunk(floatSamples, segmentText -> {
                // SenseVoice 辨識完一段 → 英文映射 → 上傳 chunk
                String mapped = englishMapper.apply(segmentText);
                synchronized (streamChunkTexts) {
                    streamChunkTexts.add(mapped);
                }
                streamingUpload.sendChunk(mapped);
                Log.d(TAG, "Stream chunk: '" + segmentText + "' -> '" + mapped + "'");
            });
        }
    }

    private void stopRecordingAndSend() {
        if (!isRecording) return;
        isRecording = false;
        onDeviceAppendPreviewEnabled = false;

        // v6.1: 停止錄音 → 釋放螢幕常亮、清掉鍵盤預覽列（輸入框本來就沒被碰過）
        if (rootView != null) rootView.setKeepScreenOn(false);
        updatePreviewStrip("");

        final boolean wasStreaming = streamingMode;
        final int myGen = activeUtteranceGeneration;
        streamingMode = false;

        try {
            audioRecord.stop();
            audioRecord.release();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder", e);
        }

        // v5.6: 釋放 WakeLock
        try {
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                recordingWakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock 釋放失敗", e);
        } finally {
            recordingWakeLock = null;
        }

        try {
            recordingThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        byte[] pcmData = pcmBuffer.toByteArray();
        rememberFullPcmForGeneration(myGen, pcmData);
        pcmBuffer = null;

        mainHandler.post(() -> {
            btnMic.setBackgroundColor(getResources().getColor(R.color.mic_idle, null));
            if (btnMic instanceof Button) ((Button) btnMic).setText("🎤");
            updateStatus("辨識中...");
        });

        // v6.20: 資料夾語音命名攔截
        if (folderNamingMode) {
            folderNamingMode = false;
            final byte[] namePcm = pcmData;
            final int gen = fieldGeneration;
            if (namePcm.length >= 3200 && localSTTReady && localSTT != null) {
                new Thread(() -> {
                    String name = localSTT.recognize(namePcm, SAMPLE_RATE);
                    if (name != null) name = name.trim();
                    if (name == null || name.isEmpty()) name = commandsHelper.uniqueGroupName();
                    final String folderName = name;
                    mainHandler.post(() -> {
                        if (gen != fieldGeneration) return;
                        commandsHelper.addGroup(folderName);
                        currentCmdGroup = folderName;
                        showPanel(Panel.COMMANDS);
                        updateStatus("已建立「" + folderName + "」");
                    });
                }, "FolderName-STT").start();
            } else {
                String folderName = commandsHelper.uniqueGroupName();
                commandsHelper.addGroup(folderName);
                currentCmdGroup = folderName;
                mainHandler.post(() -> {
                    showPanel(Panel.COMMANDS);
                    updateStatus("已建立「" + folderName + "」");
                });
            }
            return;
        }

        // v6.1: 串流中途斷線（streamFailed）→ audioStreamWs 已 null。用整段保留音訊走乾淨 HTTP
        //       （有標點、走伺服器校正），而非 pcmData 殘片（只剩斷線後那段）。
        if (currentMode == Mode.APPEND && streamFailed) {
            streamedChunks.clear();
            httpFallbackFullAudio(myGen);
            return;
        }

        // === v4.4: 音訊串流收尾必須在 "太短" 檢查之前 ===
        // 修正 bug: pcmBuffer 在每次送 WS chunk 時 reset()，所以停止錄音時殘餘可能 < 3200
        // 但此時 WS 已經送了 N 個 chunks，不能 cancel()，必須 finalize
        if (currentMode == Mode.APPEND && audioStreamWs != null && streamChunkTotal > 0) {
            // v4.4.2: 送出所有殘餘音訊（不管多短），避免末尾 1-2 字被裁切
            if (pcmData.length > 0) {
                audioStreamWs.send(ByteString.of(pcmData, 0, pcmData.length));
                Log.i(TAG, "[AudioStream] 送出剩餘音訊 (" + pcmData.length + " bytes)");
            }
            // Send finalize command
            audioStreamWs.send("{\"type\":\"finalize\"}");
            Log.i(TAG, "[AudioStream] 已送出 finalize，共 " + streamChunkTotal + " chunks");
            // The final result will come via onMessage callback — don't send via HTTP
            mainHandler.post(() -> updateStatus("整理中..."));
            audioStreamWs = null;
            audioStreamActive = false;
            return;
        }

        if (pcmData.length < 3200) {
            if (wasStreaming) streamingUpload.cancelSession();
            if (audioStreamWs != null) {
                audioStreamWs.cancel();
                audioStreamWs = null;
                audioStreamActive = false;
            }
            // v6.1: 不再有 composing text 需清理（輸入框全程乾淨），只清狀態
            streamedChunks.clear();
            if (currentMode == Mode.APPEND) {
                completeReservedUtteranceWithoutText(myGen);
            }
            mainHandler.post(() -> {
                updatePreviewStrip("");
                updateStatus("錄音太短，請再試一次");
            });
            return;
        }

        // === v4.1: 音訊串流收尾 (APPEND mode, 0 chunks 已送但殘餘 ≥ 3200) ===
        if (currentMode == Mode.APPEND && audioStreamWs != null) {
            // Send remaining audio in buffer (less than 2 seconds)
            if (pcmData.length > 0) {
                audioStreamWs.send(ByteString.of(pcmData, 0, pcmData.length));
                Log.i(TAG, "[AudioStream] 送出剩餘音訊 (" + pcmData.length + " bytes)");
            }
            // Send finalize command
            audioStreamWs.send("{\"type\":\"finalize\"}");
            Log.i(TAG, "[AudioStream] 已送出 finalize，共 " + streamChunkTotal + " chunks");
            // The final result will come via onMessage callback — don't send via HTTP
            mainHandler.post(() -> updateStatus("整理中..."));
            audioStreamWs = null;
            audioStreamActive = false;
            return;
        }

        // v6.1: WS 中途斷線已由上方 streamFailed 早退處理；此處僅清殘留狀態（無 composing text）
        if (!streamedChunks.isEmpty()) {
            streamedChunks.clear();
            mainHandler.post(() -> updatePreviewStrip(""));
        }

        // === 舊串流模式收尾 ===
        if (wasStreaming && streamingUpload.isStreamingSupported()) {
            finalizeStreamingSession(pcmData, myGen);
            return;
        }

        // === 非串流模式：本機 STT → 文字上傳 ===
        // v4.0: 只有 REPLACE 模式用本機 STT（需要游標上下文）
        // APPEND/SPELL/TRANSLATE 一律傳音訊到 Server（Groq Whisper 品質遠超手機 SenseVoice）
        if (localSTTReady && currentMode == Mode.REPLACE) {
            // 在主執行緒先取游標前後文字（背景執行緒拿不到 InputConnection）
            InputConnection icNow = getCurrentInputConnection();
            String beforeCursor = "";
            String afterCursor = "";
            if (icNow != null) {
                CharSequence before = icNow.getTextBeforeCursor(50, 0);
                CharSequence after = icNow.getTextAfterCursor(50, 0);
                beforeCursor = before != null ? before.toString() : "";
                afterCursor = after != null ? after.toString() : "";
            }
            final String bc = beforeCursor;
            final String ac = afterCursor;
            final Mode modeNow = currentMode;

            new Thread(() -> {
                long t0 = System.currentTimeMillis();
                String spokenText = localSTT.recognize(pcmData, SAMPLE_RATE);
                long sttMs = System.currentTimeMillis() - t0;
                Log.i(TAG, "[LocalSTT] " + modeNow + " 辨識耗時 " + sttMs + "ms: '" + spokenText + "'");

                if (spokenText != null && !spokenText.isEmpty()) {
                    // 英文映射
                    spokenText = englishMapper.apply(spokenText);

                    final String finalText = spokenText;
                    mainHandler.post(() -> updateStatus("辨識(" + sttMs + "ms): " + truncate(finalText, 15)));
                    if (modeNow == Mode.REPLACE) {
                        // v6.20 R2: AI 素材已備 → 走 /v1/ai-command 並「插入」答案（絕不刪除游標周圍）
                        if (aiContextText != null) {
                            sendAiCommand(finalText, aiContextText);
                        } else {
                            sendTextReplace(finalText, bc, ac, myGen);
                        }
                    } else {
                        sendTextProcess(finalText, modeNow, myGen);
                    }
                } else {
                    // 本機 STT 失敗 → fallback 上傳音訊
                    mainHandler.post(() -> updateStatus("本機辨識無結果，上傳中..."));
                    byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
                    if (modeNow == Mode.REPLACE && aiContextText != null) {
                        sendAiCommandAudio(wavData, aiContextText);
                    } else {
                        sendToWTI(wavData, modeNow, false, myGen);
                    }
                }
            }, "LocalSTT-Recognize").start();
            return;
        }

        // fallback: 本機 STT 未就緒 → 上傳音訊（舊流程）
        byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
        if (currentMode == Mode.REPLACE && aiContextText != null) {
            sendAiCommandAudio(wavData, aiContextText);
        } else {
            sendToWTI(wavData, currentMode, myGen);
        }
    }

    /**
     * v6.4: 啟用 APPEND 手機端即時預覽。只影響 previewText，不參與 final/commit。
     */
    private void prepareOnDeviceAppendPreview() {
        synchronized (onDeviceAppendPreviewLock) {
            onDeviceAppendPreviewSegments.clear();
        }
        onDeviceAppendPreviewEnabled = currentMode == Mode.APPEND
                && localSTTReady
                && localSTT != null
                && localSTT.isStreamingReady();
        if (onDeviceAppendPreviewEnabled) {
            localSTT.resetStreamingState();
            Log.i(TAG, "[OnDevicePreview] enabled for APPEND");
        } else if (currentMode == Mode.APPEND) {
            Log.i(TAG, "[OnDevicePreview] unavailable; WS chunk preview remains fallback");
        }
    }

    /**
     * v6.4: 錄音執行緒複製 PCM 給 LocalSTT VAD；SenseVoice 解碼在 LocalSTTHelper 背景緒完成。
     */
    private void feedOnDeviceAppendPreview(byte[] pcm, int byteCount) {
        if (localSTT == null || !localSTT.isStreamingReady() || byteCount < 2) return;

        int numSamples = byteCount / 2;
        float[] floatSamples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            short sample = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            floatSamples[i] = sample / 32768.0f;
        }

        try {
            localSTT.feedAudioChunk(floatSamples, segmentText -> {
                try {
                    if (!isRecording || !onDeviceAppendPreviewEnabled) return;
                    String mapped = englishMapper.apply(segmentText != null ? segmentText.trim() : "");
                    if (mapped == null || mapped.isEmpty()) return;

                    String live;
                    synchronized (onDeviceAppendPreviewLock) {
                        onDeviceAppendPreviewSegments.add(mapped);
                        StringBuilder sb = new StringBuilder();
                        String prev = "";
                        for (String segment : onDeviceAppendPreviewSegments) {
                            String seg = dedupOverlapHead(prev, segment, 6);
                            sb.append(seg);
                            prev = sb.toString();
                        }
                        live = sb.toString();
                    }

                    String tail = live.length() > 28 ? "…" + live.substring(live.length() - 28) : live;
                    int liveLen = live.length();
                    updatePreviewStrip("📱 " + tail);
                    mainHandler.post(() -> updateStatus("聆聽中…（本機預覽 " + liveLen + " 字）"));
                    Log.d(TAG, "[OnDevicePreview] segment: '" + mapped + "'");
                } catch (Throwable t) {
                    onDeviceAppendPreviewEnabled = false;
                    Log.w(TAG, "[OnDevicePreview] disabled after callback error", t);
                }
            });
        } catch (Throwable t) {
            onDeviceAppendPreviewEnabled = false;
            Log.w(TAG, "[OnDevicePreview] disabled after feed error", t);
        }
    }

    private static String dedupOverlapHead(String prev, String seg, int maxWindow) {
        if (prev == null || prev.isEmpty() || seg == null || seg.isEmpty()) return seg == null ? "" : seg;
        int max = Math.min(maxWindow, Math.min(prev.length(), seg.length()));
        for (int k = max; k > 0; k--) {
            if (prev.regionMatches(prev.length() - k, seg, 0, k)) return seg.substring(k);
        }
        return seg;
    }

    /**
     * 串流模式收尾：
     * 1. flush VAD 殘留音訊 → SenseVoice 辨識最後一段 → 上傳最後 chunk
     * 2. 等待 pending segments 完成
     * 3. POST stream-finalize → 取得 LLM 語義校正結果
     * 4. 若 finalize 失敗 → fallback 到整段辨識
     */
    private void finalizeStreamingSession(byte[] pcmData, int gen) {
        new Thread(() -> {
            // 1. Flush VAD — 處理最後殘留的語音段
            localSTT.flushVad(segmentText -> {
                String mapped = englishMapper.apply(segmentText);
                synchronized (streamChunkTexts) {
                    streamChunkTexts.add(mapped);
                }
                streamingUpload.sendChunk(mapped);
                Log.d(TAG, "Stream flush chunk: '" + segmentText + "' -> '" + mapped + "'");
            });

            // 2. 等待所有 SenseVoice 段落處理完成
            localSTT.waitForPendingSegments();

            int totalChunks = streamingUpload.getChunkCount();
            Log.i(TAG, "Streaming session ending. Total chunks: " + totalChunks);

            if (totalChunks == 0) {
                // 沒有任何 chunk（可能全部太短被過濾）→ fallback 整段辨識
                Log.w(TAG, "No chunks sent, falling back to single-shot recognition");
                fallbackSingleShot(pcmData, gen);
                return;
            }

            // 3. Finalize — 等伺服器整體 LLM 語義校正
            mainHandler.post(() -> updateStatus("整理中..."));
            streamingUpload.finalize(new StreamingUploadHelper.FinalizeCallback() {
                @Override
                public void onSuccess(String finalText) {
                    Log.i(TAG, "Stream finalize success: '" + finalText + "'");
                    streamingUpload.endSession();
                    mainHandler.post(() -> {
                        if (!finalText.isEmpty()) {
                            // v6.17: 走 commitFinalText 以支援 Termux/Gemini 等不接受 commitText 的 app。
                            if (reserveUtteranceGeneration(gen)) {
                                completeReservedUtteranceWithText(gen, finalText);
                            }
                        } else {
                            String localConcat;
                            synchronized (streamChunkTexts) {
                                localConcat = String.join("", streamChunkTexts).trim();
                            }
                            if (!localConcat.isEmpty() && reserveUtteranceGeneration(gen)) {
                                completeOfflineAppendWithLocalCorrection(
                                        gen, localConcat, "stream finalize empty response");
                            } else {
                                completeReservedUtteranceWithoutText(gen);
                                updateStatus("未辨識到文字");
                            }
                        }
                    });
                }

                @Override
                public void onError(String error) {
                    Log.w(TAG, "Stream finalize failed: " + error);
                    streamingUpload.endSession();
                    if ("STREAMING_NOT_SUPPORTED".equals(error)) {
                        // 伺服器不支援串流 → fallback + 之後不再嘗試串流
                        Log.w(TAG, "Server does not support streaming, disabling");
                        fallbackSingleShot(pcmData, gen);
                    } else {
                        // 其他錯誤 → fallback 用本地收集的文字
                        String localConcat;
                        synchronized (streamChunkTexts) {
                            localConcat = String.join("", streamChunkTexts);
                        }
                        if (!localConcat.isEmpty()) {
                            // 用本地拼接的文字送 process-text
                            mainHandler.post(() -> updateStatus("串流整理失敗，使用本地結果"));
                            sendTextProcess(localConcat, Mode.APPEND, gen);
                        } else {
                            fallbackSingleShot(pcmData, gen);
                        }
                    }
                }
            });
        }, "StreamFinalize").start();
    }

    /**
     * Fallback：整段 PCM → SenseVoice 單次辨識 → process-text
     */
    private void fallbackSingleShot(byte[] pcmData, int gen) {
        if (localSTTReady) {
            long t0 = System.currentTimeMillis();
            String spokenText = localSTT.recognize(pcmData, SAMPLE_RATE);
            long sttMs = System.currentTimeMillis() - t0;
            if (spokenText != null && !spokenText.isEmpty()) {
                spokenText = englishMapper.apply(spokenText);
                final String finalText = spokenText;
                mainHandler.post(() -> updateStatus("辨識(" + sttMs + "ms): " + truncate(finalText, 15)));
                sendTextProcess(finalText, Mode.APPEND, gen);
            } else {
                mainHandler.post(() -> updateStatus("本機辨識無結果，上傳中..."));
                byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
                sendToWTI(wavData, Mode.APPEND, false, gen);
            }
        } else {
            byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
            sendToWTI(wavData, Mode.APPEND, false, gen);
        }
    }

    // ==================== Network ====================

    private void sendToWTI(byte[] wavData, Mode mode) {
        sendToWTI(wavData, mode, mode == Mode.APPEND, 0);
    }

    private void sendToWTI(byte[] wavData, Mode mode, int gen) {
        sendToWTI(wavData, mode, mode == Mode.APPEND, gen);
    }

    private void sendToWTI(byte[] wavData, Mode mode, boolean allowOfflineAppendFallback) {
        sendToWTI(wavData, mode, allowOfflineAppendFallback, 0);
    }

    private void sendToWTI(byte[] wavData, Mode mode, boolean allowOfflineAppendFallback, int gen) {
        // v6.20: guard against null/empty audio
        if (wavData == null || wavData.length == 0) {
            if (mode == Mode.APPEND) {
                completeReservedUtteranceWithoutText(gen);
            }
            mainHandler.post(() -> updateStatus("沒有可用的音訊，請再試一次"));
            return;
        }

        // v6.20 R2: any REPLACE audio path while AI material is armed → /v1/ai-command (insert, never delete)
        if (mode == Mode.REPLACE && aiContextText != null) {
            sendAiCommandAudio(wavData, aiContextText);
            return;
        }

        String serverUrl = getServerUrl();

        String endpoint;
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav",
                        RequestBody.create(wavData, MediaType.parse("audio/wav")));

        switch (mode) {
            case REPLACE:
                endpoint = "/v1/replace";
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    CharSequence before = ic.getTextBeforeCursor(50, 0);
                    CharSequence after = ic.getTextAfterCursor(50, 0);
                    bodyBuilder.addFormDataPart("before_cursor",
                            before != null ? before.toString() : "");
                    bodyBuilder.addFormDataPart("after_cursor",
                            after != null ? after.toString() : "");
                }
                break;
            case SPELL:
                endpoint = "/v1/audio/transcriptions";
                bodyBuilder.addFormDataPart("spell_mode", "true");
                break;
            case TRANSLATE:
                endpoint = "/v1/audio/transcriptions";
                bodyBuilder.addFormDataPart("target_language", "en");
                break;
            default:
                endpoint = "/v1/audio/transcriptions";
                break;
        }

        // Add auth if configured
        String auth = getAuthPassword();

        RequestBody body = bodyBuilder.build();
        Request.Builder reqBuilder = new Request.Builder()
                .url(serverUrl + endpoint)
                .post(body);

        if (auth != null && !auth.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + auth);
        }

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "WTI request failed", e);
                if (allowOfflineAppendFallback && mode == Mode.APPEND) {
                    runOfflineFullAudioFallback(gen, "HTTP request failed: " + e.getMessage(), false);
                } else {
                    if (mode == Mode.APPEND) {
                        completeReservedUtteranceWithoutText(gen);
                    }
                    mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        if (allowOfflineAppendFallback && mode == Mode.APPEND) {
                            runOfflineFullAudioFallback(gen, "HTTP response " + response.code(), false);
                        } else {
                            if (mode == Mode.APPEND) {
                                completeReservedUtteranceWithoutText(gen);
                            }
                            mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                        }
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    if (allowOfflineAppendFallback && mode == Mode.APPEND
                            && json.optString("text", "").trim().isEmpty()) {
                        runOfflineFullAudioFallback(gen, "HTTP response text empty", false);
                    } else {
                        handleWTIResponse(json, mode, gen);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    if (allowOfflineAppendFallback && mode == Mode.APPEND) {
                        runOfflineFullAudioFallback(gen, "HTTP response parse error: " + e.getMessage(), false);
                    } else {
                        if (mode == Mode.APPEND) {
                            completeReservedUtteranceWithoutText(gen);
                        }
                        mainHandler.post(() -> updateStatus("解析錯誤"));
                    }
                }
            }
        });
    }

    /**
     * 本機 STT 完成後，只傳文字到伺服器 /v1/replace-text 做 LLM 換字推理。
     */
    /**
     * v2.7: 本機 STT 完成後，傳文字到 /v1/process-text 做校正/拼字/翻譯。
     */
    private void sendTextProcess(String spokenText, Mode mode) {
        sendTextProcess(spokenText, mode, 0);
    }

    private void sendTextProcess(String spokenText, Mode mode, int gen) {
        sendTextProcess(spokenText, mode, gen, false);
    }

    private void sendTextProcess(String spokenText, Mode mode, int gen, boolean utteranceAlreadyReserved) {
        String serverUrl = getServerUrl();
        if (mode == Mode.APPEND && gen > 0 && !utteranceAlreadyReserved) {
            startAppendProcessTextRace(spokenText, gen);
        }

        String modeStr;
        switch (mode) {
            case SPELL: modeStr = "spell"; break;
            case TRANSLATE: modeStr = "translate"; break;
            default: modeStr = "append"; break;
        }

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("text", spokenText)
                .addFormDataPart("mode", modeStr);

        if (mode == Mode.TRANSLATE) {
            bodyBuilder.addFormDataPart("target_language", "en");
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(serverUrl + "/v1/process-text")
                .post(bodyBuilder.build());

        String auth = getAuthPassword();
        if (auth != null && !auth.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + auth);
        }

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Process-text request failed", e);
                if (mode == Mode.APPEND) {
                    completeAppendProcessTextFailureWithOfflineFallback(
                            gen,
                            spokenText,
                            "Process-text request failed: " + e.getMessage(),
                            utteranceAlreadyReserved,
                            "連線失敗: " + e.getMessage());
                    return;
                }
                mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        if (mode == Mode.APPEND) {
                            completeAppendProcessTextFailureWithOfflineFallback(
                                    gen,
                                    spokenText,
                                    "server error " + response.code(),
                                    utteranceAlreadyReserved,
                                    "伺服器錯誤: " + response.code());
                            return;
                        }
                        mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    String text = json.optString("text", "").trim();
                    mainHandler.post(() -> {
                        if (mode == Mode.APPEND && gen > 0) {
                            if (!text.isEmpty()) {
                                if (!utteranceAlreadyReserved && !reserveUtteranceGeneration(gen)) {
                                    return;
                                }
                                completeReservedUtteranceWithText(gen, text);
                                return;
                            }
                            completeAppendProcessTextFailureWithOfflineFallback(
                                    gen,
                                    spokenText,
                                    "process-text empty response",
                                    utteranceAlreadyReserved,
                                    "未辨識到文字");
                            return;
                        }
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null && !text.isEmpty()) {
                            settlePendingCorrectionCapture();
                            TextSnapshot beforeSnapshot = getCurrentTextSnapshotSafely();
                            if (commitTextProgrammatically(ic, text)) {
                                recordVoiceCommit(text, beforeSnapshot);
                            }
                            String prefix;
                            switch (mode) {
                                case SPELL: prefix = "拼字: "; break;
                                case TRANSLATE: prefix = "翻譯: "; break;
                                default: prefix = ""; break;
                            }
                            updateStatus(prefix + truncate(text, 20));
                        } else {
                            updateStatus("未辨識到文字");
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing process-text response", e);
                    if (mode == Mode.APPEND) {
                        completeAppendProcessTextFailureWithOfflineFallback(
                                gen,
                                spokenText,
                                "process-text parse error: " + e.getMessage(),
                                utteranceAlreadyReserved,
                                "解析錯誤");
                        return;
                    }
                    mainHandler.post(() -> updateStatus("解析錯誤"));
                }
            }
        });
    }

    private void startAppendProcessTextRace(String spokenText, int gen) {
        final String localText = spokenText == null ? "" : spokenText.trim();
        if (localText.isEmpty() || mainHandler == null) return;

        final AtomicReference<String> onDeviceResult = new AtomicReference<>(null);
        new Thread(() -> {
            try {
                OnDeviceCorrectionEngine current = onDeviceCorrection;
                if (current == null || !current.isCorrectorReady()) return;
                String corrected = current.correct(localText, null);
                if (corrected == null || corrected.trim().isEmpty()) return;
                onDeviceResult.set(corrected.trim());
            } catch (Throwable t) {
                Log.w(TAG, "[AppendRace] on-device correction failed", t);
            }
        }, "AppendRace-OnDevice").start();

        Runnable previous = serverWaitBudgetCallbacks.remove(gen);
        if (previous != null) {
            mainHandler.removeCallbacks(previous);
        }

        Runnable budgetCallback = () -> {
            serverWaitBudgetCallbacks.remove(gen);
            if (committedGenerations.containsKey(gen)) return;
            String fastText = onDeviceResult.get();
            if (fastText == null || fastText.isEmpty()) return;
            if (!reserveUtteranceGeneration(gen)) return;
            completeReservedUtteranceWithText(gen, fastText);
            updateStatus("快速完成（未潤稿）: " + truncate(fastText, 20));
        };
        serverWaitBudgetCallbacks.put(gen, budgetCallback);
        mainHandler.postDelayed(budgetCallback, SERVER_WAIT_BUDGET_MS);
    }

    private void completeAppendProcessTextFailureWithOfflineFallback(
            int gen,
            String spokenText,
            String reason,
            boolean utteranceAlreadyReserved,
            String noLocalTextStatus) {
        String localText = spokenText == null ? "" : spokenText.trim();
        if (gen > 0 && !localText.isEmpty()) {
            if (utteranceAlreadyReserved || reserveUtteranceGeneration(gen)) {
                completeOfflineAppendWithLocalCorrection(gen, localText, reason);
            }
            return;
        }
        completeReservedUtteranceWithoutText(gen);
        mainHandler.post(() -> updateStatus(noLocalTextStatus));
    }

    private void completeOfflineAppendWithLocalCorrection(int gen, String spokenText, String reason) {
        final String localText = spokenText == null ? "" : spokenText.trim();
        if (gen <= 0 || localText.isEmpty()) {
            Log.w(TAG, "[OfflineCorrection] no local text after process-text failure: " + reason);
            completeReservedUtteranceWithoutText(gen);
            mainHandler.post(() -> updateStatus("離線校正無本機文字"));
            return;
        }

        mainHandler.post(() -> updateStatus("離線校正中…"));
        new Thread(() -> {
            String corrected = TimeoutWall.runWithBudget(() -> {
                OnDeviceCorrectionEngine current = onDeviceCorrection;
                if (current == null || !current.isCorrectorReady()) return null;
                return current.correct(localText, null);
            }, OFFLINE_CORRECTION_FAILED_SENTINEL, OFFLINE_CORRECTION_TIMEOUT_MS);

            if (!OFFLINE_CORRECTION_FAILED_SENTINEL.equals(corrected)
                    && corrected != null
                    && !corrected.trim().isEmpty()) {
                final String offlineText = corrected.trim();
                mainHandler.post(() -> {
                    completeReservedUtteranceWithText(gen, offlineText);
                    updateStatus("離線完成（未潤稿）: " + truncate(offlineText, 20));
                });
                return;
            }

            String deterministic = TimeoutWall.runWithBudget(() -> {
                OnDeviceCorrectionEngine current = onDeviceCorrection;
                if (current == null || !current.isCorrectorReady()) return null;
                return current.correctDeterministic(localText);
            }, OFFLINE_CORRECTION_FAILED_SENTINEL, OFFLINE_CORRECTION_TIMEOUT_MS);

            if (!OFFLINE_CORRECTION_FAILED_SENTINEL.equals(deterministic)
                    && deterministic != null
                    && !deterministic.trim().isEmpty()) {
                final String offlineText = deterministic.trim();
                Log.w(TAG, "[OfflineCorrection] correction failed after process-text failure: " + reason);
                mainHandler.post(() -> {
                    completeReservedUtteranceWithText(gen, offlineText);
                    updateStatus("離線完成（無標點）: " + truncate(offlineText, 20));
                });
                return;
            }

            Log.w(TAG, "[OfflineCorrection] using raw local text after process-text failure: " + reason);
            mainHandler.post(() -> {
                completeReservedUtteranceWithText(gen, localText);
                updateStatus("離線原文（未校正）: " + truncate(localText, 20));
            });
        }, "OfflineAppend-Correct").start();
    }

    private void sendTextReplace(String spokenText, String beforeCursor, String afterCursor, int gen) {
        String serverUrl = getServerUrl();

        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("spoken_text", spokenText)
                .addFormDataPart("before_cursor", beforeCursor)
                .addFormDataPart("after_cursor", afterCursor)
                .build();

        Request.Builder reqBuilder = new Request.Builder()
                .url(serverUrl + "/v1/replace-text")
                .post(body);

        String auth = getAuthPassword();
        if (auth != null && !auth.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + auth);
        }

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Replace-text request failed", e);
                mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    handleWTIResponse(json, Mode.REPLACE, gen);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing replace-text response", e);
                    mainHandler.post(() -> updateStatus("解析錯誤"));
                }
            }
        });
    }

    private void handleWTIResponse(JSONObject json, Mode mode, int gen) {
        mainHandler.post(() -> {
            try {
                // v6.17: ic null-guard moved into each case.
                // REPLACE still needs ic for deleteSurroundingText; others use commitFinalText which handles null ic internally.
                InputConnection ic = getCurrentInputConnection();

                switch (mode) {
                    case APPEND: {
                        String text = json.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            if (reserveUtteranceGeneration(gen)) {
                                // v6.17: commitFinalText 支援 Termux/Gemini 備援。
                                completeReservedUtteranceWithText(gen, text);
                            }
                        } else {
                            completeReservedUtteranceWithoutText(gen);
                            updateStatus("未辨識到文字");
                        }
                        break;
                    }
                    case REPLACE: {
                        if (ic == null) {
                            updateStatus("無法取得輸入連線");
                            return;
                        }
                        String text = json.optString("text", "").trim();
                        int deleteBefore = json.optInt("delete_before", 0);
                        int deleteAfter = json.optInt("delete_after", 0);
                        String insert = json.optString("insert", text);

                        if (deleteBefore > 0 || deleteAfter > 0) {
                            deleteSurroundingTextProgrammatically(ic, deleteBefore, deleteAfter);
                        }
                        if (!insert.isEmpty()) {
                            // v6.17: commitFinalText 支援 Termux/Gemini 備援。
                            commitFinalText(insert);
                            updateStatus("🔄 替換: " + truncate(insert, 20));
                        } else {
                            updateStatus("未找到可替換的文字");
                        }
                        break;
                    }
                    case SPELL: {
                        String text = json.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            // v6.17: commitFinalText 支援 Termux/Gemini 備援。
                            commitFinalText(text);
                            updateStatus("✏️ 拼字: " + text);
                        } else {
                            updateStatus("拼字失敗");
                        }
                        break;
                    }
                    case TRANSLATE: {
                        String text = json.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            // v6.17: commitFinalText 支援 Termux/Gemini 備援。
                            commitFinalText(text);
                            updateStatus("🌐 翻譯: " + truncate(text, 25));
                        } else {
                            updateStatus("翻譯失敗");
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling response", e);
                updateStatus("處理錯誤");
            }
        });
    }

    // ==================== Keyboard Switching ====================

    private void switchKeyboard(KeyboardMode mode) {
        dismissSymbolPopup();
        // v6.23: clear English buffer when leaving English keyboard
        if (currentKeyboardMode == KeyboardMode.ENGLISH && mode != KeyboardMode.ENGLISH) {
            clearEnWordBuffer();
        }
        currentKeyboardMode = mode;
        voiceKeyboard.setVisibility(mode == KeyboardMode.VOICE ? View.VISIBLE : View.GONE);
        englishKeyboard.setVisibility(mode == KeyboardMode.ENGLISH ? View.VISIBLE : View.GONE);
        numbersKeyboard.setVisibility(mode == KeyboardMode.NUMBERS ? View.VISIBLE : View.GONE);
        // Close any open panel when switching keyboards
        closePanel();
    }

    /**
     * Recursively find all views with "key:xxx" tags and set up click listeners.
     */
    private void setupTypingKeyboard(View parent) {
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) parent;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            Object tag = child.getTag();
            if (tag != null && tag.toString().startsWith("key:")) {
                String key = tag.toString().substring(4);
                if (key.equals("backspace")) {
                    setupBackspaceTouch(child);
                } else {
                    child.setOnClickListener(v -> onTypingKeyPressed(key));
                }
            }
            if (child instanceof ViewGroup) {
                setupTypingKeyboard(child);
            }
        }
    }

    private void onTypingKeyPressed(String key) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (key) {
            case "shift":
                toggleShift();
                break;
            case "space":
                // v6.23: learn current word before committing space (English only)
                if (currentKeyboardMode == KeyboardMode.ENGLISH) {
                    learnEnglishWord();
                    clearEnWordBuffer();
                }
                commitTextProgrammatically(ic, " ");
                break;
            case "enter":
                // v6.23: learn current word before enter (English only)
                if (currentKeyboardMode == KeyboardMode.ENGLISH) {
                    learnEnglishWord();
                    clearEnWordBuffer();
                }
                handleEnterKey();
                break;
            case "toVoice":
                switchKeyboard(KeyboardMode.VOICE);
                break;
            case "toNumbers":
                switchKeyboard(KeyboardMode.NUMBERS);
                break;
            case "toEnglish":
                switchKeyboard(KeyboardMode.ENGLISH);
                break;
            default:
                // Regular character — apply shift for letters only
                String ch = key;
                if (shiftActive && key.length() == 1 && Character.isLetter(key.charAt(0))) {
                    ch = key.toUpperCase();
                }
                commitTextProgrammatically(ic, ch);
                // v6.23: maintain enWordBuffer for English keyboard letter keys only
                if (currentKeyboardMode == KeyboardMode.ENGLISH
                        && key.length() == 1 && Character.isLetter(key.charAt(0))) {
                    enWordBuffer.append(key.toLowerCase());
                    refreshEnglishSuggestions();
                } else if (currentKeyboardMode == KeyboardMode.ENGLISH && !key.equals("shift")) {
                    // Non-letter key (e.g., ".", ",") while on English — treat as word break
                    learnEnglishWord();
                    clearEnWordBuffer();
                }
                // Auto-unshift after one character (unless caps lock)
                if (shiftActive && !capsLock) {
                    shiftActive = false;
                    updateShiftUI();
                }
                break;
        }
    }

    private boolean deleteSelectionIfAny(InputConnection ic) {
        if (ic == null) return false;
        CharSequence selectedText = ic.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            commitTextProgrammatically(ic, "");
            // v6.23: a selection delete breaks word continuity — resync English buffer.
            if (currentKeyboardMode == KeyboardMode.ENGLISH) clearEnWordBuffer();
            return true;
        }
        return false;
    }

    private void toggleShift() {
        if (!shiftActive) {
            shiftActive = true;
            capsLock = false;
        } else if (!capsLock) {
            // Second press = caps lock
            capsLock = true;
        } else {
            // Third press = off
            shiftActive = false;
            capsLock = false;
        }
        updateShiftUI();
    }

    private void updateShiftUI() {
        if (englishKeyboard == null) return;
        // Update letter key labels
        updateLetterCase(englishKeyboard);
        // Update shift key appearance
        View shiftKey = englishKeyboard.findViewWithTag("key:shift");
        if (shiftKey instanceof TextView) {
            if (capsLock) {
                ((TextView) shiftKey).setText("⬆");
                ((TextView) shiftKey).setTextColor(0xFF4ECCA3); // green = caps lock
            } else if (shiftActive) {
                ((TextView) shiftKey).setText("⬆");
                ((TextView) shiftKey).setTextColor(0xFFFFFFFF); // white = shift active
            } else {
                ((TextView) shiftKey).setText("⬆");
                ((TextView) shiftKey).setTextColor(getResources().getColor(R.color.key_text, null));
            }
        }
    }

    private void updateLetterCase(View parent) {
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) parent;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            Object tag = child.getTag();
            if (tag != null && child instanceof TextView) {
                String tagStr = tag.toString();
                if (tagStr.startsWith("key:") && tagStr.length() == 5) {
                    char c = tagStr.charAt(4);
                    if (Character.isLetter(c)) {
                        String display = shiftActive ? String.valueOf(c).toUpperCase() : String.valueOf(c);
                        ((TextView) child).setText(display);
                    }
                }
            }
            if (child instanceof ViewGroup) {
                updateLetterCase(child);
            }
        }
    }

    private void handleEnterKey() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                    && (ei.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
                markProgrammaticTextChange();
                ic.performEditorAction(ei.imeOptions & EditorInfo.IME_MASK_ACTION);
            } else {
                commitTextProgrammatically(ic, "\n");
            }
        }
    }

    /**
     * Set up long-press repeat for a backspace key (works for any keyboard's backspace).
     */
    private void setupBackspaceTouch(View backspaceView) {
        backspaceView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    backspacePressed = true;
                    backspaceRepeatCount = 0;
                    InputConnection ic0 = getCurrentInputConnection();
                    if (ic0 != null) {
                        if (!deleteSelectionIfAny(ic0)) {
                            deleteSurroundingTextProgrammatically(ic0, 1, 0);
                            // v6.23: pop last char from enWordBuffer on English keyboard
                            if (currentKeyboardMode == KeyboardMode.ENGLISH && enWordBuffer.length() > 0) {
                                enWordBuffer.deleteCharAt(enWordBuffer.length() - 1);
                                refreshEnglishSuggestions();
                            }
                        }
                    }
                    backspaceRepeatRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (!backspacePressed) return;
                            InputConnection ic = getCurrentInputConnection();
                            if (ic != null) {
                                backspaceRepeatCount++;
                                int deleteCount = backspaceRepeatCount < 5 ? 1
                                        : backspaceRepeatCount < 15 ? 2 : 5;
                                deleteSurroundingTextProgrammatically(ic, deleteCount, 0);
                                // v6.23: pop chars from enWordBuffer on repeat backspace (English)
                                if (currentKeyboardMode == KeyboardMode.ENGLISH && enWordBuffer.length() > 0) {
                                    int popCount = Math.min(deleteCount, enWordBuffer.length());
                                    enWordBuffer.delete(enWordBuffer.length() - popCount, enWordBuffer.length());
                                    refreshEnglishSuggestions();
                                }
                            }
                            long delay = Math.max(30, 120 - backspaceRepeatCount * 6);
                            mainHandler.postDelayed(this, delay);
                        }
                    };
                    mainHandler.postDelayed(backspaceRepeatRunnable, 400);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    backspacePressed = false;
                    if (backspaceRepeatRunnable != null) {
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                    }
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
    }

    // ==================== English Predictive Input (v6.23) ====================

    /**
     * Refresh suggestion bar from enWordBuffer. Safe to call on main thread.
     */
    private void refreshEnglishSuggestions() {
        try {
            String prefix = enWordBuffer.toString();
            List<String> suggestions = (englishDict != null && englishDict.isLoaded())
                    ? englishDict.suggest(prefix, 3)
                    : java.util.Collections.<String>emptyList();

            for (int i = 0; i < 3; i++) {
                enSuggestions[i] = (i < suggestions.size()) ? suggestions.get(i) : null;
            }
            updateSuggestionBar();
        } catch (Exception e) {
            Log.w(TAG, "refreshEnglishSuggestions failed (fail-open)", e);
        }
    }

    /**
     * Apply suggestion at slot index: delete typed prefix, commit suggestion word + space.
     */
    private void applyEnglishSuggestion(int index) {
        try {
            if (index < 0 || index >= 3) return;
            String word = enSuggestions[index];
            if (word == null || word.isEmpty()) return;
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;

            int bufLen = enWordBuffer.length();
            if (bufLen > 0) {
                // v6.23 safety (Codex cross-family review): only delete if the text before the
                // cursor actually matches our buffer. Guards against buffer/field desync (cursor
                // moved externally, selection deleted) so we NEVER delete unrelated text.
                CharSequence before = ic.getTextBeforeCursor(bufLen, 0);
                if (before == null || before.length() != bufLen
                        || !before.toString().equalsIgnoreCase(enWordBuffer.toString())) {
                    clearEnWordBuffer();  // desynced → abort safely, no deletion
                    return;
                }
                deleteSurroundingTextProgrammatically(ic, bufLen, 0);
            }

            // Capitalize if buffer started with uppercase (i.e., shift was active when first letter typed)
            // Simple heuristic: check if buffer had first char typed uppercase (we always store lower,
            // but we can check shift state). Keep it simple: commit as-is (lowercase) for safety.
            commitTextProgrammatically(ic, word + " ");

            if (englishDict != null) englishDict.learn(word);

            enWordBuffer.setLength(0);
            clearEnSuggestions();
            updateSuggestionBar();
        } catch (Exception e) {
            Log.w(TAG, "applyEnglishSuggestion failed (fail-open)", e);
        }
    }

    /**
     * Learn the current enWordBuffer contents if valid and not in a password field.
     */
    private void learnEnglishWord() {
        try {
            if (englishDict == null || enWordBuffer.length() < 2) return;
            String word = enWordBuffer.toString();
            // Password-field check
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null) {
                int variation = ei.inputType & android.text.InputType.TYPE_MASK_VARIATION;
                if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        || variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    return;
                }
            }
            englishDict.learn(word);
        } catch (Exception e) {
            Log.w(TAG, "learnEnglishWord failed (fail-open)", e);
        }
    }

    /**
     * Clear enWordBuffer and suggestions (does NOT commit anything).
     */
    private void clearEnWordBuffer() {
        try {
            enWordBuffer.setLength(0);
            clearEnSuggestions();
            updateSuggestionBar();
        } catch (Exception e) {
            Log.w(TAG, "clearEnWordBuffer failed (fail-open)", e);
        }
    }

    private void clearEnSuggestions() {
        enSuggestions[0] = null;
        enSuggestions[1] = null;
        enSuggestions[2] = null;
    }

    private void updateSuggestionBar() {
        if (enSuggest0 == null) return;
        enSuggest0.setText(enSuggestions[0] != null ? enSuggestions[0] : "");
        if (enSuggest1 != null) enSuggest1.setText(enSuggestions[1] != null ? enSuggestions[1] : "");
        if (enSuggest2 != null) enSuggest2.setText(enSuggestions[2] != null ? enSuggestions[2] : "");
    }

    // ==================== Mode ====================

    private void cycleMode() {
        switch (currentMode) {
            case APPEND: currentMode = Mode.REPLACE; break;
            case REPLACE: currentMode = Mode.SPELL; break;
            case SPELL: currentMode = Mode.TRANSLATE; break;
            case TRANSLATE: currentMode = Mode.APPEND; break;
        }
        saveMode();
        updateModeUI();
    }

    private void saveMode() {
        getSharedPreferences("simon_ime", MODE_PRIVATE)
                .edit()
                .putString(PREF_MODE_KEY, currentMode.name())
                .apply();
    }

    private void loadSavedMode() {
        String saved = getSharedPreferences("simon_ime", MODE_PRIVATE)
                .getString(PREF_MODE_KEY, Mode.APPEND.name());
        try {
            currentMode = Mode.valueOf(saved);
        } catch (Exception e) {
            currentMode = Mode.APPEND;
        }
    }

    private void updateModeUI() {
        if (btnMode == null) return;
        switch (currentMode) {
            case APPEND:
                btnMode.setText("追");
                btnMode.setTextColor(getResources().getColor(R.color.mode_append, null));
                break;
            case REPLACE:
                btnMode.setText("換");
                btnMode.setTextColor(getResources().getColor(R.color.mode_replace, null));
                break;
            case SPELL:
                btnMode.setText("拼");
                btnMode.setTextColor(getResources().getColor(R.color.mode_spell, null));
                break;
            case TRANSLATE:
                btnMode.setText("譯");
                btnMode.setTextColor(0xFF6bc5f0);
                break;
        }
    }

    // ==================== Helpers ====================

    private void updateStatus(String text) {
        if (statusText == null) return;
        if (text == null || text.isEmpty()) {
            statusText.setVisibility(View.GONE);
        } else {
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
            // Auto-hide after 3 seconds if not recording
            if (!isRecording) {
                mainHandler.postDelayed(() -> {
                    if (!isRecording && statusText != null) {
                        statusText.setVisibility(View.GONE);
                    }
                }, 3000);
            }
        }
    }

    /**
     * v6.1: 串流即時預覽只顯示在「鍵盤自己的」預覽列（previewText），絕不碰輸入框。
     * 可由任意執行緒呼叫（內部切回主執行緒）。
     */
    private void updatePreviewStrip(String text) {
        mainHandler.post(() -> {
            if (previewText == null) return;
            if (text == null || text.isEmpty()) {
                previewText.setText("");
                previewText.setVisibility(View.GONE);
            } else {
                previewText.setText(text);
                previewText.setVisibility(View.VISIBLE);
            }
        });
    }

    /**
     * v6.1: 用整段保留音訊（fullPcmBuffer）做一次乾淨的 HTTP 轉錄（APPEND 走 /v1/audio/transcriptions，
     * 伺服器端會做標點＋法律詞校正），避免把無標點的串流預覽倒進輸入框。
     * 只在 WS 失敗或 final 為空時呼叫。須在錄音停止後（fullPcmBuffer 寫入已完成）呼叫。
     */
    private void httpFallbackFullAudio(int gen) {
        // v6.25: 終局守衛——同一 generation 只允許一次 commit/fallback，擋 WS 晚到回呼造成的重複提交
        if (!reserveUtteranceGeneration(gen)) return;
        byte[] pcm = getFullPcmForGeneration(gen);
        if (pcm == null || pcm.length < 3200) {
            completeReservedUtteranceWithoutText(gen);
            mainHandler.post(() -> updateStatus("沒有可用的音訊，請再試一次"));
            return;
        }
        byte[] wavData = pcmToWav(pcm, SAMPLE_RATE, 1, 16);
        Log.i(TAG, "[AudioStream] 整段音訊 HTTP fallback (" + pcm.length + " bytes)");
        sendFullAudioHttpFallback(gen, wavData, pcm);
    }

    /**
     * v6.3: WS 已失敗或 final 為空後，先嘗試整段 HTTP 轉錄；HTTP 也失敗/空結果時，
     * 使用手機端內建 SenseVoice 對同一段 fullPcmBuffer 做最終兜底。
     */
    private void sendFullAudioHttpFallback(int gen, byte[] wavData, byte[] pcm) {
        String serverUrl = getServerUrl();
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav",
                        RequestBody.create(wavData, MediaType.parse("audio/wav")));

        String auth = getAuthPassword();
        Request.Builder reqBuilder = new Request.Builder()
                .url(serverUrl + "/v1/audio/transcriptions")
                .post(bodyBuilder.build());
        if (auth != null && !auth.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + auth);
        }

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "[AudioStream] HTTP fallback failed", e);
                runOfflineFullAudioFallback(gen, "HTTP fallback failed: " + e.getMessage(), true, pcm);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        Log.w(TAG, "[AudioStream] HTTP fallback server error: " + response.code());
                        runOfflineFullAudioFallback(gen, "HTTP fallback response " + response.code(), true, pcm);
                        return;
                    }

                    JSONObject json = new JSONObject(responseBody);
                    String text = json.optString("text", "").trim();
                    if (text.isEmpty()) {
                        Log.w(TAG, "[AudioStream] HTTP fallback returned empty text");
                        runOfflineFullAudioFallback(gen, "HTTP fallback text empty", true, pcm);
                        return;
                    }

                    completeReservedUtteranceWithText(gen, text);
                } catch (Exception e) {
                    Log.e(TAG, "[AudioStream] HTTP fallback parse error", e);
                    runOfflineFullAudioFallback(gen, "HTTP fallback parse error: " + e.getMessage(), true, pcm);
                }
            }
        });
    }

    private void runOfflineFullAudioFallback(int gen, String reason, boolean utteranceAlreadyReserved) {
        byte[] pcm = getFullPcmForGeneration(gen);
        runOfflineFullAudioFallback(gen, reason, utteranceAlreadyReserved, pcm);
    }

    /**
     * v6.12-revert: 最後防線只允許本機 STT 產生候選文字，再送回 server correction。
     * 不直接 commit SenseVoice 原文，避免簡體/無標點漏出。
     */
    private void runOfflineFullAudioFallback(int gen, String reason, boolean utteranceAlreadyReserved, byte[] pcm) {
        if (!utteranceAlreadyReserved && !reserveUtteranceGeneration(gen)) return;

        if (pcm == null || pcm.length < 3200) {
            Log.w(TAG, "[OfflineFallback] no usable PCM after server failure: " + reason);
            completeReservedUtteranceWithoutText(gen);
            mainHandler.post(() -> updateStatus("沒有可用的音訊，請再試一次"));
            return;
        }
        if (!localSTTReady || localSTT == null || !localSTT.isReady()) {
            Log.e(TAG, "[OfflineFallback] SenseVoice not ready after server failure: " + reason);
            completeReservedUtteranceWithoutText(gen);
            mainHandler.post(() -> updateStatus("離線辨識未就緒"));
            return;
        }

        mainHandler.post(() -> updateStatus("離線辨識…"));
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String text = localSTT.recognize(pcm, SAMPLE_RATE);
            long sttMs = System.currentTimeMillis() - t0;
            if (text != null) text = englishMapper.apply(text.trim());

            if (text != null && !text.isEmpty()) {
                final String finalText = text;
                Log.i(TAG, "[OfflineFallback] SenseVoice success after " + reason
                        + " (" + sttMs + "ms), routing to server correction: '"
                        + truncate(finalText, 50) + "'");
                mainHandler.post(() -> updateStatus("伺服器校正中…"));
                sendTextProcess(finalText, Mode.APPEND, gen, true);
            } else {
                Log.e(TAG, "[OfflineFallback] SenseVoice returned empty after server failure: " + reason);
                completeReservedUtteranceWithoutText(gen);
                mainHandler.post(() -> updateStatus("離線辨識無結果"));
            }
        }, "OfflineFallback-SenseVoice").start();
    }

    // ==================== v6.20 新增方法 ====================

    /** 更新剪貼簿鍵的標記計數徽章 */
    private void updateArmedIndicator() {
        if (btnClipboard == null) return;
        try {
            if (aiContextText != null) {
                btnClipboard.setText("🤖");
            } else if (!markedClips.isEmpty()) {
                btnClipboard.setText("📋" + markedClips.size());
            } else {
                btnClipboard.setText("📋");
            }
        } catch (Exception e) {
            Log.w(TAG, "updateArmedIndicator failed", e);
        }
    }

    /**
     * v6.20 R2: 將已標記剪貼組成 AI 素材、武裝待命。
     * 以 "\n---\n" 串接；超過 6000 字則丟棄「最舊」標記。
     */
    private void armAiContext() {
        if (markedClips.isEmpty()) {
            updateStatus("請先標記剪貼內容");
            return;
        }
        List<String> marks = new ArrayList<>(markedClips); // 插入序：最舊在前
        StringBuilder sb = new StringBuilder();
        // 由最新往回累加，總長 ≤6000；不足者代表最舊被丟棄
        for (int i = marks.size() - 1; i >= 0; i--) {
            String piece = marks.get(i);
            int added = piece.length() + (sb.length() > 0 ? 5 : 0); // "\n---\n" = 5
            if (sb.length() + added > 6000) break;
            if (sb.length() > 0) sb.insert(0, "\n---\n");
            sb.insert(0, piece);
        }
        aiContextText = sb.toString();
        aiContextCount = markedClips.size();
        updateArmedIndicator();
        updateStatus("🤖 AI素材已備 " + aiContextCount + "則 · 長按🎤說出指令");
        closePanel();
    }

    /** v6.20 R2: 單次用後解除 AI 武裝狀態 */
    private void clearAiState() {
        aiContextText = null;
        aiContextCount = 0;
        markedClips.clear();
        updateArmedIndicator();
    }

    /**
     * v6.20 R2: 文字指令 → POST /v1/ai-command，回傳答案「插入」游標處（絕不刪除周圍）。
     * 空回應 → 保留武裝供重試；欄位已切換（fieldGeneration 變動）→ 丟棄不插入。
     */
    private void sendAiCommand(String instruction, String context) {
        if (instruction == null || instruction.trim().isEmpty()) {
            updateStatus("未辨識到指令");
            return;
        }
        final int capturedGeneration = fieldGeneration;
        okhttp3.FormBody body = new okhttp3.FormBody.Builder()
                .add("instruction", instruction.trim())
                .add("context", context != null ? context : "")
                .add("language", "zh-TW")
                .build();
        Request.Builder rb = new Request.Builder()
                .url(getServerUrl() + "/v1/ai-command")
                .post(body);
        String auth = getAuthPassword();
        if (auth != null && !auth.isEmpty()) rb.addHeader("Authorization", "Bearer " + auth);
        httpClient.newCall(rb.build()).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> updateStatus("AI 指令失敗，素材保留可重試"));
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                handleAiCommandResponse(response, capturedGeneration);
            }
        });
    }

    /**
     * v6.20 R2: 音訊指令（本機 STT 未取得文字時）→ POST /v1/ai-command（file 由伺服器轉錄為指令）。
     */
    private void sendAiCommandAudio(byte[] wavData, String context) {
        if (wavData == null || wavData.length == 0) {
            updateStatus("沒有可用的音訊，素材保留可重試");
            return;
        }
        final int capturedGeneration = fieldGeneration;
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav",
                        RequestBody.create(wavData, MediaType.parse("audio/wav")))
                .addFormDataPart("context", context != null ? context : "")
                .addFormDataPart("language", "zh-TW")
                .build();
        Request.Builder rb = new Request.Builder()
                .url(getServerUrl() + "/v1/ai-command")
                .post(body);
        String auth = getAuthPassword();
        if (auth != null && !auth.isEmpty()) rb.addHeader("Authorization", "Bearer " + auth);
        httpClient.newCall(rb.build()).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> updateStatus("AI 指令失敗，素材保留可重試"));
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                handleAiCommandResponse(response, capturedGeneration);
            }
        });
    }

    /** v6.20 R2: 共用 /v1/ai-command 回應處理（marshal 回主緒後 insert）。 */
    private void handleAiCommandResponse(Response response, int capturedGeneration) {
        String bodyStr;
        boolean ok;
        int code;
        try (Response r = response) {
            code = r.code();
            ok = r.isSuccessful();
            bodyStr = r.body() != null ? r.body().string() : "";
        } catch (Exception e) {
            mainHandler.post(() -> updateStatus("AI 指令失敗，素材保留可重試"));
            return;
        }
        final boolean fok = ok;
        final int fcode = code;
        final String fbody = bodyStr;
        mainHandler.post(() -> {
            if (!fok) {
                updateStatus("AI 伺服器錯誤 " + fcode + "，素材保留");
                return; // keep armed
            }
            String text = "";
            try {
                text = new JSONObject(fbody).optString("text", "").trim();
            } catch (Exception ignore) {}
            if (text.isEmpty()) {
                updateStatus("AI 無回應，素材保留可重試"); // keep armed
                return;
            }
            if (capturedGeneration != fieldGeneration) {
                Log.w(TAG, "AI response discarded: field switched");
                return;
            }
            commitFinalText(text);       // pure insert at cursor (no deleteSurroundingText)
            updateStatus("🤖 " + truncate(text, 20));
            clearAiState();              // single-use disarm
        });
    }

    /**
     * v6.20 R1/R3: 批次把已標記剪貼加入選定的常用詞彙資料夾。
     */
    private void showBatchAddToCommandsInline(List<String> clips) {
        if (clips == null || clips.isEmpty()) {
            updateStatus("請先標記剪貼內容");
            return;
        }
        panelContainer.removeAllViews();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF111122);
        panel.setPadding(24, 16, 24, 16);

        TextView title = new TextView(this);
        title.setText("批次加入常用詞彙（" + clips.size() + " 則）");
        title.setTextColor(0xFF4ECCA3);
        title.setTextSize(16);
        panel.addView(title);

        TextView groupLabel = new TextView(this);
        groupLabel.setText("選擇資料夾：");
        groupLabel.setTextColor(0xFF888888);
        groupLabel.setTextSize(12);
        groupLabel.setPadding(0, 8, 0, 8);
        panel.addView(groupLabel);

        LinearLayout groupRow = new LinearLayout(this);
        groupRow.setOrientation(LinearLayout.HORIZONTAL);
        groupRow.setPadding(0, 8, 0, 12);

        List<String> groupNames = commandsHelper.getGroupNames();
        final String[] selectedGroup = { groupNames.isEmpty() ? null : groupNames.get(0) };
        final Button[] groupButtons = new Button[groupNames.size()];
        for (int i = 0; i < groupNames.size(); i++) {
            String gName = groupNames.get(i);
            Button btn = new Button(this);
            btn.setText(gName);
            btn.setTextSize(12);
            btn.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 72);
            lp.setMarginEnd(8);
            btn.setLayoutParams(lp);
            btn.setPadding(16, 0, 16, 0);
            groupButtons[i] = btn;
            if (gName.equals(selectedGroup[0])) {
                btn.setTextColor(0xFF4ECCA3); btn.setBackgroundColor(0xFF1a1a2e);
            } else {
                btn.setTextColor(0xFF888888); btn.setBackgroundColor(0xFF16213e);
            }
            btn.setOnClickListener(v -> {
                selectedGroup[0] = gName;
                for (int j = 0; j < groupButtons.length; j++) {
                    if (groupNames.get(j).equals(gName)) {
                        groupButtons[j].setTextColor(0xFF4ECCA3); groupButtons[j].setBackgroundColor(0xFF1a1a2e);
                    } else {
                        groupButtons[j].setTextColor(0xFF888888); groupButtons[j].setBackgroundColor(0xFF16213e);
                    }
                }
            });
            groupRow.addView(btn);
        }
        panel.addView(groupRow);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnConfirm = new Button(this);
        btnConfirm.setText("確認加入");
        btnConfirm.setTextColor(0xFF4ECCA3);
        btnConfirm.setTextSize(14);
        btnConfirm.setOnClickListener(v -> {
            if (selectedGroup[0] != null) {
                for (String clip : clips) {
                    String lbl = clip.length() > 10 ? clip.substring(0, 10) + "…" : clip;
                    commandsHelper.addCommand(selectedGroup[0], lbl, clip);
                }
                updateStatus("⚡ 已加入 " + clips.size() + " 則至「" + selectedGroup[0] + "」");
                markedClips.clear();
                showPanel(Panel.CLIPBOARD);
            }
        });
        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(0xFF888888);
        btnCancel.setTextSize(14);
        btnCancel.setOnClickListener(v -> showPanel(Panel.CLIPBOARD));
        actionRow.addView(btnConfirm);
        actionRow.addView(btnCancel);
        panel.addView(actionRow);

        panelContainer.addView(panel);
    }

    /** 顯示/隱藏剪貼簿標記底欄並更新計數 */
    private void updateClipMarkFooter() {
        if (clipMarkFooter == null || clipMarkCount == null) return;
        if (markedClips.isEmpty()) {
            clipMarkFooter.setVisibility(View.GONE);
        } else {
            clipMarkFooter.setVisibility(View.VISIBLE);
            clipMarkCount.setText("已標記 " + markedClips.size() + " 條");
        }
    }

    /** 從 SharedPreferences 載入已登錄詞彙集合 */
    private void loadEnrolledVocab() {
        SharedPreferences prefs = getSharedPreferences("simon_ime_vocab", MODE_PRIVATE);
        String json = prefs.getString("enrolled_vocab", "[]");
        enrolledVocab = new HashSet<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String w = arr.optString(i, "");
                if (!w.isEmpty()) enrolledVocab.add(w);
            }
        } catch (Exception ignored) {}
    }

    /**
     * v6.20 R5 gate: 只收「短純中文詞」。trim 後長度 2–6、每個字元都是 CJK U+4E00–U+9FFF
     * （這一條就擋掉空白/ASCII/數字/標點/URL），再排除少量填充停用詞。
     */
    private boolean isEnrollableVocab(String w) {
        if (w == null) return false;
        w = w.trim();
        int len = w.length();
        if (len < 2 || len > 6) return false;
        for (int i = 0; i < len; i++) {
            char c = w.charAt(i);
            if (c < 0x4E00 || c > 0x9FFF) return false;
        }
        Set<String> stop = new HashSet<>(Arrays.asList(
                "然後", "這個", "那個", "所以", "就是", "可是", "但是", "因為", "如果", "不過", "的話"));
        return !stop.contains(w);
    }

    /**
     * v6.20 R5: 速率限制詞彙自動登錄（≥1000ms 間隔、≤10 條/分鐘、≤100 條/天）。
     * 只收短純中文詞；密碼欄位一律略過；重複詞彙直接略過；登錄後以 source="clip" 同步至伺服器。
     */
    private void maybeAutoEnrollVocab(String text) {
        maybeAutoEnrollVocab(text, "");
    }

    private void maybeAutoEnrollVocab(String text, String label) {
        if (text == null || text.trim().isEmpty()) return;
        // MUST-FIX #1: never auto-enroll our own IME output (self-copy safety net uses label "simon-ime").
        if ("simon-ime".equals(label)) return;
        if (!autoVocabEnabled || vocabHelper == null) return;
        String word = text.trim();
        // R5 gate: short pure-CJK word only (rejects whitespace/ASCII/digits/punct/URLs + fillers)
        if (!isEnrollableVocab(word)) return;
        if (enrolledVocab.contains(word)) return;

        // Password-field suppression: never enroll anything typed into a password field
        try {
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null) {
                int variation = ei.inputType & android.text.InputType.TYPE_MASK_VARIATION;
                if (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                        || variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        || variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                        || variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                    return;
                }
            }
        } catch (Exception ignored) {}

        long now = System.currentTimeMillis();
        if (now - lastEnrollTimeMs < 1000L) return;  // ≥1000ms between enrolls
        if (now - enrollMinuteStartMs > 60_000L) {
            enrollMinuteStartMs = now;
            enrollCountThisMinute = 0;
        }
        if (now - enrollDayStartMs > 86_400_000L) {
            enrollDayStartMs = now;
            enrollCountToday = 0;
        }
        if (enrollCountThisMinute >= 10 || enrollCountToday >= 100) return;

        enrollCountThisMinute++;
        enrollCountToday++;
        lastEnrollTimeMs = now;
        enrolledVocab.add(word);

        // 持久化
        SharedPreferences prefs = getSharedPreferences("simon_ime_vocab", MODE_PRIVATE);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String w : enrolledVocab) arr.put(w);
        prefs.edit().putString("enrolled_vocab", arr.toString()).apply();

        // 同步到伺服器（MUST-FIX #2: source="clip" 獨立命名空間）
        vocabHelper.sync(word, "clip", new VocabHelper.SyncCallback() {
            @Override public void onSuccess() {
                Log.i(TAG, "[Vocab] synced: " + word);
                mainHandler.post(() -> updateStatus("📗 已記住詞彙「" + word + "」"));
            }
            @Override public void onError(String msg) { Log.w(TAG, "[Vocab] sync error: " + msg); }
        });
    }

    /** 在 panelContainer 顯示詞彙庫列表 */
    private void showVocabListInline() {
        panelContainer.removeAllViews();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF111122);
        panel.setPadding(16, 8, 16, 8);

        // 標題列
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("📚 詞彙庫");
        title.setTextColor(0xFF4ECCA3);
        title.setTextSize(14);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        titleRow.addView(title);

        Button btnBack = new Button(this);
        btnBack.setText("← 返回");
        btnBack.setTextColor(0xFF888888);
        btnBack.setTextSize(12);
        btnBack.setBackground(null);
        btnBack.setAllCaps(false);
        btnBack.setOnClickListener(v -> showPanel(Panel.CLIPBOARD));
        titleRow.addView(btnBack);
        panel.addView(titleRow);

        if (enrolledVocab.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚無詞彙（左滑剪貼簿項目 → 加詞彙）");
            empty.setTextColor(0xFF666666);
            empty.setTextSize(12);
            empty.setPadding(0, 16, 0, 0);
            panel.addView(empty);
        } else {
            for (String word : new ArrayList<>(enrolledVocab)) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, 4, 0, 4);

                TextView wordView = new TextView(this);
                wordView.setText(word);
                wordView.setTextColor(0xFFcccccc);
                wordView.setTextSize(13);
                LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                wordView.setLayoutParams(wlp);
                row.addView(wordView);

                Button btnDel = new Button(this);
                btnDel.setText("✕");
                btnDel.setTextColor(0xFF666666);
                btnDel.setTextSize(12);
                btnDel.setBackground(null);
                btnDel.setAllCaps(false);
                btnDel.setOnClickListener(v -> {
                    enrolledVocab.remove(word);
                    SharedPreferences prefs = getSharedPreferences("simon_ime_vocab", MODE_PRIVATE);
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (String w : enrolledVocab) arr.put(w);
                    prefs.edit().putString("enrolled_vocab", arr.toString()).apply();
                    if (vocabHelper != null) {
                        vocabHelper.delete(word, new VocabHelper.DeleteCallback() {
                            @Override public void onSuccess() {}
                            @Override public void onError(String msg) {}
                        });
                    }
                    showVocabListInline();
                });
                row.addView(btnDel);
                panel.addView(row);
            }
        }

        panelContainer.addView(panel);
    }

    /** 在 panelContainer 顯示批次加入常用指令介面 */
    private void showBatchAddToCommandsInline() {
        if (markedClips.isEmpty()) return;
        panelContainer.removeAllViews();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF111122);
        panel.setPadding(24, 16, 24, 16);

        TextView title = new TextView(this);
        title.setText("批次加入常用指令（" + markedClips.size() + " 條）");
        title.setTextColor(0xFF4ECCA3);
        title.setTextSize(16);
        panel.addView(title);

        TextView groupLabel = new TextView(this);
        groupLabel.setText("選擇群組：");
        groupLabel.setTextColor(0xFF888888);
        groupLabel.setTextSize(12);
        groupLabel.setPadding(0, 8, 0, 4);
        panel.addView(groupLabel);

        LinearLayout groupRow = new LinearLayout(this);
        groupRow.setOrientation(LinearLayout.HORIZONTAL);
        groupRow.setPadding(0, 0, 0, 12);

        List<String> groupNames = commandsHelper.getGroupNames();
        final String[] selectedGroup = { groupNames.isEmpty() ? null : groupNames.get(0) };
        final Button[] groupButtons = new Button[groupNames.size()];

        for (int i = 0; i < groupNames.size(); i++) {
            String gName = groupNames.get(i);
            Button btn = new Button(this);
            btn.setText(gName);
            btn.setTextSize(12);
            btn.setAllCaps(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, 72);
            lp.setMarginEnd(8);
            btn.setLayoutParams(lp);
            groupButtons[i] = btn;
            if (gName.equals(selectedGroup[0])) {
                btn.setTextColor(0xFF4ECCA3);
                btn.setBackgroundColor(0xFF1a1a2e);
            } else {
                btn.setTextColor(0xFF888888);
                btn.setBackgroundColor(0xFF16213e);
            }
            final int idx = i;
            btn.setOnClickListener(v -> {
                selectedGroup[0] = gName;
                for (int j = 0; j < groupButtons.length; j++) {
                    if (j == idx) {
                        groupButtons[j].setTextColor(0xFF4ECCA3);
                        groupButtons[j].setBackgroundColor(0xFF1a1a2e);
                    } else {
                        groupButtons[j].setTextColor(0xFF888888);
                        groupButtons[j].setBackgroundColor(0xFF16213e);
                    }
                }
            });
            groupRow.addView(btn);
        }
        panel.addView(groupRow);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnConfirm = new Button(this);
        btnConfirm.setText("確認加入");
        btnConfirm.setTextColor(0xFF4ECCA3);
        btnConfirm.setTextSize(14);
        btnConfirm.setAllCaps(false);
        btnConfirm.setOnClickListener(v -> {
            if (selectedGroup[0] != null) {
                for (String clip : new ArrayList<>(markedClips)) {
                    String label = clip.length() > 10 ? clip.substring(0, 10) + "…" : clip;
                    commandsHelper.addCommand(selectedGroup[0], label, clip);
                }
                updateStatus("⚡ 已批次加入「" + selectedGroup[0] + "」");
                markedClips.clear();
                updateArmedIndicator();
                showPanel(Panel.CLIPBOARD);
            }
        });

        Button btnCancel = new Button(this);
        btnCancel.setText("取消");
        btnCancel.setTextColor(0xFF888888);
        btnCancel.setTextSize(14);
        btnCancel.setAllCaps(false);
        btnCancel.setOnClickListener(v -> showPanel(Panel.CLIPBOARD));

        actionRow.addView(btnConfirm);
        actionRow.addView(btnCancel);
        panel.addView(actionRow);

        panelContainer.addView(panel);
    }

    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        return prefs.getString("server_url", "http://100.84.86.128:8001");
    }

    private void warmUpConnection() {
        long now = System.currentTimeMillis();
        if (now - lastWarmUpMs < CONNECTION_WARM_UP_DEBOUNCE_MS) {
            return;
        }
        lastWarmUpMs = now;

        Request request = new Request.Builder()
                .url(getServerUrl() + "/")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "Connection warm-up failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    Log.d(TAG, "Connection warm-up HTTP " + response.code());
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });
    }

    private String getAuthPassword() {
        SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        return prefs.getString("auth_password", "guangxin_voice_2026");
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int dataLength = pcmData.length;
        int totalLength = 36 + dataLength;

        ByteBuffer buffer = ByteBuffer.allocate(44 + dataLength);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) 'R'); buffer.put((byte) 'I');
        buffer.put((byte) 'F'); buffer.put((byte) 'F');
        buffer.putInt(totalLength);
        buffer.put((byte) 'W'); buffer.put((byte) 'A');
        buffer.put((byte) 'V'); buffer.put((byte) 'E');

        buffer.put((byte) 'f'); buffer.put((byte) 'm');
        buffer.put((byte) 't'); buffer.put((byte) ' ');
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(sampleRate * channels * bitsPerSample / 8);
        buffer.putShort((short) (channels * bitsPerSample / 8));
        buffer.putShort((short) bitsPerSample);

        buffer.put((byte) 'd'); buffer.put((byte) 'a');
        buffer.put((byte) 't'); buffer.put((byte) 'a');
        buffer.putInt(dataLength);
        buffer.put(pcmData);

        return buffer.array();
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        try {
            if (mIgnoreNextUpdateSelection) {
                mIgnoreNextUpdateSelection = false;
                return;
            }
            maybeScheduleCorrectionCapture(newSelStart, newSelEnd);
        } catch (Exception e) {
            Log.w(TAG, "Correction capture onUpdateSelection failed", e);
        }

        try {
            super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                    candidatesStart, candidatesEnd);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        warmUpConnection();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(connectionWarmUpRunnable);
            mainHandler.postDelayed(connectionWarmUpRunnable, CONNECTION_WARM_UP_INTERVAL_MS);
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(connectionWarmUpRunnable);
            clearServerWaitBudgetCallbacks();
        }
        settlePendingCorrectionCapture();
        dismissSymbolPopup();
        // v6.1: 鍵盤收起 → 釋放螢幕常亮，避免非錄音時殘留 keepScreenOn 拖電
        if (rootView != null) rootView.setKeepScreenOn(false);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onDestroy() {
        dismissSymbolPopup();
        if (isRecording) {
            isRecording = false;
            streamingMode = false;
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
        }
        // v6.1: safety net 釋放螢幕常亮（IME 被系統回收時）
        if (rootView != null) rootView.setKeepScreenOn(false);
        // v5.6: safety net 釋放 WakeLock（IME 被系統殺掉時）
        try {
            if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
                recordingWakeLock.release();
            }
        } catch (Exception ignored) {}
        recordingWakeLock = null;
        if (streamingUpload != null && streamingUpload.isSessionActive()) {
            streamingUpload.cancelSession();
        }
        if (audioStreamWs != null) {
            audioStreamWs.cancel();
            audioStreamWs = null;
            audioStreamActive = false;
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacks(mPendingCorrectionCaptureRunnable);
            mainHandler.removeCallbacks(connectionWarmUpRunnable);
            clearServerWaitBudgetCallbacks();
        }
        if (localSTT != null) {
            localSTT.release();
        }
        if (onDeviceCorrection != null) {
            onDeviceCorrection.release();
        }
        super.onDestroy();
    }
}
