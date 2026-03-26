package com.simon.voiceime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
    private ByteArrayOutputStream pcmBuffer;
    private OkHttpClient httpClient;
    private Handler mainHandler;

    // Helpers
    private ClipboardHelper clipboardHelper;
    private CommandsHelper commandsHelper;
    private LocalSTTHelper localSTT;
    private volatile boolean localSTTReady = false;
    private EnglishMapper englishMapper;
    private StreamingUploadHelper streamingUpload;
    private DataBackupHelper dataBackupHelper;
    private QwenHelper qwenHelper;

    // Streaming state (APPEND mode with VAD)
    private volatile boolean streamingMode = false;
    private volatile boolean streamQwenActive = false;
    private final List<String> streamChunkTexts = new ArrayList<>();

    // v4.1: Audio streaming state (APPEND mode → WebSocket PCM chunks)
    private WebSocket audioStreamWs = null;
    private final List<String> streamedChunks = Collections.synchronizedList(new ArrayList<>());
    private int streamChunkTotal = 0;
    private volatile boolean audioStreamActive = false;

    // UI elements
    private View rootView;
    private TextView statusText;
    private TextView previewText;
    private View btnMic;
    private TextView btnMode;
    private FrameLayout panelContainer;

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

    // Backspace repeat acceleration
    private boolean backspacePressed = false;
    private int backspaceRepeatCount = 0;
    private Runnable backspaceRepeatRunnable;

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
        streamingUpload = new StreamingUploadHelper();
        dataBackupHelper = new DataBackupHelper(this);
        qwenHelper = new QwenHelper(this);

        // 載入上次使用的模式
        loadSavedMode();

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
        View btnClipboard = rootView.findViewById(R.id.btnClipboard);
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
            if (ic != null) ic.commitText(" ", 1);
        });

        // --- 逗號 ---
        View btnComma = rootView.findViewById(R.id.btnComma);
        btnComma.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText("，", 1);
        });

        // --- 句號 ---
        View btnPeriod = rootView.findViewById(R.id.btnPeriod);
        btnPeriod.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText("。", 1);
        });

        // --- 退格（長按加速連刪） ---
        btnBackspace.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    backspacePressed = true;
                    backspaceRepeatCount = 0;
                    // 先刪一個字
                    InputConnection ic0 = getCurrentInputConnection();
                    if (ic0 != null) ic0.deleteSurroundingText(1, 0);
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
                                ic.deleteSurroundingText(deleteCount, 0);
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
                    ic.performEditorAction(ei.imeOptions & EditorInfo.IME_MASK_ACTION);
                } else {
                    ic.commitText("\n", 1);
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

        updateModeUI();
        return rootView;
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

    // ==================== Clipboard Panel ====================

    private void showClipboardPanel() {
        View view = LayoutInflater.from(this).inflate(R.layout.clipboard_panel, panelContainer, true);

        Button btnClose = view.findViewById(R.id.btnClipClose);
        btnClose.setOnClickListener(v -> closePanel());

        RecyclerView recycler = view.findViewById(R.id.clipRecycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        List<String> items = clipboardHelper.getHistory();
        ClipAdapter adapter = new ClipAdapter(items, position -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && position < items.size()) {
                ic.commitText(items.get(position), 1);
                updateStatus("📋 已貼上");
                closePanel();
            }
        });
        recycler.setAdapter(adapter);

        // 右滑 → 加入常用指令
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getAdapterPosition();
                if (pos < 0 || pos >= items.size()) return;
                String clipText = items.get(pos);
                // 恢復 item（不真的移除）
                adapter.notifyItemChanged(pos);
                // 顯示內嵌加入面板
                showAddToCommandsInline(clipText);
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh, float dX, float dY,
                                    int actionState, boolean isActive) {
                if (dX > 0) {
                    // 綠色背景
                    Paint paint = new Paint();
                    paint.setColor(0xFF2d6a4f);
                    c.drawRect(vh.itemView.getLeft(), vh.itemView.getTop(),
                            vh.itemView.getLeft() + dX, vh.itemView.getBottom(), paint);
                    // ⚡+ 文字
                    Paint textPaint = new Paint();
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTextSize(36f);
                    textPaint.setAntiAlias(true);
                    c.drawText("⚡+", vh.itemView.getLeft() + 24,
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

    // Simple RecyclerView Adapter for clipboard
    private static class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.VH> {
        private final List<String> items;
        private final OnItemClick listener;

        interface OnItemClick { void onClick(int position); }

        ClipAdapter(List<String> items, OnItemClick listener) {
            this.items = items;
            this.listener = listener;
        }

        @Override public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.clip_item, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(VH holder, int position) {
            holder.text.setText(items.get(position));
            holder.itemView.setOnClickListener(v -> listener.onClick(position));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView text;
            VH(View v) {
                super(v);
                text = v.findViewById(R.id.clipText);
            }
        }
    }

    // ==================== Commands Panel ====================

    private String currentCmdGroup = null;

    private void showCommandsPanel() {
        View view = LayoutInflater.from(this).inflate(R.layout.commands_panel, panelContainer, true);

        Button btnClose = view.findViewById(R.id.btnCmdClose);
        btnClose.setOnClickListener(v -> closePanel());

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
                        ic.commitText(text, 1);
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
        private final ClipAdapter.OnItemClick listener;

        CmdAdapter(List<CommandsHelper.Command> items, ClipAdapter.OnItemClick listener) {
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
            // v5.3: APPEND 串流模式延遲 1s finalize
            // 繼續錄音讓最後幾個字有時間送出並被 server 處理
            if (currentMode == Mode.APPEND && audioStreamWs != null && streamChunkTotal > 0) {
                mainHandler.post(() -> updateStatus("收尾中..."));
                pendingFinalizeRunnable = () -> stopRecordingAndSend();
                mainHandler.postDelayed(pendingFinalizeRunnable, 1000);
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
            stopRecordingAndSend();
            return;
        }

        // Close any open panel
        closePanel();

        int rawBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        final int bufferSize = rawBufferSize > 0 ? rawBufferSize : SAMPLE_RATE * 2;

        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
        } catch (SecurityException e) {
            updateStatus("需要麥克風權限");
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            updateStatus("麥克風初始化失敗");
            return;
        }

        pcmBuffer = new ByteArrayOutputStream();
        isRecording = true;
        audioRecord.startRecording();

        // v3.6: 停用舊串流模式（VAD 分段）
        streamingMode = false;

        // v4.2: 音訊串流 WebSocket（已修復文字消失 + 亂序 bug）
        if (currentMode == Mode.APPEND) {
            startAudioStreamWs();
        }

        updateStatus("🔴 錄音中...");
        btnMic.setBackgroundColor(getResources().getColor(R.color.mic_active, null));
        if (btnMic instanceof Button) ((Button) btnMic).setText("⏹");

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            int silentFrames = 0;           // 連續靜音 frame 計數
            final int SILENCE_THRESHOLD = 800;  // 16-bit PCM RMS 門檻（靜音偵測）
            final int SILENCE_FRAMES_TO_SPLIT = 8; // ~500ms 靜音觸發分段（取決於 bufferSize/sampleRate）
            final int MIN_CHUNK_BYTES = 32000;  // 最小 1 秒才送（避免 Whisper 幻覺）
            final int MAX_CHUNK_BYTES = 160000; // 最大 5 秒強制送（避免無限累積）
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
                            silentFrames++;
                        } else {
                            silentFrames = 0;
                        }

                        int bufSize = pcmBuffer.size();
                        // 送出條件：(停頓 ≥500ms 且累積 ≥1s) 或 (累積 ≥5s 強制送)
                        boolean pauseDetected = silentFrames >= SILENCE_FRAMES_TO_SPLIT && bufSize >= MIN_CHUNK_BYTES;
                        boolean forceFlush = bufSize >= MAX_CHUNK_BYTES;

                        if (pauseDetected || forceFlush) {
                            byte[] chunkData = pcmBuffer.toByteArray();
                            pcmBuffer.reset();
                            silentFrames = 0;
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
    private void startAudioStreamWs() {
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
                audioStreamActive = true;
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
                        audioStreamActive = false;
                        audioStreamWs = null;

                    } else if ("chunk".equals(type)) {
                        String chunkText = json.optString("text", "");
                        int idx = json.optInt("index", -1);
                        if (!chunkText.isEmpty()) {
                            streamedChunks.add(chunkText);
                            // v4.2.1: Build full composing text from all chunks so far
                            StringBuilder composing = new StringBuilder();
                            for (String c : streamedChunks) composing.append(c);
                            final String composingText = composing.toString();
                            mainHandler.post(() -> {
                                InputConnection ic = getCurrentInputConnection();
                                if (ic != null) {
                                    // setComposingText shows as underlined preview (not committed)
                                    ic.setComposingText(composingText, 1);
                                }
                                updateStatus("串流: " + truncate(chunkText, 15));
                            });
                        }
                        Log.i(TAG, "[AudioStream] chunk#" + idx + " 回傳: '" + chunkText + "'");

                    } else if ("final".equals(type)) {
                        String finalText = json.optString("text", "");
                        streamedChunks.clear();

                        mainHandler.post(() -> {
                            InputConnection ic = getCurrentInputConnection();
                            if (ic != null) {
                                if (!finalText.isEmpty()) {
                                    // v4.3.1: 用 setComposingText 覆蓋預覽 → finishComposingText 確定
                                    // 不再 finishComposingText + delete + commitText（會造成重複）
                                    ic.setComposingText(finalText, 1);
                                    ic.finishComposingText();
                                    updateStatus("完成: " + truncate(finalText, 20));
                                } else {
                                    // Gemini 失敗 — 直接確定現有預覽文字
                                    ic.finishComposingText();
                                    updateStatus("串流完成");
                                }
                            }
                        });
                        Log.i(TAG, "[AudioStream] 最終文字: '" + truncate(finalText, 50) + "'");

                    } else if ("error".equals(type)) {
                        String msg = json.optString("message", "unknown");
                        Log.e(TAG, "[AudioStream] 伺服器錯誤: " + msg);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[AudioStream] 訊息解析錯誤", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.w(TAG, "[AudioStream] WebSocket 連線失敗（將自動 fallback）", t);
                audioStreamActive = false;
                audioStreamWs = null;
                // v4.4.1: 清理殘留的 composing text，避免與 fallback 結果重複
                mainHandler.post(() -> {
                    if (!streamedChunks.isEmpty()) {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            // 把已收到的串流文字確定提交，不浪費
                            StringBuilder sb = new StringBuilder();
                            for (String c : streamedChunks) sb.append(c);
                            if (sb.length() > 0) {
                                ic.setComposingText(sb.toString(), 1);
                                ic.finishComposingText();
                                Log.i(TAG, "[AudioStream] 斷線前已收文字已提交: " + sb);
                            } else {
                                ic.finishComposingText();
                            }
                        }
                        streamedChunks.clear();
                    }
                    // 靜默 fallback，不顯示嚇人的錯誤訊息
                    if (isRecording) {
                        updateStatus("🔴 錄音中...");
                    }
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "[AudioStream] WebSocket 已關閉: " + code + " " + reason);
                audioStreamActive = false;
                audioStreamWs = null;
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

        final boolean wasStreaming = streamingMode;
        streamingMode = false;

        try {
            audioRecord.stop();
            audioRecord.release();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recorder", e);
        }

        try {
            recordingThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        byte[] pcmData = pcmBuffer.toByteArray();
        pcmBuffer = null;

        mainHandler.post(() -> {
            btnMic.setBackgroundColor(getResources().getColor(R.color.mic_idle, null));
            if (btnMic instanceof Button) ((Button) btnMic).setText("🎤");
            updateStatus("辨識中...");
        });

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
            // v4.4.1: 清理可能殘留的 composing text
            mainHandler.post(() -> {
                if (!streamedChunks.isEmpty()) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.finishComposingText();
                    streamedChunks.clear();
                }
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

        // v4.4.1: 如果 WS 中途斷線，此時 audioStreamWs=null 但 composing text 可能殘留
        // 清理後再走 fallback HTTP 路徑，避免重複文字
        if (!streamedChunks.isEmpty()) {
            Log.i(TAG, "[AudioStream] WS 已斷但有殘留 composing text，清理後 fallback HTTP");
            mainHandler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.finishComposingText();
                streamedChunks.clear();
            });
        }

        // === 舊串流模式收尾 ===
        if (wasStreaming && streamingUpload.isStreamingSupported()) {
            finalizeStreamingSession(pcmData);
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
                        sendTextReplace(finalText, bc, ac);
                    } else {
                        sendTextProcess(finalText, modeNow);
                    }
                } else {
                    // 本機 STT 失敗 → fallback 上傳音訊
                    mainHandler.post(() -> updateStatus("本機辨識無結果，上傳中..."));
                    byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
                    sendToWTI(wavData, modeNow);
                }
            }, "LocalSTT-Recognize").start();
            return;
        }

        // fallback: 本機 STT 未就緒 → 上傳音訊（舊流程）
        byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
        sendToWTI(wavData, currentMode);
    }

    /**
     * 串流模式收尾：
     * 1. flush VAD 殘留音訊 → SenseVoice 辨識最後一段 → 上傳最後 chunk
     * 2. 等待 pending segments 完成
     * 3. POST stream-finalize → 取得 LLM 語義校正結果
     * 4. 若 finalize 失敗 → fallback 到整段辨識
     */
    private void finalizeStreamingSession(byte[] pcmData) {
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
                fallbackSingleShot(pcmData);
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
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null && !finalText.isEmpty()) {
                            ic.commitText(finalText, 1);
                            updateStatus("✅ " + truncate(finalText, 20));
                        } else {
                            updateStatus("未辨識到文字");
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
                        fallbackSingleShot(pcmData);
                    } else {
                        // 其他錯誤 → fallback 用本地收集的文字
                        String localConcat;
                        synchronized (streamChunkTexts) {
                            localConcat = String.join("", streamChunkTexts);
                        }
                        if (!localConcat.isEmpty()) {
                            // 用本地拼接的文字送 process-text
                            mainHandler.post(() -> updateStatus("串流整理失敗，使用本地結果"));
                            sendTextProcess(localConcat, Mode.APPEND);
                        } else {
                            fallbackSingleShot(pcmData);
                        }
                    }
                }
            });
        }, "StreamFinalize").start();
    }

    /**
     * Fallback：整段 PCM → SenseVoice 單次辨識 → process-text
     */
    private void fallbackSingleShot(byte[] pcmData) {
        if (localSTTReady) {
            long t0 = System.currentTimeMillis();
            String spokenText = localSTT.recognize(pcmData, SAMPLE_RATE);
            long sttMs = System.currentTimeMillis() - t0;
            if (spokenText != null && !spokenText.isEmpty()) {
                spokenText = englishMapper.apply(spokenText);
                final String finalText = spokenText;
                mainHandler.post(() -> updateStatus("辨識(" + sttMs + "ms): " + truncate(finalText, 15)));
                sendTextProcess(finalText, Mode.APPEND);
            } else {
                mainHandler.post(() -> updateStatus("本機辨識無結果，上傳中..."));
                byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
                sendToWTI(wavData, Mode.APPEND);
            }
        } else {
            byte[] wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
            sendToWTI(wavData, Mode.APPEND);
        }
    }

    // ==================== Network ====================

    private void sendToWTI(byte[] wavData, Mode mode) {
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
                mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    handleWTIResponse(json, mode);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    mainHandler.post(() -> updateStatus("解析錯誤"));
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
        String serverUrl = getServerUrl();

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
                    String text = json.optString("text", "").trim();
                    mainHandler.post(() -> {
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null && !text.isEmpty()) {
                            ic.commitText(text, 1);
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
                    mainHandler.post(() -> updateStatus("解析錯誤"));
                }
            }
        });
    }

    private void sendTextReplace(String spokenText, String beforeCursor, String afterCursor) {
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
                    handleWTIResponse(json, Mode.REPLACE);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing replace-text response", e);
                    mainHandler.post(() -> updateStatus("解析錯誤"));
                }
            }
        });
    }

    private void handleWTIResponse(JSONObject json, Mode mode) {
        mainHandler.post(() -> {
            try {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) {
                    updateStatus("無法取得輸入連線");
                    return;
                }

                switch (mode) {
                    case APPEND: {
                        String text = json.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            ic.commitText(text, 1);
                            updateStatus("✅ " + truncate(text, 20));
                        } else {
                            updateStatus("未辨識到文字");
                        }
                        break;
                    }
                    case REPLACE: {
                        String text = json.optString("text", "").trim();
                        int deleteBefore = json.optInt("delete_before", 0);
                        int deleteAfter = json.optInt("delete_after", 0);
                        String insert = json.optString("insert", text);

                        if (deleteBefore > 0 || deleteAfter > 0) {
                            ic.deleteSurroundingText(deleteBefore, deleteAfter);
                        }
                        if (!insert.isEmpty()) {
                            ic.commitText(insert, 1);
                            updateStatus("🔄 替換: " + truncate(insert, 20));
                        } else {
                            updateStatus("未找到可替換的文字");
                        }
                        break;
                    }
                    case SPELL: {
                        String text = json.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            ic.commitText(text, 1);
                            updateStatus("✏️ 拼字: " + text);
                        } else {
                            updateStatus("拼字失敗");
                        }
                        break;
                    }
                    case TRANSLATE: {
                        String text = json.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            ic.commitText(text, 1);
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
                ic.commitText(" ", 1);
                break;
            case "enter":
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
                ic.commitText(ch, 1);
                // Auto-unshift after one character (unless caps lock)
                if (shiftActive && !capsLock) {
                    shiftActive = false;
                    updateShiftUI();
                }
                break;
        }
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
                ic.performEditorAction(ei.imeOptions & EditorInfo.IME_MASK_ACTION);
            } else {
                ic.commitText("\n", 1);
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
                    if (ic0 != null) ic0.deleteSurroundingText(1, 0);
                    backspaceRepeatRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (!backspacePressed) return;
                            InputConnection ic = getCurrentInputConnection();
                            if (ic != null) {
                                backspaceRepeatCount++;
                                int deleteCount = backspaceRepeatCount < 5 ? 1
                                        : backspaceRepeatCount < 15 ? 2 : 5;
                                ic.deleteSurroundingText(deleteCount, 0);
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

    private String getServerUrl() {
        SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        return prefs.getString("server_url", "http://100.84.86.128:8001");
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
    public void onDestroy() {
        if (isRecording) {
            isRecording = false;
            streamingMode = false;
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
        }
        if (streamingUpload != null && streamingUpload.isSessionActive()) {
            streamingUpload.cancelSession();
        }
        if (audioStreamWs != null) {
            audioStreamWs.cancel();
            audioStreamWs = null;
            audioStreamActive = false;
        }
        if (localSTT != null) {
            localSTT.release();
        }
        super.onDestroy();
    }
}
