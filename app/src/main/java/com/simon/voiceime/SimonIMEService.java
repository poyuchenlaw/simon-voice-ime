package com.simon.voiceime;

import com.simon.voiceime.KeyboardPager.KeyboardMode;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.graphics.drawable.ColorDrawable;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.simon.voiceime.correct.OnDeviceCorrectionEngine;
import com.simon.voiceime.correct.TimeoutWall;

import org.json.JSONObject;
import org.json.JSONArray;

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
import java.util.concurrent.atomic.AtomicBoolean;

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
import okio.BufferedSink;

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
    private static final boolean REPLACE_SERVER_ASR = true; // v6.27 2026-09-09：換＝語音筆記走伺服器辨識，文意精準優先於速度
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    enum Mode { APPEND, REPLACE, SPELL, TRANSLATE }

    private static final String PREF_MODE_KEY = "last_mode";
    private Mode currentMode = Mode.APPEND;
    private KeyboardMode currentKeyboardMode = KeyboardMode.VOICE;
    private volatile boolean isRecording = false;
    private volatile AudioRecord audioRecord;
    private final Object recorderRestartLock = new Object();
    private volatile long lastRecognitionStatusMs;
    private long activeRecordingStartedMs;
    private Thread recordingThread;
    // v5.6: 鎖屏期間保 CPU，避免 AudioRecord underrun + WebSocket 心跳逾時
    private PowerManager.WakeLock recordingWakeLock;
    private ByteArrayOutputStream pcmBuffer;
    private OkHttpClient httpClient;
    private Handler mainHandler;
    private Runnable pendingTextCandidates;
    private long lastTextKeyUptime=-1;

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
    protected ClipboardHelper clipboardHelper;
    private CommandsHelper commandsHelper;
    private LocalSTT localSTT;
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

    private ZhuyinInputController zhuyinInput;
    private InputConnection zhuyinComposingConnection;
    private String retainedTextField="",retainedTextBefore="",retainedText="";
    private int retainedTextEnd=-1;
    private boolean retainedTextNeedsReclaim;
    private String textFieldIdentity(EditorInfo info){return info==null?"":info.packageName+":"+info.fieldId+":"+info.inputType+":"+info.fieldName;}
    private void rememberTextComposition(InputConnection ic,String value){
        if(!textLayoutSelected()||protectedInputField||ic==null||ic!=zhuyinComposingConnection||value.isEmpty())return;
        if(textKeyRender){
            EditorInfo info=getCurrentInputEditorInfo();String field=textFieldIdentity(info);
            int start=retainedTextEnd>=0&&field.equals(retainedTextField)?retainedTextEnd-retainedText.length():
                info!=null&&info.initialSelStart>=0&&info.initialSelEnd>=0?Math.min(info.initialSelStart,info.initialSelEnd):-1;
            if(start<0)return;
            String prefix=field.equals(retainedTextField)&&retainedTextBefore.endsWith(retainedText)?retainedTextBefore.substring(0,retainedTextBefore.length()-retainedText.length()):"";
            retainedTextEnd=start+value.length();retainedTextField=field;retainedTextBefore=prefix+value;retainedText=value;
            return; // editor queries are lifecycle-only; per-key ownership is cached.
        }
        android.view.inputmethod.ExtractedTextRequest request=new android.view.inputmethod.ExtractedTextRequest();
        android.view.inputmethod.ExtractedText extracted=ic.getExtractedText(request,0);
        CharSequence before=ic.getTextBeforeCursor(value.length()+64,0);
        if(extracted==null||extracted.selectionStart!=extracted.selectionEnd||before==null||!before.toString().endsWith(value))return;
        retainedTextEnd=extracted.startOffset+extracted.selectionEnd;
        retainedTextField=textFieldIdentity(getCurrentInputEditorInfo());retainedTextBefore=before.toString();retainedText=value;
    }
    private boolean reclaimTextComposition(EditorInfo info){
        InputConnection ic=getCurrentInputConnection();
        if(ic==null||protectedInputField||retainedTextEnd<0||!retainedTextField.equals(textFieldIdentity(info))||zhuyinInput==null)return false;
        try{
        android.view.inputmethod.ExtractedTextRequest request=new android.view.inputmethod.ExtractedTextRequest();
        android.view.inputmethod.ExtractedText extracted=ic.getExtractedText(request,0);
        CharSequence before=ic.getTextBeforeCursor(retainedTextBefore.length(),0);
        // Recover only the exact former owned range in the exact same field.
        // A new editor, changed text or changed selection cannot be reclaimed.
        if(extracted!=null&&extracted.selectionStart==extracted.selectionEnd&&extracted.startOffset+extracted.selectionEnd==retainedTextEnd&&before!=null&&retainedTextBefore.contentEquals(before)&&ic.setComposingRegion(retainedTextEnd-retainedText.length(),retainedTextEnd)){
            zhuyinComposingConnection=ic;retainedTextNeedsReclaim=false;return true;
        }
        return false;
        }catch(RuntimeException error){Log.w(TAG,"Text composition ownership unavailable: "+error.getClass().getSimpleName());return false;}
    }

    private void discardRetainedTextComposition(){
        cancelPendingTextCandidates();
        zhuyinComposingConnection=null;retainedTextNeedsReclaim=false;
        retainedTextEnd=-1;retainedTextField=retainedTextBefore=retainedText="";
        if(zhuyinInput!=null)zhuyinInput.clear();
        renderZhuyinStreamPreview("");updateTextCount();
        if(textLayoutSelected())renderTextCandidateRows();
    }

    private ZhuyinWordIndex zhuyinWordIndex;
    private ZhuyinAssociationHistory zhuyinAssociationHistory;
    private String lastCommittedZhuyinWord;
    private String renderedZhuyinCandidateKind = "engine";
    private HorizontalScrollView boCandidateScroll;
    private LinearLayout boCandidateItems;
    private LinearLayout boWordCandidateItems;
    private HorizontalScrollView boWordCandidateScroll;
    private int textCandidateTaps;
    private TextView boStreamPreview;
    private TextView boCandidateRightHint;
    private String zhuyinCommitVia="other";
    private List<String> renderedZhuyinCandidates = Collections.emptyList();
    private int renderedZhuyinCandidateCount = 0;

    private ImeTelemetry imeTelemetry;
    private VoicePendingQueue voicePendingQueue;
    private volatile boolean recordingFinalizing;
    private final List<Runnable> afterRecordingFinalization=new ArrayList<>();
    private final Set<Integer> discardedVoiceGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int silenceStatusGeneration;
    private final AtomicBoolean pendingDrainRunning = new AtomicBoolean(false);
    private final java.util.concurrent.ScheduledExecutorService pendingVoiceExecutor=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"VoicePendingUploader");t.setDaemon(true);return t;
    });
    private java.util.concurrent.ScheduledFuture<?> pendingDrainTask;
    private final Set<String> discardedProtectedSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String activePendingSessionId;
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> pendingSessionByGeneration = new java.util.concurrent.ConcurrentHashMap<>();
    private ConnectivityManager.NetworkCallback voiceNetworkCallback;
    private volatile long lastVoiceChunkElapsed;
    private volatile VoiceSessionGuard voiceSessionGuard;
    private final java.util.concurrent.ConcurrentHashMap<Integer,String> guardStopReasons = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Integer> guardStoppedGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile long lastVoiceStallEventElapsed;
    // Delivery receipts suppress duplicate text; audio custody receipts alone permit PCM deletion.
    private final java.util.concurrent.ConcurrentHashMap<String,java.util.concurrent.CountDownLatch> audioReceiptWaiters=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> deliveredVoiceGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> serverFinalGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> serverFullAudioGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Integer> fullAudioRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean lastCommitInsertedOrCopied;
    private volatile boolean lastCommitClipboardWritten;
    private volatile String lastCommitMethod="clipboard";
    private final java.util.concurrent.atomic.AtomicBoolean pendingQuotaWarningShown=new java.util.concurrent.atomic.AtomicBoolean(false);
    private TouchLearningStore touchLearning;
    private TouchShadowLearning touchShadow;
    /** Blocks telemetry and local learning for password/no-personalized-learning editors. */
    private boolean protectedInputField = false;
    private AiSentencePhone sentencePhone;
    private String sentenceInstalledCommit;
    private String touchSessionId = java.util.UUID.randomUUID().toString();
    private BopomofoKeyTouch pendingBopomofoKeyTouch;


    private static final class BopomofoKeyTouch {
        final String key; final float x; final float y; final float centerX; final float centerY;
        JSONObject shadow = new JSONObject();
        BopomofoKeyTouch(String key, float x, float y, float centerX, float centerY) {
            this.key = key; this.x = x; this.y = y; this.centerX = centerX; this.centerY = centerY;
        }
    }

    // Streaming state (APPEND mode with VAD)
    private volatile boolean streamingMode = false;
    private volatile boolean streamQwenActive = false;
    private final List<String> streamChunkTexts = new ArrayList<>();

    // v4.1: Audio streaming state (APPEND mode → WebSocket PCM chunks)
    private WebSocket audioStreamWs = null;
    private final java.util.concurrent.ConcurrentHashMap<String,OpusStreamEncoder> opusEncoders=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile int opusDisabledGeneration=-1;
    private volatile boolean opusDisabledForProcess;
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
    // v6.30: keep the accepted result length even after the ordered commit queue drains.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> acceptedTextLengths =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> appendRescueCandidates =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Long> recordingDurationMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int REPLACE_SHORT_CHARS = 6;
    private static final long REPLACE_LONG_AUDIO_MS = 5000;
    private int rescueExtraChars = TextLossGuard.MIN_EXTRA_CHARS;
    private double rescueRatio = TextLossGuard.MIN_RATIO;
    private int replaceShortChars = REPLACE_SHORT_CHARS;
    private long replaceLongAudioMs = REPLACE_LONG_AUDIO_MS;
    private final Object utteranceCommitLock = new Object();
    private int nextGenToCommit = 1;
    private final java.util.TreeMap<Integer, String> pendingCommits = new java.util.TreeMap<>();
    private final Set<Integer> completedGenerations = new HashSet<>();
    // Phone memory holds only bounded previews; complete recording stays in file custody.
    private static final int MAX_FULL_PCM_BYTES = 256 * 1024; // Bounded preview; complete PCM streams to file.

    // Metadata only; the existing telemetry HandlerThread owns disk and upload work.
    private void recordVoiceStage(String stage,int gen,String id,String outcome) {
        try {
            if(imeTelemetry==null||protectedInputField||gen<=0||id==null||id.isEmpty()||discardedProtectedSessions.contains(id))return;
            JSONObject event=new JSONObject().put("phase","voice_stage").put("stage",stage)
                    .put("client_session_id",id).put("generation",gen)
                    .put("elapsed_ms",SystemClock.elapsedRealtime()).put("uptime_ms",SystemClock.uptimeMillis())
                    .put("ms_since_start",0).put("audio_ms",0).put("chars",0).put("close_code",0)
                    .put("close_reason","").put("insert_method","").put("bytes",0).put("attempts",0);
            if("release".equals(stage))event.put("next_generation",nextGenToCommit);
            if("commit".equals(stage)){event.put("commit_outcome",outcome);event.put("insert_method",lastCommitMethod);}
            imeTelemetry.record("voice","voice",event,false);
        } catch(Exception e) {Log.w(TAG,"Voice stage telemetry dropped",e);}
    }

    private void recordVoiceEvent(String phase, String id, long audioMs, int chars, int closeCode,
                                  String closeReason, String insertMethod, long bytes, int attempts) {
        if (imeTelemetry == null || protectedInputField) return;
        try {
            String reason = closeReason == null ? "" : closeReason;
            if (reason.length() > 80) reason = reason.substring(0, 80);
            long sinceStart=Math.max(0L,SystemClock.elapsedRealtime()-activeRecordingStartedMs);
            if(id!=null&&voicePendingQueue!=null){try{long started=Long.parseLong(voicePendingQueue.startedAt(id));if(started>0)sinceStart=Math.max(0L,System.currentTimeMillis()-started);}catch(Exception ignored){}}
            imeTelemetry.record("voice", "voice", new JSONObject().put("phase",phase)
                    .put("client_session_id",id == null ? "" : id)
                    .put("ms_since_start", sinceStart)
                    .put("audio_ms",Math.max(0L,audioMs)).put("chars",Math.max(0,chars))
                    .put("close_code",closeCode).put("close_reason",reason)
                    .put("insert_method",insertMethod == null ? "" : insertMethod)
                    .put("bytes",Math.max(0L,bytes)).put("attempts",Math.max(0,attempts)),false);
        } catch (Exception e) { Log.w(TAG,"voice telemetry event skipped",e); }
    }

    private void registerVoiceNetworkCallback() {
        try {
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(cm==null)return;
            voiceNetworkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){
                    VoicePendingQueue queue=voicePendingQueue;
                    if(queue==null)return;
                    queue.execute(() -> {for(String id:queue.pendingOldestFirst())queue.markPending(id,"network_available");drainPendingVoiceQueue();});
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),voiceNetworkCallback);
        } catch(Exception e){Log.w(TAG,"voice network callback unavailable",e);}
    }

    private String fetchServerArchiveText(String sessionId)throws IOException {
        MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("client_session_id",sessionId).build();
        Request.Builder rb=new Request.Builder().url(getServerUrl()+"/v1/audio-archive/transcribe").post(body);
        String auth=getAuthPassword();if(auth!=null&&!auth.isEmpty())rb.addHeader("Authorization","Bearer "+auth);
        OkHttpClient client=httpClient.newBuilder().readTimeout(150,TimeUnit.SECONDS).callTimeout(160,TimeUnit.SECONDS).build();
        try(Response response=client.newCall(rb.build()).execute()) {
            if(!response.isSuccessful())throw new VoicePendingQueue.UploadFailure(response.code(),response.body()==null?"":response.body().string());
            String raw=response.body()==null?"":response.body().string();
            VoicePendingQueue.parseSuccessfulResponse(raw);
            return raw;
        }
    }

    private void drainPendingVoiceQueue() {
        VoicePendingQueue queue=voicePendingQueue;if(queue==null)return;
        if(!pendingDrainRunning.compareAndSet(false,true))return;
        if(pendingVoiceExecutor.isShutdown()){pendingDrainRunning.set(false);return;}
        pendingVoiceExecutor.execute(()->{
          try {
            queue.runIO(() -> null);
            for(String id:queue.pendingServerDiscards()) {
                if(!queue.serverDiscardDue(id,System.currentTimeMillis()))continue;
                Request.Builder rb=new Request.Builder().url(getServerUrl()+"/v1/audio-archive?client_session_id="+id).delete();
                String auth=getAuthPassword();if(auth!=null&&!auth.isEmpty())rb.addHeader("Authorization","Bearer "+auth);
                try(Response response=httpClient.newBuilder().readTimeout(2,TimeUnit.SECONDS).callTimeout(3,TimeUnit.SECONDS).build().newCall(rb.build()).execute()) {
                    boolean confirmed=response.isSuccessful()&&response.body()!=null&&new JSONObject(response.body().string()).optBoolean("discarded");
                    if(response.code()==404||response.code()==405) {
                        Request.Builder receipt=new Request.Builder().url(getServerUrl()+"/v1/audio-receipt?client_session_id="+id);
                        if(auth!=null&&!auth.isEmpty())receipt.addHeader("Authorization","Bearer "+auth);
                        try(Response custody=httpClient.newBuilder().callTimeout(3,TimeUnit.SECONDS).build().newCall(receipt.build()).execute()) {
                            confirmed=custody.code()==404&&custody.body()!=null
                                    &&"Audio receipt not found".equals(new JSONObject(custody.body().string()).optString("detail"));
                        }
                    }
                    if(!confirmed)throw new IOException("discard not confirmed HTTP "+response.code());
                    queue.confirmServerDiscard(id);
                }catch(Exception e) {queue.serverDiscardFailed(id);Log.w(TAG,"Server audio discard pending; will retry",e);}
            }
            for(String id:queue.pendingOldestFirst()){
                if(id.equals(activePendingSessionId)||pendingSessionByGeneration.containsValue(id)||!queue.due(id,System.currentTimeMillis()))continue;
                queue.uploadOne(id,new VoicePendingQueue.Uploader() {
                  @Override public String transcribe(java.io.File pcm,String sessionId,int sampleRate)throws Exception {return upload(pcm,sessionId,sampleRate,false);}
                  @Override public String archiveChunk(java.io.File pcm,String sessionId,int sampleRate)throws Exception {return upload(pcm,sessionId,sampleRate,true);}
                  private String upload(java.io.File pcm,String sessionId,int sampleRate,boolean custodyOnly)throws Exception {
                    if(!custodyOnly&&queue.receiptConfirmed(sessionId))return fetchServerArchiveText(sessionId);
                    MultipartBody.Builder form=new MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("file","recording.wav",pcmWavBody(pcm,sampleRate))
                            .addFormDataPart("client_session_id",sessionId);
                    if(custodyOnly)form.addFormDataPart("custody_only","true");
                    MultipartBody body=form.build();
                    Request.Builder rb=new Request.Builder().url(getServerUrl()+"/v1/audio-archive").post(body);
                    String auth=getAuthPassword();if(auth!=null&&!auth.isEmpty())rb.addHeader("Authorization","Bearer "+auth);
                    long seconds=Math.max(60L,Math.min(150L,30L+(long)Math.ceil(pcm.length()/(sampleRate*2.0))));
                    OkHttpClient uploadClient=httpClient.newBuilder().readTimeout(seconds,TimeUnit.SECONDS).callTimeout(seconds+10,TimeUnit.SECONDS).build();
                    try(Response response=uploadClient.newCall(rb.build()).execute()){
                        if(response.code()==409) {
                            mainHandler.post(() -> updateStatus("錄音備份衝突，音訊保留待重試"));
                            if(queue.backupFailed(sessionId)) {
                                Request.Builder receipt=new Request.Builder().url(getServerUrl()+"/v1/audio-receipt?client_session_id="+sessionId);
                                if(auth!=null&&!auth.isEmpty())receipt.addHeader("Authorization","Bearer "+auth);
                                try(Response custody=uploadClient.newCall(receipt.build()).execute()) {
                                    if(custody.isSuccessful()&&custody.body()!=null) {
                                        JSONObject held=new JSONObject(custody.body().string());
                                        if(queue.acceptServerCopyAfterWriteFailure(sessionId,held)) {
                                            mainHandler.post(() -> updateStatus("本機錄音備份失敗，伺服器已保存音訊"));
                                            return fetchServerArchiveText(sessionId);
                                        }
                                    }
                                }
                            }
                            throw new IOException("audio custody conflict");
                        }
                        if(response.code()==413)throw new VoicePendingQueue.PayloadTooLargeException();
                        if(!response.isSuccessful())throw new VoicePendingQueue.UploadFailure(response.code(),response.body()==null?"":response.body().string());
                    String raw=response.body()==null?"":response.body().string();
                    if(!custodyOnly)VoicePendingQueue.parseSuccessfulResponse(raw);
                    return raw;
                    }
                  }
                  @Override public String finalizeParent(JSONObject identity,org.json.JSONArray children,int sampleRate)throws Exception {return parent(identity,children,false);}
                  @Override public String archiveParent(JSONObject identity,org.json.JSONArray children,int sampleRate)throws Exception {return parent(identity,children,true);}
                  private String parent(JSONObject identity,org.json.JSONArray children,boolean custodyOnly)throws Exception {
                    MultipartBody body=new MultipartBody.Builder().setType(MultipartBody.FORM)
                            .addFormDataPart("client_session_id",identity.getString("client_session_id"))
                            .addFormDataPart("parent_byte_count",String.valueOf(identity.getLong("byte_count")))
                            .addFormDataPart("parent_sha256",identity.getString("sha256"))
                            .addFormDataPart("child_receipts",children.toString()).addFormDataPart("custody_only",String.valueOf(custodyOnly)).build();
                    Request.Builder rb=new Request.Builder().url(getServerUrl()+"/v1/audio-archive/transcribe").post(body);
                    String auth=getAuthPassword();if(auth!=null&&!auth.isEmpty())rb.addHeader("Authorization","Bearer "+auth);
                    OkHttpClient client=httpClient.newBuilder().readTimeout(150,TimeUnit.SECONDS).callTimeout(160,TimeUnit.SECONDS).build();
                    try(Response response=client.newCall(rb.build()).execute()) {
                        if(!response.isSuccessful())throw new VoicePendingQueue.UploadFailure(response.code(),response.body()==null?"":response.body().string());
                        String raw=response.body()==null?"":response.body().string();if(!custodyOnly)VoicePendingQueue.parseSuccessfulResponse(raw);return raw;
                    }
                  }
                },new VoicePendingQueue.Delivery() {
                  @Override public void archiveToHistory(String text,String startedAt) {
                    if(clipboardHelper==null)throw new IllegalStateException("clipboard history unavailable");
                    clipboardHelper.addToHistory(text);
                  }
                  @Override public void deliver(String text,String startedAt) {
                    if(!VoiceResultText.isEmptyFinal(text)){
                        if(clipboardHelper==null)throw new IllegalStateException("clipboard history unavailable");
                        if(!copyToSystemClipboard(text))throw new IllegalStateException("clipboard unavailable; retry delivery");
                        clipboardHelper.addToHistory(text);
                        showPendingVoiceNotification(startedAt);
                    }
                    recordVoiceEvent("pending_uploaded",id,queue.audioMs(id),text==null?0:text.length(),0,"","clipboard",queue.totalBytes(),queue.attempts(id));
                  }
                },(phase,sessionId,audioMs,bytes,attempts,error)->{
                    if("silence".equals(phase)&&!queue.backupFailed(sessionId)) { mainHandler.post(this::showNoVoiceStatus); }
                    if("pending_failed".equals(phase)){
                        recordVoiceEvent(phase,sessionId,audioMs,0,0,error,"",bytes,attempts);
                        if(queue.receiptConfirmed(sessionId)&&error.contains("empty transcript for non-silent original audio"))
                            mainHandler.post(() -> showPendingVoiceNotice("尚未取得辨識文字；原音已保留，會自動重試"));
                    }
                });
                if(queue.totalBytes()>500L*1024*1024)showPendingVoiceQuotaWarning();
            }
          } finally { pendingDrainRunning.set(false); schedulePendingDrain(); }
        });
    }

    private synchronized void schedulePendingDrain(){
        VoicePendingQueue q=voicePendingQueue;if(q==null||pendingVoiceExecutor.isShutdown())return;
        if(pendingDrainTask!=null){pendingDrainTask.cancel(false);pendingDrainTask=null;}
        long wait=Long.MAX_VALUE;
        for(String id:q.pendingOldestFirst())if(!id.equals(activePendingSessionId)&&!pendingSessionByGeneration.containsValue(id))wait=Math.min(wait,q.retryDelayMs(id));
        for(String id:q.pendingServerDiscards())wait=Math.min(wait,q.serverDiscardDelayMs(id));
        if(wait!=Long.MAX_VALUE)pendingDrainTask=pendingVoiceExecutor.schedule(this::drainPendingVoiceQueue,wait,TimeUnit.MILLISECONDS);
    }

    private RequestBody pcmWavBody(java.io.File pcm,int sampleRate) {
        return new RequestBody(){
            @Override public MediaType contentType(){return MediaType.parse("audio/wav");}
            @Override public long contentLength(){return voicePendingQueue.runIO(() -> pcm.length()+44L);}
            @Override public void writeTo(BufferedSink sink)throws IOException {
                try {
                    long dataLength=voicePendingQueue.runIO(() -> pcm.length());
                    java.nio.ByteBuffer h=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                    h.put(new byte[]{'R','I','F','F'}).putInt((int)(dataLength+36)).put(new byte[]{'W','A','V','E'}).put(new byte[]{'f','m','t',' '})
                            .putInt(16).putShort((short)1).putShort((short)1).putInt(sampleRate).putInt(sampleRate*2).putShort((short)2).putShort((short)16)
                            .put(new byte[]{'d','a','t','a'}).putInt((int)dataLength);
                    sink.write(h.array());
                    java.io.InputStream in=voicePendingQueue.runIO(() -> new java.io.FileInputStream(pcm));
                    try {
                        byte[] buffer=new byte[16*1024];
                        int n;
                        while((n=voicePendingQueue.runIO(() -> in.read(buffer)))!=-1)sink.write(buffer,0,n);
                    } finally {voicePendingQueue.runIO(() -> {in.close();return null;});}
                } catch(IllegalStateException e) {throw new IOException("durable audio read failed",e);}
            }
        };
    }

    private void showPendingVoiceNotification(String startedAt) {
        try {
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm==null)return;
            String channel="voice_pending";
            if(android.os.Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(channel,"語音補傳",NotificationManager.IMPORTANCE_DEFAULT));
            String time=startedAt;
            try{time=new java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(Long.parseLong(startedAt)));}catch(Exception ignored){}
            Notification.Builder b=android.os.Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
            nm.notify(671,b.setOnlyAlertOnce(true).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("已恢復 " + recoveredVoiceCount.incrementAndGet() + " 段").setContentText("開啟鍵盤 📋 歷史，點選文字取回（"+time+"）").setAutoCancel(true).build());
        }catch(Exception e){Log.w(TAG,"pending voice notification failed",e);}
    }
    private void showPendingVoiceNotice(String message) {
        try {
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm==null)return;
            String channel="voice_pending";
            nm.createNotificationChannel(new NotificationChannel(channel,"語音補傳",NotificationManager.IMPORTANCE_DEFAULT));
            nm.notify(671,new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOnlyAlertOnce(true).setContentTitle(recoveredVoiceCount.get()>0?"已恢復 "+recoveredVoiceCount.get()+" 段；"+message:message)
                    .setContentIntent(android.app.PendingIntent.getActivity(this,671,new Intent(this,SettingsActivity.class),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE))
                    .setContentText(message.startsWith("尚未取得辨識文字")?"原音已由伺服器保存；取得文字後會自動送出":"錄音仍保留；點選開啟設定，可手動重試").build());
        }catch(Exception e){Log.w(TAG,"pending voice notice failed",e);}
    }
    private void showPendingVoiceQuotaWarning(){try{NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(nm==null)return;
        if(!pendingQuotaWarningShown.compareAndSet(false,true))return;
        String channel="voice_pending";if(android.os.Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(channel,"語音補傳",NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b=android.os.Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
        nm.notify(646,b.setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("待補傳語音已超過 500 MB").setContentText("音訊仍保留在手機，等待網路上傳").setOngoing(true).build());
    }catch(Exception e){Log.w(TAG,"pending queue warning failed",e);}}

    // v6.20 fields
    private final Set<String> markedClips = new LinkedHashSet<>();
    private String aiContextText = null;
    private int aiContextCount = 0;
    private int fieldGeneration = 0;
    private final java.util.Map<Integer, java.util.concurrent.FutureTask<LineContextAccessibilityService.Snapshot>> lineCaptures = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, LineContextAccessibilityService.Snapshot> lineRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Integer, Integer> lineFields = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> lineGenerations=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<Integer,String> lineDrafts=new java.util.concurrent.ConcurrentHashMap<>();
    private String lineDraft() {
        InputConnection ic=getCurrentInputConnection();if(ic==null)return null;
        CharSequence selected=ic.getSelectedText(0);if(selected!=null&&selected.length()>0)return null;
        CharSequence before=ic.getTextBeforeCursor(4000,0),after=ic.getTextAfterCursor(4000,0);
        if(before==null||after==null)return null;
        return before.toString()+"\u0000"+after.toString();
    }
    private boolean lineGhostwriterField() {
        EditorInfo editor=getCurrentInputEditorInfo();
        return !isWatchService() && !protectedInputField && currentMode==Mode.REPLACE && editor!=null
            && LineContextAccessibilityService.LINE_PACKAGE.equals(editor.packageName);
    }
    private void invalidateLineGhostwriter() {
        lineCaptures.clear(); lineRequests.clear(); lineFields.clear(); lineDrafts.clear();
    }
    private void sendLineGhostwriterAudio(byte[] wavData, int gen) {
        java.util.concurrent.FutureTask<LineContextAccessibilityService.Snapshot> capture=lineCaptures.get(gen);
        Integer field=lineFields.get(gen);
        if(capture==null||field==null)return;
        new Thread(() -> {
            LineContextAccessibilityService.Snapshot snapshot=null;
            try {snapshot=capture.get(1500,java.util.concurrent.TimeUnit.MILLISECONDS);} catch(Exception unavailable) { }
            final LineContextAccessibilityService.Snapshot result=snapshot;
            mainHandler.post(() -> {
                if(!lineGhostwriterField()||field!=fieldGeneration||gen!=activeUtteranceGeneration||!lineFields.containsKey(gen))return;
                if(result==null||!result.current()||!lineDrafts.containsKey(gen)||!lineDrafts.get(gen).equals(lineDraft())) {
                    keepPendingModeResult(gen,"GHOSTWRITER","line_context_unavailable");
                    updateStatus("LINE 對話無法讀取或已變動；請授權嘴替並重錄，不會填入口述顧慮");return;
                }
                lineRequests.put(gen,result);
                sendAiCommandAudio(wavData,"",gen);
            });
        },"LINE-Reply-Context").start();
    }
    private boolean autoVocabEnabled = true;
    private Set<String> enrolledVocab = new HashSet<>();
    private long lastEnrollTimeMs = 0;
    private int enrollCountThisMinute = 0;
    private long enrollMinuteStartMs = 0;
    private int enrollCountToday = 0;
    private long enrollDayStartMs = 0;
    private boolean folderNamingMode = false;

    // UI elements
    protected View rootView;
    protected TextView statusText;
    private TextView previewText;
    protected View btnMic;
    private TextView btnMode;
    private TextView btnClipboard;   // v6.20: promoted to field (was local in onCreateInputView)
    private FrameLayout panelContainer;
    private PopupWindow symbolPopup;
    private View clipMarkFooter;
    private TextView clipMarkCount;

    // Keyboard switching
    private View voiceKeyboard;
    private View bopomofoKeyboard;
    private LayoutDiagnostics layoutDiagnostics;
    private PreviewReveal readingReveal;
    private PreviewReveal textReveal;
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

    private static final long OFFLINE_CORRECTION_TIMEOUT_MS = 2_000L;
    // 等伺服器語意校正的預算。實測伺服器 p50 約 650ms、尾端可達 1.8s；
    // 端上確定性校正約 11ms。超過此預算就先給端上結果，不讓使用者空等。
    private static final long SERVER_WAIT_BUDGET_MS = 1_200L;
    private static final String OFFLINE_CORRECTION_FAILED_SENTINEL =
            "\uE000OFFLINE_CORRECTION_FAILED\uE000";

    protected boolean isWatchService() { return false; }
    protected void onWatchAudio(byte[] pcm) {}
    protected boolean isVoiceRecording() { return isRecording; }

    /** Keep sherpa-backed implementations out of the common/watch compile classpath. */
    private LocalSTT createLocalSTT() {
        try {
            Class<?> type = Class.forName("com.simon.voiceime.LocalSTTHelper");
            return (LocalSTT) type.getConstructor(Context.class).newInstance(this);
        } catch (Throwable t) {
            Log.i(TAG, "phone-only local STT unavailable");
            return null;
        }
    }

    /** Keep correction's sherpa-backed punctuation implementation phone-only as well. */
    private OnDeviceCorrectionEngine createOnDeviceCorrectionEngine() {
        try {
            Class<?> type = Class.forName(
                    "com.simon.voiceime.correct.PhoneOnDeviceCorrectionEngine");
            return (OnDeviceCorrectionEngine) type.getConstructor(Context.class).newInstance(this);
        } catch (Throwable t) {
            Log.i(TAG, "phone-only correction unavailable");
            return null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if(!isWatchService())AiSentencePhone.migrateAutoApply(this);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        mainHandler = new Handler(Looper.getMainLooper());
        clipboardHelper = new ClipboardHelper(this);
        SharedPreferences rescuePrefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
        rescueExtraChars = rescuePrefs.getInt("text_loss_min_extra_chars", TextLossGuard.MIN_EXTRA_CHARS);
        rescueRatio = rescuePrefs.getFloat("text_loss_min_ratio", (float) TextLossGuard.MIN_RATIO);
        replaceShortChars = Math.max(0, rescuePrefs.getInt("replace_short_chars", REPLACE_SHORT_CHARS));
        replaceLongAudioMs = Math.max(1, rescuePrefs.getLong("replace_long_audio_ms", REPLACE_LONG_AUDIO_MS));
        if (isWatchService()) {
            currentMode = Mode.REPLACE; // Watch owns its mode and HTTP dispatch; no phone streaming.
            return;
        }
        imeTelemetry = ImeTelemetry.install(this);
        voicePendingQueue = VoicePendingQueue.getInstance(getFilesDir(),SAMPLE_RATE);
        voicePendingQueue.reportOversizeDiscards((phase,id,ms,bytes,attempts,error) -> {
            if (imeTelemetry != null) try {
                imeTelemetry.record("voice", "voice", new JSONObject().put("phase",phase).put("audio_ms",ms).put("bytes",bytes),false);
            } catch (Exception e) { Log.w(TAG,"oversize discard telemetry failed",e); }
        });
        if(voicePendingQueue.totalBytes()>500L*1024*1024)showPendingVoiceQuotaWarning();
        registerVoiceNetworkCallback();
        drainPendingVoiceQueue();
        commandsHelper = new CommandsHelper(this);
        englishMapper = new EnglishMapper(this);
        onDeviceCorrection = createOnDeviceCorrectionEngine();
        new Thread(() -> {
            if (onDeviceCorrection == null) return;
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
        try { zhuyinWordIndex = ZhuyinWordIndex.open(this); }
        catch (Exception error) { Log.e(TAG, "Initial-symbol dictionary unavailable", error); }
        zhuyinAssociationHistory = new ZhuyinAssociationHistory(new java.io.File(getFilesDir(), "zhuyin_associations.tsv"));
        zhuyinInput = new ZhuyinInputController(createZhuyinEngine(), zhuyinWordIndex);
        zhuyinInput.setRetypeEngineFactory(this::createZhuyinEngine);
        zhuyinInput.setTextLayout(textLayoutSelected());
        touchLearning = new TouchLearningStore(this);
        touchShadow = new TouchShadowLearning(this, mainHandler);
        try { sentencePhone=new AiSentencePhone(this,mainHandler,new AiSentencePhone.Host(){
            public ZhuyinInputController controller(){return zhuyinInput;}
            public InputConnection ownedConnection(){InputConnection ic=getCurrentInputConnection();return ic!=null&&ic==zhuyinComposingConnection?ic:null;}
            public boolean allowed(){EditorInfo info=getCurrentInputEditorInfo();return !protectedInputField&&info!=null&&info.inputType!=0&&info.packageName!=null&&currentKeyboardMode==KeyboardMode.BOPOMOFO;}
            public void replace(ZhuyinInputController controller){controller.inheritCommitTelemetry(zhuyinInput);zhuyinInput=controller;applyZhuyinState(controller.state());}
            public void render(){renderSentenceOptions();}
            public void commitSuggestion(){applyZhuyinState(zhuyinInput.press("enter"));}
        }); } catch(Exception unavailable){Log.w(TAG,"Sentence layer unavailable; local keyboard retained",unavailable);}


        // 背景初始化本機 STT
        localSTT = createLocalSTT();
        new Thread(() -> {
            if (localSTT == null) return;
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
        selectionRevision++;clearExternalSelection();
        boolean wasProtected=protectedInputField;
        protectedInputField = isProtectedInputField(attribute);
        clearRankingContext();
        if(!restarting||protectedInputField||wasProtected)lastCommittedZhuyinWord=null;
        if(protectedInputField)LocalConversationContext.clear();
        String field=textFieldIdentity(attribute);
        if(!restarting||!field.equals(retainedTextField))invalidateLineGhostwriter();
        if(restarting&&!field.equals(retainedTextField))fieldGeneration++;
        boolean retained=zhuyinInput!=null&&!zhuyinInput.textPreview().isEmpty();
        if(!field.equals(retainedTextField)||protectedInputField!=wasProtected
                ||protectedInputField&&retained&&(!restarting||zhuyinComposingConnection!=getCurrentInputConnection()))
            discardRetainedTextComposition();
        else if(retained&&!protectedInputField)retainedTextNeedsReclaim=true;
        retainedTextField=field;
        if(sentencePhone!=null)sentencePhone.changed(true);
        sentenceInstalledCommit=null;
        if(protectedInputField&&!wasProtected&&isRecording){
            discardProtectedRecording();
            activePendingSessionId=null;isRecording=false;
            AudioRecord recorder=audioRecord;
            if(recorder!=null){synchronized(recorderRestartLock){try{recorder.stop();}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}}}
            if(audioStreamWs!=null){try{audioStreamWs.close(1000,"protected field");}catch(Exception ignored){}audioStreamWs=null;}
            try{if(recordingWakeLock!=null&&recordingWakeLock.isHeld())recordingWakeLock.release();}catch(Exception ignored){}
            recordingWakeLock=null;
            Log.i(TAG,"Voice recording discarded after protected-field switch");
        }
        if (zhuyinInput != null) zhuyinInput.setLearningEnabled(!protectedInputField);
        RimeVocabularyInstaller.setLearningEnabled(!protectedInputField);
        if (protectedInputField) RemotePrivateVocabSync.cancelForProtectedField();
        else if (zhuyinWordIndex != null) RemotePrivateVocabSync.refreshOnce(this, zhuyinWordIndex);
        if (!restarting) touchSessionId = java.util.UUID.randomUUID().toString();
        if (touchShadow != null && (!restarting || protectedInputField)) touchShadow.invalidate();
        // v6.20: only treat a genuinely new field (not an internal restart) as a field switch.
        // On a real switch, bump the generation guard and disarm any pending AI material so it
        // never leaks into an unrelated field. Keep state on restarting==true.
        if (!restarting) {
            zhuyinComposingConnection = null;
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
                    if(event.getAction()==MotionEvent.ACTION_UP && currentKeyboardMode==KeyboardMode.BOPOMOFO)
                        recordBopomofoTouch(v,"backspace",event);
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
        setupPunctuationKey(btnComma, "，", ",");

        // --- 句號 ---
        View btnPeriod = rootView.findViewById(R.id.btnPeriod);
        setupPunctuationKey(btnPeriod, "。", ".");

        setupSymbolLauncher(rootView.findViewById(R.id.btnFullWidth), SymbolPopupSpec.FULL_WIDTH, 5, "，");
        setupSymbolLauncher(rootView.findViewById(R.id.btnHalfWidth), SymbolPopupSpec.HALF_WIDTH, 6, ",");

        // --- 退格（長按加速連刪） ---
        btnBackspace.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (touchShadow != null) touchShadow.invalidate();
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

        // --- 切換注音鍵盤（短按）/ 跳轉輸入法（長按） ---
        btnSwitchIME.setOnClickListener(v -> switchKeyboard(KeyboardMode.BOPOMOFO));
        btnSwitchIME.setOnLongClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
            }
            return true;
        });

        // --- 鍵盤切換設定 ---
        voiceKeyboard = rootView.findViewById(R.id.voiceKeyboard);
        bopomofoKeyboard = rootView.findViewById(R.id.bopomofoKeyboard);
        englishKeyboard = rootView.findViewById(R.id.englishKeyboard);
        numbersKeyboard = rootView.findViewById(R.id.numbersKeyboard);

        // 設定注音、英文鍵盤和數字鍵盤的按鍵處理
        setupTypingKeyboard(bopomofoKeyboard);
        setupTypingKeyboard(englishKeyboard);
        setupTypingKeyboard(numbersKeyboard);

        // v6.23: 英文預測列
        enSuggest0 = rootView.findViewById(R.id.enSuggest0);
        enSuggest1 = rootView.findViewById(R.id.enSuggest1);
        enSuggest2 = rootView.findViewById(R.id.enSuggest2);
        if (enSuggest0 != null) enSuggest0.setOnClickListener(v -> applyEnglishSuggestion(0));
        if (enSuggest1 != null) enSuggest1.setOnClickListener(v -> applyEnglishSuggestion(1));
        if (enSuggest2 != null) enSuggest2.setOnClickListener(v -> applyEnglishSuggestion(2));

        boCandidateScroll = rootView.findViewById(R.id.boCandidateScroll);
        boCandidateItems = rootView.findViewById(R.id.boCandidateItems);
        boWordCandidateItems=rootView.findViewById(R.id.boWordCandidateItems);
        boWordCandidateScroll=rootView.findViewById(R.id.boWordCandidateScroll);
        configureTextRows();
        if(layoutDiagnostics!=null)layoutDiagnostics.close();
        layoutDiagnostics=new LayoutDiagnostics(this,rootView,imeTelemetry);
        boStreamPreview = rootView.findViewById(R.id.boStreamPreview);
        if (boStreamPreview != null) boStreamPreview.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        renderedZhuyinCandidates = Collections.emptyList();
        rowEngineViews.clear();
        renderedZhuyinCandidateCount = 0;
        boCandidateRightHint=rootView.findViewById(R.id.boCandidateRightHint);
        if(boCandidateScroll!=null){
            boCandidateScroll.getViewTreeObserver().addOnScrollChangedListener(this::updateCandidateRightHint);
            boCandidateScroll.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateCandidateRightHint());
        }
        if (boCandidateScroll != null) boCandidateScroll.setOnScrollChangeListener((View v, int x, int y, int oldX, int oldY) -> {
            if (boCandidateItems != null && x + v.getWidth() >= boCandidateItems.getWidth() - dp(24)) {
                int before=renderedZhuyinCandidateCount;appendZhuyinCandidateBatch();
                if(renderedZhuyinCandidateCount!=before)renderSentenceOptions();
            }
        });

        switchKeyboard(currentKeyboardMode);
        if(textLayoutSelected())reclaimTextComposition(getCurrentInputEditorInfo());
        applyZhuyinState(zhuyinInput.state());
        updateCompleteTypingHint(false);
        updateModeUI();
        updateArmedIndicator();
        return rootView;
    }

    private void setupPunctuationKey(View key, String fullWidth, String halfWidth) {
        if (key == null) return;
        final boolean[] down = {false};
        final boolean[] longPress = {false};
        final Runnable[] longPressTask = new Runnable[1];
        longPressTask[0] = () -> {
            if (!down[0] || longPress[0]) return;
            longPress[0] = true;
            commitPunctuation(halfWidth);
            recordBopomofoKeyOutcome("，", 0L);
        };
        key.setClickable(true);
        key.setLongClickable(true);
        key.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    captureBopomofoKeyTouch(v, "，", event);
                    down[0] = true;
                    longPress[0] = false;
                    v.setPressed(true);
                    v.postDelayed(longPressTask[0], ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_UP:
                    v.removeCallbacks(longPressTask[0]);
                    v.setPressed(false);
                    if (down[0] && !longPress[0]) {
                        long started = SystemClock.elapsedRealtime();
                        commitPunctuation(fullWidth);
                        recordBopomofoKeyOutcome("，", SystemClock.elapsedRealtime() - started);
                    }
                    down[0] = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(longPressTask[0]);
                    v.setPressed(false);
                    down[0] = false;
                    return true;
                default:
                    return true;
            }
        });
    }

    private void setupSymbolLauncher(View key, String[] symbols, int columns, String fallbackText) {
        if (key == null) return;
        key.setOnClickListener(v -> showSymbolPopupOrFallback(v, symbols, columns, fallbackText));
        key.setOnLongClickListener(v -> {
            dismissSymbolPopup();
            commitPunctuation(fallbackText);
            return true;
        });
    }

    private void showSymbolPopupOrFallback(View anchor, String[] symbols, int columns, String fallbackText) {
        try {
            dismissSymbolPopup();
            if (activePanel != Panel.NONE) closePanel();
            View content = buildSymbolPopupContent(symbols, columns, fallbackText);
            content.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int popupWidth = content.getMeasuredWidth();
            int popupHeight = content.getMeasuredHeight();
            if (popupWidth <= 0 || popupHeight <= 0) throw new IllegalStateException("symbol popup measured empty");
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
            commitPunctuation(fallbackText);
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
        if (rootView == null || rootView.getWidth() <= 0) return -Math.max(0, popupWidth - anchor.getWidth()) / 2;
        int[] anchorLocation = new int[2];
        int[] rootLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        rootView.getLocationOnScreen(rootLocation);
        int margin = dp(4);
        int anchorLeft = anchorLocation[0] - rootLocation[0];
        int desiredLeft = anchorLeft + anchor.getWidth() / 2 - popupWidth / 2;
        int maxLeft = Math.max(margin, rootView.getWidth() - popupWidth - margin);
        return Math.max(margin, Math.min(desiredLeft, maxLeft)) - anchorLeft;
    }

    private void commitSymbolFromPopup(String symbol, String fallbackText) {
        try {
            InputConnection ic = getCurrentInputConnection();
            commitPunctuation(symbol);
        } catch (Exception e) {
            Log.w(TAG, "Symbol commit failed; committing fallback punctuation", e);
            commitPunctuation(fallbackText);
        }
    }

    private void dismissSymbolPopup() {
        if (symbolPopup != null) {
            if (symbolPopup.isShowing()) symbolPopup.dismiss();
            symbolPopup = null;
        }
    }

    private void commitPunctuation(String text) {
        if (currentKeyboardMode == KeyboardMode.BOPOMOFO && zhuyinInput != null) {
            boolean preview=!zhuyinInput.previewText().isEmpty();
            if(imeTelemetry!=null){
                imeTelemetry.punctuationDestination(preview?"preview":"field");
                if(pendingBopomofoKeyTouch==null){recordBopomofoKeyOutcome(text,0L);}
            }
            zhuyinCommitVia="other";
            applyZhuyinState(zhuyinInput.punctuation(text));
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) commitTextProgrammatically(ic, text);
        else commitTextSafely(text);
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
    private String lastVoiceCommitOutcome="not_attempted";
    protected void commitFinalText(String text) {
        lastVoiceCommitOutcome="not_attempted";
        boolean icCallInProgress=false;
        lastCommitInsertedOrCopied=false;
        lastCommitClipboardWritten=false;
        lastCommitMethod="clipboard";
        try {
            if(VoiceResultText.isEmptyFinal(text)) {showNoVoiceStatus();return;}
            settlePendingCorrectionCapture();
            TextSnapshot beforeSnapshot = getCurrentTextSnapshotSafely();

            // Step 1: Always copy to clipboard first as safety net.
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("simon-ime", text));
                    lastCommitClipboardWritten=true;
                    lastCommitInsertedOrCopied=true;
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
                lastVoiceCommitOutcome="no_ic";
                Log.w(TAG, "commitFinalText: ic == null, text on clipboard");
                updateStatus("已複製，可長按貼上");
                return;
            }

            // Step 4: Normal path.
            if (!problematic) {
                markProgrammaticTextChange();
                icCallInProgress=true;
                boolean ok = ic.commitText(text, 1);
                icCallInProgress=false;
                lastVoiceCommitOutcome=ok?"ic_api_true":"ic_api_false";
                if (ok) {
                    lastCommitInsertedOrCopied=true;
                    lastCommitMethod="commit";
                    recordVoiceCommit(text, beforeSnapshot);
                    return;
                }
                Log.w(TAG, "commitFinalText: commitText returned false for pkg=" + packageName + ", falling back to paste");
            }

            // Step 5: Paste fallback (text already on clipboard).
            markProgrammaticTextChange();
            if(lastCommitClipboardWritten){
                icCallInProgress=true;
                boolean pasted=ic.performContextMenuAction(android.R.id.paste);
                icCallInProgress=false;
                lastVoiceCommitOutcome=pasted?"ic_api_true":"ic_api_false";
                if(pasted)lastCommitInsertedOrCopied=true;
            }
            lastCommitMethod="paste";
            recordVoiceCommit(text, beforeSnapshot);
            updateStatus("已複製，可長按貼上");

        } catch (Exception e) {
            if(icCallInProgress)lastVoiceCommitOutcome="ic_api_exception";
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
        if (gen == activeUtteranceGeneration && fullPcmBuffer != null
                && !(fullPcmBuffer instanceof BoundedPcmBuffer && ((BoundedPcmBuffer)fullPcmBuffer).truncated())) {
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
        pruneConcurrentGenerationMap(acceptedTextLengths);
        pruneConcurrentGenerationMap(appendRescueCandidates);
        pruneConcurrentGenerationMap(recordingDurationMs);
    }

    private void clearServerWaitBudgetCallbacks() {
        if (mainHandler == null) return;
        for (Runnable callback : serverWaitBudgetCallbacks.values()) {
            mainHandler.removeCallbacks(callback);
        }
        serverWaitBudgetCallbacks.clear();
    }

    private static final class ReadyUtterance {
        final int generation; final String clientSessionId; final String text;
        ReadyUtterance(int generation,String clientSessionId,String text){this.generation=generation;this.clientSessionId=clientSessionId;this.text=text;}
    }

    private void commitReadyUtterance(ReadyUtterance ready) {
        if(isDiscardedVoiceGeneration(ready.generation))return;
        recordVoiceStage("release",ready.generation,ready.clientSessionId,null);
        deliverVoiceResult(ready.generation,ready.text,() -> {
            commitFinalText(ready.text);
            recordVoiceStage("commit",ready.generation,ready.clientSessionId,lastVoiceCommitOutcome);
            if("commit".equals(lastCommitMethod))updateStatus("完成: " + truncate(ready.text,20));
            else if(lastCommitClipboardWritten) {
                updateStatus("文字已保留剪貼簿，可長按貼上");
                showPendingVoiceNotice("文字已保留剪貼簿，可長按貼上");
            } else {
                updateStatus("文字未送出，已保留補傳");
                showPendingVoiceNotice("文字未送出，已保留補傳");
            }
        });
    }

    private boolean isDiscardedVoiceGeneration(int gen) {
        String id=pendingSessionByGeneration.get(gen);
        return discardedVoiceGenerations.contains(gen)||(id!=null&&discardedProtectedSessions.contains(id));
    }

    private void showNoVoiceStatus() {
        updatePreviewStrip("");
        updateStatus("沒有錄到聲音");
        final int token=silenceStatusGeneration;
        mainHandler.postDelayed(() -> {
            if(token==silenceStatusGeneration)updateStatus(isRecording?"🔴 錄音中...":"就緒");
        },2000L);
    }

    private long voiceAudioDurationMs(int gen) {
        Long duration=recordingDurationMs.get(gen);
        if(duration!=null)return duration;
        String id=pendingSessionByGeneration.get(gen);
        if(!isRecording&&!recordingFinalizing&&id!=null&&voicePendingQueue!=null
                &&!voicePendingQueue.backupFailed(id)&&voicePendingQueue.audioMs(id)>0)return voicePendingQueue.audioMs(id);
        return -1; // Unknown duration is not evidence of a short tap.
    }

    private void keepPendingModeResult(int gen,String mode) {keepPendingModeResult(gen,mode,"empty_"+mode+"_result");}
    private void keepPendingModeResult(int gen,String mode,String reason) {
        markPendingGeneration(gen,reason);
        mainHandler.post(() -> updateStatus("未取得結果，音訊等待封存收據"));
    }

    private boolean consumeSilentResult(int gen,String text) {return consumeSilentResult(gen,text,true);}
    private boolean consumeSilentResult(int gen,String text,boolean append) {return consumeSilentResult(gen,text,append,"non_append");}
    private boolean consumeSilentResult(int gen,String text,boolean append,String mode) {
        if(isDiscardedVoiceGeneration(gen)||deliveredVoiceGenerations.contains(gen))return true;
        if(!VoiceResultText.isEmptyFinal(text))return false;
        // Only delivery semantics: no text, duration or mode can authorize audio deletion.
        if(gen>0)deliveredVoiceGenerations.add(gen);
        markPendingGeneration(gen,"live_silence_waiting_receipt");
        if(append) {reserveUtteranceGeneration(gen);completeReservedUtteranceWithoutText(gen);}
        showNoVoiceStatus();return true;
    }

    /** Save the outbox and acknowledge delivery independently of audio custody. */
    private void deliverVoiceResult(int gen,String text,Runnable delivery) {
        deliverVoiceResult(gen,text,false,true,delivery);
    }
    private void deliverVoiceResult(int gen,String text,boolean deleteOnly,boolean append,Runnable delivery) {
        if(isDiscardedVoiceGeneration(gen)||deliveredVoiceGenerations.contains(gen))return;
        if(recoverLateVoiceFinal(gen,text))return;
        cancelVoiceFinalDeadline(gen);
        if(!deleteOnly&&consumeSilentResult(gen,text,append))return;
        final String id=pendingSessionByGeneration.get(gen);
        final boolean full=serverFullAudioGenerations.contains(gen)||serverFinalGenerations.contains(gen);
        java.util.function.Consumer<Boolean> deliver=backupFailed -> {
            if(isDiscardedVoiceGeneration(gen)||deliveredVoiceGenerations.contains(gen)||(id!=null&&discardedProtectedSessions.contains(id)))return;
            lastCommitInsertedOrCopied=false;lastCommitClipboardWritten=false;
            try {delivery.run();}catch(Exception e) {Log.e(TAG,"Voice delivery failed",e);}
            final boolean delivered=lastCommitInsertedOrCopied||lastCommitClipboardWritten;
            final boolean copied=lastCommitClipboardWritten;
            if(delivered&&gen>0)deliveredVoiceGenerations.add(gen);
            if(backupFailed) {
                // Delivery survives a missing durable file or a failed outbox write.
                pendingSessionByGeneration.remove(gen);
                updateStatus("錄音備份失敗，"+(delivered?"文字已送出":"文字未送出，請重試"));
                return;
            }
            if(id==null||voicePendingQueue==null)return;
            voicePendingQueue.execute(() -> {
                try {
                    if(delivered)voicePendingQueue.acknowledgeDelivery(id,copied);
                    markPendingGeneration(gen,delivered?"text_delivered_waiting_audio_receipt":"result_not_delivered");
                } catch(Exception e) {
                    Log.e(TAG,"Voice delivery receipt failed",e);
                    mainHandler.post(() -> updateStatus("錄音備份失敗，文字已送出，音訊保留"));
                }
            });
        };
        if(id==null||voicePendingQueue==null) {deliver.accept(false);return;}
        recordVoiceStage("io_begin",gen,id,null);
        voicePendingQueue.execute(() -> {
            boolean failed=false;
            try {voicePendingQueue.persistResult(id,text,full,append);}
            catch(Exception e) {failed=true;Log.e(TAG,"Voice result backup failed",e);}
            recordVoiceStage("io_end",gen,id,null);
            final boolean backupFailed=failed;
            recordVoiceStage("io_main_post",gen,id,null);
            mainHandler.post(() -> {recordVoiceStage("io_main_run",gen,id,null);deliver.accept(backupFailed);});
        });
    }

    private void persistRecordingRead(String id,byte[] buffer,int read) {
        for(int i=0;i+1<read;i+=2) {
            int sample=(short)((buffer[i]&255)|(buffer[i+1]<<8));
            if(Math.abs(sample)>=800) {
                for(java.util.Map.Entry<Integer,String> e:pendingSessionByGeneration.entrySet())
                    if(e.getValue().equals(id))audibleVoiceGenerations.add(e.getKey());
                break;
            }
        }
        if(voicePendingQueue!=null&&id!=null&&read>0&&!discardedProtectedSessions.contains(id))
            voicePendingQueue.appendAsync(id,buffer,read);
    }

    private void finishDurableRecording(String id) {
        if(voicePendingQueue==null||id==null||discardedProtectedSessions.contains(id))return;
        voicePendingQueue.holdForReceipt(id);
        java.util.concurrent.CountDownLatch waiter=audioReceiptWaiters.computeIfAbsent(id,key->new java.util.concurrent.CountDownLatch(1));
        voicePendingQueue.finishRecording(id,() -> {
            new Thread(() -> {
                try {
                    if(voicePendingQueue.receiptConfirmed(id))waiter.countDown();
                    if(waiter.await(8,TimeUnit.SECONDS))return;
                    // Query only after an absent/abnormal WS receipt. Never on the normal WS path.
                    for(long delay:new long[]{200L,500L,1000L}) {
                        Thread.sleep(delay);
                        if(waiter.getCount()==0||discardedProtectedSessions.contains(id))return;
                        Request.Builder rb=new Request.Builder().url(getServerUrl()+"/v1/audio-receipt?client_session_id="+id);
                        String auth=getAuthPassword();if(auth!=null&&!auth.isEmpty())rb.addHeader("Authorization","Bearer "+auth);
                        OkHttpClient client=httpClient.newBuilder().readTimeout(2,TimeUnit.SECONDS).callTimeout(3,TimeUnit.SECONDS).build();
                        try(Response response=client.newCall(rb.build()).execute()) {
                            if(response.isSuccessful()&&response.body()!=null&&voicePendingQueue.acceptReceipt(id,new JSONObject(response.body().string()))) {
                                waiter.countDown();return;
                            }
                        }catch(Exception e) {Log.w(TAG,"Audio receipt lookup failed; retaining pending PCM",e);}
                    }
                }catch(InterruptedException e) {Thread.currentThread().interrupt();}
                finally {
                    audioReceiptWaiters.remove(id,waiter);
                    voicePendingQueue.releaseReceiptWait(id);
                    drainPendingVoiceQueue();
                }
            },"VoiceAudioReceipt").start();
        });
    }

    private void receiveAudioReceipt(String id,JSONObject receipt) {
        if(id==null||voicePendingQueue==null||discardedProtectedSessions.contains(id))return;
        voicePendingQueue.execute(() -> {
            if(voicePendingQueue.acceptReceipt(id,receipt)) {
                java.util.concurrent.CountDownLatch waiter=audioReceiptWaiters.get(id);
                if(waiter!=null)waiter.countDown();
            }
        });
    }

    // A stopped recording must not hold the ordered commit queue indefinitely.
    private static final long VOICE_FINAL_DEADLINE_MS = 35_000L;
    private final java.util.concurrent.ConcurrentHashMap<Integer,Runnable> voiceFinalDeadlines = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer,String> recoverySessionByGeneration = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> audibleVoiceGenerations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicInteger recoveredVoiceCount = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int audioStreamGeneration;

    private void cancelVoiceFinalDeadline(int gen) {
        Runnable callback=voiceFinalDeadlines.remove(gen);
        if(callback!=null)mainHandler.removeCallbacks(callback);
    }

    private void armVoiceFinalDeadline(int gen) {
        if(gen<=0||isWatchService())return;
        cancelVoiceFinalDeadline(gen);
        final String deadlineSessionId=pendingSessionByGeneration.get(gen);
        Runnable callback=() -> {recordVoiceStage("deadline",gen,deadlineSessionId,null);recoverUnfinishedVoiceGeneration(gen,"final_deadline");};
        voiceFinalDeadlines.put(gen,callback);
        mainHandler.postDelayed(callback,VOICE_FINAL_DEADLINE_MS);
    }

    private void recoverUnfinishedVoiceGeneration(int gen,String reason) {
        if(isDiscardedVoiceGeneration(gen)||deliveredVoiceGenerations.contains(gen)||acceptedTextLengths.containsKey(gen))return;
        if((isRecording||recordingFinalizing)&&gen==activeUtteranceGeneration){
            afterRecordingFinalization.add(() -> recoverUnfinishedVoiceGeneration(gen,reason));return;
        }
        cancelVoiceFinalDeadline(gen);
        String id=pendingSessionByGeneration.get(gen);
        if(id==null)return;
        if(id.equals(activePendingSessionId))activePendingSessionId=null;
        recoverySessionByGeneration.put(gen,id);
        reserveUtteranceGeneration(gen);
        // Close the ordering slot before recovery; the durable queue owns this ID.
        completeReservedUtteranceWithoutText(gen);
        // Recovery owns a finalized recording even when the recorder ID has not been cleared yet.
        markPendingGeneration(gen,reason);
        updateStatus("語音暫未送出，已保留補傳；後續語音可繼續");
        showPendingVoiceNotice("語音已保留，等待補傳");
        recordVoiceEvent("pending_saved",id,voicePendingQueue==null?0:voicePendingQueue.audioMs(id),0,0,reason,"",0,0);
    }

    private boolean recoverLateVoiceFinal(int gen,String text) {
        String id=recoverySessionByGeneration.get(gen);
        if(id==null)return false;
        if(voicePendingQueue!=null&&!VoiceResultText.isEmptyFinal(text)&&!isDiscardedVoiceGeneration(gen)) {
            voicePendingQueue.execute(() -> {
                if(!voicePendingQueue.delivered(id))voicePendingQueue.persistResult(id,text,true,true);
                drainPendingVoiceQueue();
            });
        }
        return true;
    }

    private OpusStreamEncoder.Sink opusSink(WebSocket ws) {
        return new OpusStreamEncoder.Sink() {
            @Override public boolean configure(int delay) {
                try{return ws.send(AppVersion.withAppVersion(new JSONObject().put("type","opus_config").put("encoder_delay_samples",delay)).toString());}
                catch(Exception failure){return false;}
            }
            @Override public boolean send(byte[] packet){return ws.send(ByteString.of(packet,0,packet.length));}
        };
    }

    private void sendStreamAudio(WebSocket ws,byte[] pcm,String id) {
        try {
            OpusStreamEncoder encoder=id==null?null:opusEncoders.get(id);
            if(encoder!=null)encoder.write(pcm,pcm.length,opusSink(ws));
            else if(!ws.send(ByteString.of(pcm,0,pcm.length)))throw new IOException("PCM transport rejected");
            if(voicePendingQueue!=null)voicePendingQueue.noteStreamedBytes(id,pcm.length);
        }catch(Exception failure) {
            if(id!=null&&opusEncoders.containsKey(id))opusDisabledForProcess=true;
            streamFailed=true;audioStreamActive=false;ws.cancel();
            Log.w(TAG,"Audio encoding/transport failed; durable PCM retained",failure);
        }
    }

    private void sendAudioEndOfStream(WebSocket ws,String id) {
        for(java.util.Map.Entry<Integer,String> e:pendingSessionByGeneration.entrySet())
            if(e.getValue().equals(id))mainHandler.post(() -> armVoiceFinalDeadline(e.getKey()));
        final OpusStreamEncoder encoder=id==null?null:opusEncoders.get(id);
        if(voicePendingQueue==null||id==null) {ws.send(AppVersion.controlMessage("finalize"));return;}
        voicePendingQueue.execute(() -> {
            try {
                voicePendingQueue.sealStreamedBytes(id);
                JSONObject eos=AppVersion.withAppVersion(voicePendingQueue.recordingIdentity(id));
                if(encoder!=null) {
                    eos.put("audio_format","opus").put("wire_byte_count",encoder.wireBytes()).put("wire_sha256",encoder.wireSha())
                            .put("encoder_delay_samples",encoder.delaySamples());
                }
                eos.put("type","finalize");ws.send(eos.toString());
                if(encoder!=null)opusEncoders.remove(id,encoder);
            }catch(Exception e) {Log.e(TAG,"Audio EOS identity unavailable; retaining local PCM",e);ws.send(AppVersion.controlMessage("finalize"));}
        });
    }

    private void discardProtectedRecording() {
        // Simon intentionally discards the whole session, including speech before switching
        // to a protected field; no earlier portion may leak to clipboard or retry upload.
        String id=activePendingSessionId;
        if(id!=null) {
            discardedProtectedSessions.add(id);
            for(java.util.Map.Entry<Integer,String> e:pendingSessionByGeneration.entrySet())
                if(id.equals(e.getValue()))discardedVoiceGenerations.add(e.getKey());
            if(voicePendingQueue!=null){voicePendingQueue.discardAsync(id);drainPendingVoiceQueue();}
            recordVoiceEvent("protected_discard",id,0,0,0,"","",0,0);
            completeReservedUtteranceWithoutText(activeUtteranceGeneration);
        }
    }

    private void completeReservedUtteranceWithText(int gen, String text) {
        String sessionId=pendingSessionByGeneration.get(gen);
        if(sessionId!=null&&discardedProtectedSessions.contains(sessionId)){
            Log.i(TAG,"Discarding transcription for protected-field recording session");
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> completeReservedUtteranceWithText(gen, text));
            return;
        }

        cancelVoiceFinalDeadline(gen);
        if(recoverLateVoiceFinal(gen,text))return;
        if(consumeSilentResult(gen,text))return;
        List<ReadyUtterance> readyTexts = new ArrayList<>();
        synchronized (utteranceCommitLock) {
            if (gen < nextGenToCommit) return;
            completedGenerations.add(gen);
            pendingCommits.put(gen, text);
            recordVoiceStage("admit",gen,pendingSessionByGeneration.get(gen),null);
            if (!isWatchService()) acceptedTextLengths.put(gen, text.length());
            collectReadyUtteranceCommitsLocked(readyTexts);
        }
        for (ReadyUtterance ready : readyTexts) {
            commitReadyUtterance(ready);
            if(voicePendingQueue!=null)drainPendingVoiceQueue();
        }
    }

    private void completeReservedUtteranceWithoutText(int gen) {
        if (gen <= 0) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> completeReservedUtteranceWithoutText(gen));
            return;
        }

        List<ReadyUtterance> readyTexts = new ArrayList<>();
        synchronized (utteranceCommitLock) {
            if (gen < nextGenToCommit) return;
            completedGenerations.add(gen);
            collectReadyUtteranceCommitsLocked(readyTexts);
        }
        for (ReadyUtterance ready : readyTexts) commitReadyUtterance(ready);
        String activeId=pendingSessionByGeneration.get(gen);
        if(activeId!=null&&activeId.equals(activePendingSessionId))return;
        String queuedId=pendingSessionByGeneration.remove(gen);
        if(queuedId!=null&&voicePendingQueue!=null){voicePendingQueue.execute(() -> voicePendingQueue.markPending(queuedId,"transcription_not_committed"));recordVoiceEvent("pending_saved",queuedId,voicePendingQueue.audioMs(queuedId),0,0,"","",voicePendingQueue.totalBytes(),0);drainPendingVoiceQueue();}
    }

    private void markPendingGeneration(int gen,String reason){
        String id=pendingSessionByGeneration.remove(gen);
        if(id!=null&&voicePendingQueue!=null)voicePendingQueue.markPendingAsync(id,reason,this::drainPendingVoiceQueue);
    }

    private void notePendingGeneration(int gen,String reason) {
        String id=pendingSessionByGeneration.get(gen);
        if(id!=null&&voicePendingQueue!=null)voicePendingQueue.markPendingAsync(id,reason,null);
    }

    private void collectReadyUtteranceCommitsLocked(List<ReadyUtterance> readyTexts) {
        while (completedGenerations.contains(nextGenToCommit)) {
            String text = pendingCommits.remove(nextGenToCommit);
            if (text != null && !text.isEmpty()) {
                readyTexts.add(new ReadyUtterance(nextGenToCommit,pendingSessionByGeneration.get(nextGenToCommit),text));
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
        if (isWatchService()) return; // Watch bridge owns the complete response; no phone correction capture.
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
            AppVersion.withAppVersion(payload);

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
    protected boolean pasteClipboardText(String text) {
        if(text==null||text.isEmpty())return false;
        InputConnection ic=getCurrentInputConnection();
        if(ic==null) {
            boolean copied=copyToSystemClipboard(text);
            updateStatus(copied?"已複製，長按輸入框貼上（無輸入連線）":"複製失敗，請重試");
            return copied;
        }
        markProgrammaticTextChange();
        boolean pasted=ic.commitText(text,1);
        boolean copied=false;
        if(!pasted) {
            copied=copyToSystemClipboard(text);
            if(copied) {
                markProgrammaticTextChange();
                pasted=ic.performContextMenuAction(android.R.id.paste);
            }
        }
        updateStatus(pasted?"📋 已貼上":copied?"已複製，可長按貼上":"貼上失敗，請重試");
        return pasted||copied;
    }

    private boolean copyToSystemClipboard(String text) {
        try {
            ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(cm==null)return false;
            cm.setPrimaryClip(ClipData.newPlainText("simon-ime",text));
            return true;
        } catch(Exception e) {Log.w(TAG,"Clipboard copy failed",e);return false;}
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

    private void postMicrophoneWarning(int gen, long observedMs, String message) {
        mainHandler.post(() -> {
            if (isRecording && gen == activeUtteranceGeneration
                    && lastRecognitionStatusMs < observedMs) updateStatus(message);
        });
    }

    private boolean restartMicrophone(int bufferSize, int gen, SilenceWatchdog.Verdict reason,
                                   int readResult, long observedMs) {
        synchronized (recorderRestartLock) {
            if (!isRecording || gen != activeUtteranceGeneration) return false;
            Log.w(TAG, "[SilenceWatchdog] restart reason=" + reason + " readResult=" + readResult);
            AudioRecord replacement = null;
            try {
                try {
                    audioRecord.stop();
                } catch (Exception e) {
                    Log.w(TAG, "[SilenceWatchdog] stop failed", e);
                }
                audioRecord.release();
                if (reason == SilenceWatchdog.Verdict.SILENT_RESTART) {
                    replacement = new AudioRecord(MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
                } else {
                    try {
                        replacement = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
                        if (replacement.getState() != AudioRecord.STATE_INITIALIZED) {
                            replacement.release();
                            replacement = null;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "[SilenceWatchdog] VOICE_RECOGNITION unavailable", e);
                    }
                    if (replacement == null) {
                        replacement = new AudioRecord(MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
                    }
                }
                if (replacement.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("AudioRecord initialization failed");
                }
                if (!isRecording || gen != activeUtteranceGeneration) {
                    replacement.release();
                    return false;
                }
                replacement.startRecording();
                audioRecord = replacement;
                postMicrophoneWarning(gen, observedMs, "⚠️ 麥克風已重新啟動");
                return true;
            } catch (Exception e) {
                if (replacement != null) {
                    try {
                        replacement.release();
                    } catch (Exception releaseError) {
                        Log.w(TAG, "[SilenceWatchdog] replacement release failed", releaseError);
                    }
                }
                Log.w(TAG, "[SilenceWatchdog] rebuild failed readResult=" + readResult, e);
                postMicrophoneWarning(gen, observedMs, "⚠️ 麥克風重新啟動失敗，錄音已保留");
                return false;
            }
        }
    }

    private String appendRescueCandidate(int gen) {
        String candidate = appendRescueCandidates.getOrDefault(gen, "");
        if (gen != activeUtteranceGeneration) return candidate;
        StringBuilder chunks = new StringBuilder();
        synchronized (streamedChunks) {
            for (String chunk : streamedChunks) chunks.append(chunk);
        }
        String local;
        synchronized (onDeviceAppendPreviewLock) {
            local = TextLossGuard.joinDedup(onDeviceAppendPreviewSegments, 6);
        }
        if (chunks.length() > candidate.length()) candidate = chunks.toString();
        if (local.length() > candidate.length()) candidate = local;
        return candidate;
    }

    // Runs on the main thread, like both existing race completion callbacks.
    private void rescueDiscardedLocal(int gen, String candidate) {
        int committedLen = acceptedTextLengths.getOrDefault(gen, 0);
        if (!isWatchService() && TextLossGuard.shouldRescue(
                committedLen, candidate.length(), rescueExtraChars, rescueRatio)) {
            clipboardHelper.addToHistory(candidate);
            updateStatus("辨識到更完整版本，已存剪貼簿");
        } else {
            Log.i(TAG, "[TextLossGuard] discard on-device candidate=" + candidate.length()
                    + " committed=" + committedLen + " gen=" + gen);
        }
    }

    private void rescueReplaceAudio(int gen,boolean shortResult) {
        if(isDiscardedVoiceGeneration(gen))return;
        final byte[] pcm=fullPcmByGeneration.get(gen);
        final LocalSTT recognizer=localSTT;
        if(pcm==null||pcm.length==0||!localSTTReady||recognizer==null) {
            markPendingGeneration(gen,"replace_rescue_unavailable");
            mainHandler.post(() -> updateStatus("換字失敗，音訊保留待補傳"));return;
        }
        new Thread(() -> {
            try {
                String recognized=recognizer.recognize(pcm,SAMPLE_RATE);
                mainHandler.post(() -> {
                    // This is an unconfirmed offline rescue, not a server punctuation/SPELL command.
                    if(VoiceResultText.clean(recognized).codePoints().noneMatch(Character::isLetterOrDigit)
                            ||VoiceResultText.isHallucinationMarker(recognized)) {
                        markPendingGeneration(gen,"replace_local_unconfirmed");
                        updateStatus("換字失敗，音訊保留待補傳");return;
                    }
                    String rescued=VoiceResultText.clean(recognized);
                    deliverVoiceResult(gen,rescued,() -> {
                        ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if(cm==null)throw new IllegalStateException("clipboard unavailable");
                        cm.setPrimaryClip(ClipData.newPlainText("simon-ime",rescued));
                        lastCommitClipboardWritten=true;lastCommitInsertedOrCopied=true;
                        if(clipboardHelper!=null)clipboardHelper.addToHistory(rescued);
                        updateStatus(shortResult?"換字結果偏短，原話已存剪貼簿":"換字逾時，原話已存剪貼簿（端上辨識）");
                    });
                });
            } catch(Exception e) {
                markPendingGeneration(gen,"replace_rescue_failed:"+e.getClass().getSimpleName());
                mainHandler.post(() -> updateStatus("換字失敗，音訊保留待補傳"));
            }
        },"ReplaceRescue-STT").start();
    }

    protected void startRecording() {
        if(recordingFinalizing) {updateStatus("收尾中，請稍候");return;}
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

        if(lineGhostwriterField()) {
            EditorInfo editor=getCurrentInputEditorInfo();
            int variation=editor.inputType & android.text.InputType.TYPE_MASK_VARIATION;
            if((editor.imeOptions & EditorInfo.IME_MASK_ACTION)==EditorInfo.IME_ACTION_SEARCH
                    ||variation==android.text.InputType.TYPE_TEXT_VARIATION_FILTER) {
                updateStatus("嘴替只在 LINE 聊天輸入框使用，請先開啟對話");return;
            }
        }
        // Close any open panel
        if (!isWatchService()) closePanel();

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

        pcmBuffer = isWatchService() ? new ByteArrayOutputStream() : new BoundedPcmBuffer(MAX_FULL_PCM_BYTES);
        fullPcmBuffer = isWatchService() ? new ByteArrayOutputStream() : new BoundedPcmBuffer(MAX_FULL_PCM_BYTES); // Only short offline preview; full audio is on disk.
        streamFailed = false;
        final int myGen = utteranceGeneration.incrementAndGet();
        activeUtteranceGeneration = myGen;
        invalidateLineGhostwriter();
        if(lineGhostwriterField()) {
            lineGenerations.add(myGen);
            String draft=lineDraft();if(draft!=null)lineDrafts.put(myGen,draft);
            lineFields.put(myGen,fieldGeneration);
            java.util.concurrent.FutureTask<LineContextAccessibilityService.Snapshot> capture=new java.util.concurrent.FutureTask<>(LineContextAccessibilityService::capture);
            lineCaptures.put(myGen,capture);
            new Thread(capture,"LINE-Visible-Context").start();
        }
        activePendingSessionId=(voicePendingQueue==null||protectedInputField)?null:voicePendingQueue.beginAsync(() -> mainHandler.post(() -> updateStatus("錄音備份失敗，仍可即時辨識")));
        if(activePendingSessionId!=null)pendingSessionByGeneration.put(myGen,activePendingSessionId);
        if(lineGenerations.contains(myGen)&&activePendingSessionId!=null&&voicePendingQueue!=null) {
            final String directiveId=activePendingSessionId;
            voicePendingQueue.execute(()->voicePendingQueue.retainDirective(directiveId));
        }
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
                recordingWakeLock.acquire(isWatchService() ? 10 * 60 * 1000L : 31 * 60 * 1000L); // Stay awake through phone cap (<=30 min); guard releases on stop.
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock 取得失敗，繼續錄音", e);
        }

        final long recordingStartedMs = android.os.SystemClock.elapsedRealtime();
        activeRecordingStartedMs = recordingStartedMs;
        lastVoiceChunkElapsed=recordingStartedMs;lastVoiceStallEventElapsed=0;
        recordVoiceEvent("start",activePendingSessionId,0,0,0,"","",0,0);
        if (!isWatchService()) {
            SharedPreferences sessionPrefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
            voiceSessionGuard = new VoiceSessionGuard(recordingStartedMs,
                    sessionPrefs.getInt("voice_session_cap_minutes", VoiceSessionGuard.DEFAULT_CAP_MINUTES));
            scheduleVoiceSessionGuard(myGen);
        }
        audioRecord.startRecording();

        // v3.6: 停用舊串流模式（VAD 分段）
        streamingMode = false;
        if (!isWatchService()) prepareOnDeviceAppendPreview();

        // v4.2: 音訊串流 WebSocket（已修復文字消失 + 亂序 bug）
        if (currentMode == Mode.APPEND) {
            startAudioStreamWs(myGen);
        }

        updateStatus("🔴 錄音中...");
        btnMic.setBackgroundColor(getResources().getColor(R.color.mic_active, null));
        if (btnMic instanceof Button) ((Button) btnMic).setText("⏹");

        final String recordingSessionId=activePendingSessionId;
        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            int silentBytes = 0;           // 連續靜音 byte 計數
            final int SILENCE_THRESHOLD = 800;  // 16-bit PCM RMS 門檻（靜音偵測）
            final int BYTES_PER_MS = 32; // 16000*2/1000
            final int SILENCE_MS_TO_SPLIT = 500;
            final int SILENCE_BYTES_TO_SPLIT = SILENCE_MS_TO_SPLIT * BYTES_PER_MS; // 16000 bytes = 500ms
            final int MIN_CHUNK_BYTES = 32000;  // 最小 1 秒才送（避免 Whisper 幻覺）
            final int MAX_CHUNK_BYTES = 48000;  // v6.14: 最大 1.5 秒強制送，讓 GB10 在說話中先解碼
            boolean rebuildFailed = false;
            SharedPreferences prefs = getSharedPreferences("simon_ime_prefs", MODE_PRIVATE);
            SilenceWatchdog watchdog = new SilenceWatchdog(recordingStartedMs,
                    prefs.getFloat("silent_rms", (float) SilenceWatchdog.SILENT_RMS),
                    prefs.getLong("silent_warn_ms", SilenceWatchdog.SILENT_WARN_MS),
                    prefs.getLong("silent_restart_ms", SilenceWatchdog.SILENT_RESTART_MS),
                    prefs.getInt("read_error_limit", SilenceWatchdog.READ_ERROR_LIMIT));

            try {
            while (isRecording) {
                int read;
                try {
                    read = audioRecord.read(buffer, 0, buffer.length);
                } catch (IllegalStateException e) {
                    read = AudioRecord.ERROR_INVALID_OPERATION;
                }
                // stopRecordingAndSend may race with read(): persist and process a final positive
                // read before the loop exits so it cannot fall between the server final and queue deletion.
                if (!isRecording && read <= 0) break;
                if(read>0 && voicePendingQueue!=null && recordingSessionId!=null && !discardedProtectedSessions.contains(recordingSessionId)){
                    persistRecordingRead(recordingSessionId,buffer,read);
                    if(voicePendingQueue.totalBytes()>500L*1024*1024)showPendingVoiceQuotaWarning();
                }
                long voiceNow=SystemClock.elapsedRealtime();
                if(voicePendingQueue!=null && recordingSessionId!=null && voiceNow-lastVoiceChunkElapsed>3000
                        && voiceNow-lastVoiceStallEventElapsed>=3000){
                    lastVoiceStallEventElapsed=voiceNow;
                    recordVoiceEvent("stall",recordingSessionId,voicePendingQueue.audioMs(recordingSessionId),0,0,"","",voicePendingQueue.totalBytes(),0);
                }
                if (!isWatchService()) {
                    long sum = 0;
                    for (int i = 0; i + 1 < read; i += 2) {
                        short sample = (short) ((buffer[i] & 0xff) | (buffer[i + 1] << 8));
                        sum += (long) sample * sample;
                    }
                    double normalizedRms = read >= 2
                            ? Math.sqrt(sum / (double) (read / 2)) / 32768.0 : Double.NaN;
                    long observedMs = android.os.SystemClock.elapsedRealtime();
                    VoiceSessionGuard guard = voiceSessionGuard;
                    if (guard != null && normalizedRms >= 800.0 / 32768.0) guard.speech(observedMs);
                    SilenceWatchdog.Verdict verdict = watchdog.feed(read, normalizedRms, observedMs);
                    if (verdict == SilenceWatchdog.Verdict.SILENT_WARN) {
                        postMicrophoneWarning(myGen, observedMs,
                                "⚠️ 沒收到聲音（藍牙耳機或別的 App 占用麥克風？）");
                    } else if (!rebuildFailed && (verdict == SilenceWatchdog.Verdict.READ_ERROR
                            || verdict == SilenceWatchdog.Verdict.SILENT_RESTART)) {
                        rebuildFailed = !restartMicrophone(bufferSize, myGen, verdict, read, observedMs);
                    }
                    if (read <= 0) android.os.SystemClock.sleep(20); // avoid a hot error loop
                }
                if (read > 0) {
                    // The stream and durable backup cover the same recorder reads.
                    pcmBuffer.write(buffer, 0, read);
                    // v6.1: APPEND 串流模式下，pcmBuffer 會每送一個 chunk 就 reset()，
                    //       fullPcmBuffer 不 reset → 保留整段音訊供失敗時乾淨重轉錄。
                    //       預覽記憶體封頂 256 KiB；完整 PCM 持續落盤。片段端上辨識不能授權刪除完整檔案。
                    if (currentMode == Mode.APPEND && fullPcmBuffer != null
                            ) {
                        fullPcmBuffer.write(buffer, 0, read);
                    }

                    if (currentMode == Mode.APPEND && onDeviceAppendPreviewEnabled) {
                        feedOnDeviceAppendPreview(buffer, read, myGen);
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
                        boolean forceFlush = bufSize >= (recordingSessionId!=null&&opusEncoders.containsKey(recordingSessionId)?640:MAX_CHUNK_BYTES);

                        if (pauseDetected || forceFlush) {
                            byte[] chunkData = pcmBuffer.toByteArray();
                            pcmBuffer.reset();
                            silentBytes = 0;
                            sendStreamAudio(audioStreamWs,chunkData,recordingSessionId);
                            lastVoiceChunkElapsed=SystemClock.elapsedRealtime();
                            streamChunkTotal++;
                            Log.i(TAG, "[AudioStream] chunk #" + streamChunkTotal
                                    + " (" + chunkData.length + "B, "
                                    + (pauseDetected ? "pause" : "maxlen") + ")");
                        }
                    }
                }
            }
            } catch(Exception e) {Log.e(TAG,"Recorder stopped after capture error",e);}
            finally {
                finishDurableRecording(recordingSessionId);
                OpusStreamEncoder encoder=recordingSessionId==null?null:opusEncoders.get(recordingSessionId);
                if(encoder!=null)try {
                    if(!streamFailed&&audioStreamActive&&audioStreamGeneration==myGen&&audioStreamWs!=null) {
                        byte[] tail=pcmBuffer.toByteArray();pcmBuffer.reset();
                        if(tail.length>0){sendStreamAudio(audioStreamWs,tail,recordingSessionId);streamChunkTotal++;}
                        if(!streamFailed)encoder.finish(opusSink(audioStreamWs));
                    }
                }catch(Exception failure) {
                    opusDisabledForProcess=true;streamFailed=true;audioStreamActive=false;if(audioStreamWs!=null)audioStreamWs.cancel();
                    Log.w(TAG,"Opus EOS failed; original PCM retained",failure);
                }finally {
                    try{encoder.close();}catch(Exception failure){Log.w(TAG,"Opus release failed",failure);}
                    if(streamFailed)opusEncoders.remove(recordingSessionId,encoder);
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
        startAudioStreamWs(myGen,false);
    }

    private void startAudioStreamWs(final int myGen,final boolean requestedOpus) {
        final String custodySessionId=pendingSessionByGeneration.get(myGen);
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

        Request wsReq = new Request.Builder().url(AppVersion.withAppVersion(wsUrl)).build();
        audioStreamGeneration=myGen;
        audioStreamActive=false;
        audioStreamWs = httpClient.newWebSocket(wsReq, new WebSocketListener() {
            private boolean capabilityProbeClosed;
            @Override
            public void onOpen(WebSocket ws, Response response) {
                // Send auth + context
                String auth = getAuthPassword();
                try {
                    JSONObject authMsg = AppVersion.withAppVersion(new JSONObject());
                    authMsg.put("type", "auth");
                    authMsg.put("audio_format",requestedOpus?"opus":"pcm_s16le");
                    if(requestedOpus)authMsg.put("opus_config_required",true);
                    authMsg.put("password", auth != null ? auth : "");
                    authMsg.put("client_session_id",custodySessionId==null?"":custodySessionId);
                    if (!contextBefore.isEmpty()) authMsg.put("context_before", contextBefore);
                    if (!contextAfter.isEmpty()) authMsg.put("context_after", contextAfter);
                    if(!ws.send(authMsg.toString()))throw new IOException("auth send rejected");
                } catch (Exception e) {
                    try {
                        JSONObject fallback=AppVersion.withAppVersion(new JSONObject());
                        fallback.put("type","auth").put("audio_format",requestedOpus?"opus":"pcm_s16le").put("password",auth!=null?auth:"")
                            .put("client_session_id",custodySessionId==null?"":custodySessionId);
                        if(requestedOpus)fallback.put("opus_config_required",true);
                        if(!ws.send(fallback.toString()))throw new IOException("fallback auth send rejected");
                    } catch(Exception fallbackError) {
                        Log.e(TAG,"Audio stream auth failed; retaining recording",fallbackError);
                        mainHandler.post(() -> recoverUnfinishedVoiceGeneration(myGen,"auth_send_failed"));
                    }
                }
                Log.i(TAG, "[AudioStream] WebSocket 已連線，已送出認證" +
                        (contextBefore.isEmpty() ? "" : " (含上下文)"));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    String type = json.optString("type");
                    if (guardStoppedGenerations.contains(myGen) && !"receipt".equals(type)) return;

                    if ("auth_ok".equals(type)) {
                        Log.i(TAG, "[AudioStream] 認證成功");
                        if(myGen==audioStreamGeneration&&myGen==activeUtteranceGeneration&&isRecording
                                &&!recoverySessionByGeneration.containsKey(myGen)) {
                            if(!requestedOpus&&!opusDisabledForProcess&&opusDisabledGeneration!=myGen&&"OP20".equals(json.optString("opus_framing"))) {
                                org.json.JSONArray formats=json.optJSONArray("audio_formats");boolean supported=false;
                                if(formats!=null)for(int i=0;i<formats.length();i++)if("opus".equals(formats.optString(i)))supported=true;
                                if(supported && custodySessionId!=null)try {
                                    opusEncoders.put(custodySessionId,new OpusStreamEncoder());capabilityProbeClosed=true;
                                    ws.close(1000,"opus capability probe complete");startAudioStreamWs(myGen,true);return;
                                }catch(Exception unavailable){opusDisabledForProcess=true;opusDisabledGeneration=myGen;Log.w(TAG,"Opus unavailable; using PCM",unavailable);}
                            }
                            if(requestedOpus&&!"opus".equals(json.optString("audio_format"))) {
                                opusDisabledForProcess=true;opusDisabledGeneration=myGen;
                                OpusStreamEncoder old=opusEncoders.remove(custodySessionId);if(old!=null)old.close();
                                capabilityProbeClosed=true;ws.close(1000,"opus not negotiated");startAudioStreamWs(myGen,false);return;
                            }
                            audioStreamActive=true;
                        }
                        else ws.close(1000,"recording preserved for recovery");

                    } else if ("auth_fail".equals(type)) {
                        Log.e(TAG, "[AudioStream] 認證失敗");
                        if (myGen == utteranceGeneration.get()) {
                            streamFailed = true;
                            audioStreamActive = false;
                            audioStreamWs = null;
                        }
                        mainHandler.post(() -> {
                            if (myGen != utteranceGeneration.get()) {
                                recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                            } else if (isRecording) {
                                updateStatus("🔴 錄音中…（連線中斷，本地暫存）");
                            } else {
                                updateStatus("整理中…");
                                recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                            }
                        });

                    } else if ("chunk".equals(type)) {
                        if (myGen != utteranceGeneration.get()) return;
                        String chunkText = json.optString("text", "");
                        int idx = json.optInt("index", -1);
                        VoiceSessionGuard guard = voiceSessionGuard;
                        if (isRecording && myGen == activeUtteranceGeneration && guard != null)
                            guard.serverProgress(SystemClock.elapsedRealtime(), idx, chunkText);
                        if (!VoiceResultText.isSilence(chunkText)) {
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

                    } else if ("receipt".equals(type)) {
                        receiveAudioReceipt(custodySessionId,json);
                    } else if ("final".equals(type)) {
                        final String finalText;
                        try {finalText=VoicePendingQueue.parseSuccessfulResponse(json.toString());}
                        catch(java.io.IOException malformed) {
                            mainHandler.post(() -> recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished"));
                            return;
                        }
                        if (guardStoppedGenerations.contains(myGen)) return;
                        String sessionId=pendingSessionByGeneration.get(myGen);
                        recordVoiceEvent("final",sessionId,sessionId==null?0:voicePendingQueue.audioMs(sessionId),finalText.length(),0,"","",0,0);
                        final String traceSessionId=sessionId!=null?sessionId:recoverySessionByGeneration.get(myGen);
                        recordVoiceStage("ws_main_post",myGen,traceSessionId,null);
                        mainHandler.post(() -> {
                            recordVoiceStage("ws_main_run",myGen,traceSessionId,null);
                            if(!VoiceResultText.isEmptyFinal(finalText))cancelVoiceFinalDeadline(myGen);
                            if(recoverLateVoiceFinal(myGen,finalText)){cancelVoiceFinalDeadline(myGen);return;}
                            if(VoiceResultText.isEmptyFinal(finalText)&&audibleVoiceGenerations.contains(myGen)) {
                                cancelVoiceFinalDeadline(myGen);
                                recoverUnfinishedVoiceGeneration(myGen,"audible_empty_final");return;
                            }
                            if(VoiceResultText.isEmptyFinal(finalText)) {
                                // A rolling zero tail is not proof of complete digital silence.
                                final byte[] completePcm=fullPcmByGeneration.get(myGen);
                                final String silenceId=pendingSessionByGeneration.get(myGen);
                                if(silenceId==null||voicePendingQueue==null) {
                                    cancelVoiceFinalDeadline(myGen);
                                    recoverUnfinishedVoiceGeneration(myGen,"unconfirmed_empty_final");return;
                                }
                                voicePendingQueue.execute(() -> {
                                    boolean settled=false;
                                    try {settled=voicePendingQueue.acknowledgeDigitalSilence(silenceId,completePcm);}
                                    catch(Exception e) {Log.e(TAG,"Empty voice result settlement failed",e);}
                                    final boolean verified=settled;
                                    mainHandler.post(() -> {
                                        cancelVoiceFinalDeadline(myGen);
                                        if(verified)consumeSilentResult(myGen,finalText);
                                        else recoverUnfinishedVoiceGeneration(myGen,"unconfirmed_empty_final");
                                    });
                                });
                                return;
                            }
                            if(consumeSilentResult(myGen,finalText))return;
                            serverFinalGenerations.add(myGen);
                            if(requestedOpus && custodySessionId!=null && voicePendingQueue!=null)
                                voicePendingQueue.execute(() -> voicePendingQueue.markOpusFinalCorrected(custodySessionId));
                            // Main-thread snapshot cannot mix a new recording's preview into this generation.
                            final String candidate = isWatchService() ? "" : appendRescueCandidate(myGen);
                            final boolean rescue = !isWatchService() && TextLossGuard.shouldRescue(
                                    finalText.length(), candidate.length(), rescueExtraChars, rescueRatio);
                            if (myGen == utteranceGeneration.get()) {
                                streamedChunks.clear();
                                updatePreviewStrip("");
                            }
                            if (!finalText.isEmpty()) {
                                // v6.1: 一次性提交最終（雲端已拼接＋校正）結果。全程未動 composing text，故直接 commitText。
                                //       generation 守衛：晚到的 onFailure fallback 會被擋，不會重複提交。
                                // v6.17: 走 commitFinalText 以支援不接受 commitText 的 app（Termux/Gemini）。
                                if (reserveUtteranceGeneration(myGen)) {
                                    completeReservedUtteranceWithText(myGen, finalText);
                                } else {
                                    int committedLen = acceptedTextLengths.getOrDefault(myGen, 0);
                                    String discarded = candidate.length() > finalText.length() ? candidate : finalText;
                                    if (!isWatchService() && TextLossGuard.shouldRescue(
                                            committedLen, discarded.length(), rescueExtraChars, rescueRatio)) {
                                        clipboardHelper.addToHistory(discarded);
                                        updateStatus("辨識到更完整版本，已存剪貼簿");
                                    } else {
                                        Log.i(TAG, "[TextLossGuard] discard WS candidate=" + discarded.length()
                                                + " committed=" + committedLen + " gen=" + myGen);
                                    }
                                }
                            } else {
                                // 伺服器最終結果為空 → 用保留的整段音訊做一次乾淨重轉錄（有標點），
                                //       而非把無標點串流文字倒進輸入框。
                                Log.w(TAG, "[AudioStream] final 為空，改用整段音訊 HTTP fallback");
                                updateStatus("整理中…");
                                recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                            }
                            if (rescue) {
                                clipboardHelper.addToHistory(candidate);
                                updateStatus("辨識結果偏短，較完整版本已存剪貼簿");
                            }
                        });
                        Log.i(TAG, "[AudioStream] 最終文字: '" + truncate(finalText, 50) + "'");

                    } else if ("error".equals(type)) {
                        String msg = json.optString("message", "unknown");
                        if(requestedOpus&&msg.toLowerCase(java.util.Locale.ROOT).contains("opus"))opusDisabledForProcess=true;
                        Log.e(TAG, "[AudioStream] 伺服器錯誤: " + msg);
                        if (myGen == utteranceGeneration.get()) {
                            streamFailed = true;
                            audioStreamActive = false;
                            audioStreamWs = null;
                        }
                        String sessionId=pendingSessionByGeneration.get(myGen);
                        if(sessionId!=null&&voicePendingQueue!=null){voicePendingQueue.execute(() -> voicePendingQueue.markPending(sessionId,"ws_error:"+msg));recordVoiceEvent("ws_close",sessionId,voicePendingQueue.audioMs(sessionId),0,0,msg,"",voicePendingQueue.totalBytes(),0);}
                        mainHandler.post(() -> {
                            if (myGen != utteranceGeneration.get()) {
                                recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                            } else if (isRecording) {
                                updateStatus("🔴 錄音中…（連線中斷，本地暫存）");
                            } else {
                                updateStatus("整理中…");
                                recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[AudioStream] 訊息解析錯誤", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                if(capabilityProbeClosed)return;
                if (guardStoppedGenerations.contains(myGen)) return;
                Log.w(TAG, "[AudioStream] WebSocket 連線失敗（改走整段音訊 fallback）", t);
                String sessionId=pendingSessionByGeneration.get(myGen);
                if(sessionId!=null&&voicePendingQueue!=null){voicePendingQueue.execute(() -> voicePendingQueue.markPending(sessionId,"ws_failure:"+t.getClass().getSimpleName()));recordVoiceEvent("ws_close",sessionId,voicePendingQueue.audioMs(sessionId),0,response==null?0:response.code(),t.getClass().getSimpleName(),"",voicePendingQueue.totalBytes(),0);}
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
                        recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                    } else if (isRecording) {
                        updateStatus("🔴 錄音中…（連線中斷，本地暫存）");
                    } else {
                        updateStatus("整理中…");
                        recoverUnfinishedVoiceGeneration(myGen,"ws_unfinished");
                    }
                });
            }

            @Override
            public void onClosing(WebSocket ws,int code,String reason) {
                if(capabilityProbeClosed){ws.close(code,reason);return;}
                mainHandler.post(() -> recoverUnfinishedVoiceGeneration(myGen,"ws_closing_without_final"));
                ws.close(code,reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                if(capabilityProbeClosed)return;
                if (guardStoppedGenerations.contains(myGen)) return;
                Log.i(TAG, "[AudioStream] WebSocket 已關閉: " + code + " " + reason);
                mainHandler.post(() -> recoverUnfinishedVoiceGeneration(myGen,"ws_closed_without_final"));
                String id=pendingSessionByGeneration.get(myGen);
                if(id!=null&&voicePendingQueue!=null){recordVoiceEvent("ws_close",id,voicePendingQueue.audioMs(id),0,code,reason,"",voicePendingQueue.totalBytes(),0);
                    if(code!=1000){voicePendingQueue.execute(() -> voicePendingQueue.markPending(id,"ws_close:"+code));if(!isRecording)drainPendingVoiceQueue();}}
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

    private boolean voiceScreenOn() {
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE);
        android.view.Display display = dm == null ? null : dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        return (pm == null || pm.isInteractive()) && (display == null || display.getState() != android.view.Display.STATE_OFF);
    }

    private void scheduleVoiceSessionGuard(final int gen) {
        mainHandler.postDelayed(() -> {
            if (!isRecording || gen != activeUtteranceGeneration) return;
            VoiceSessionGuard guard = voiceSessionGuard;
            if (guard == null) return;
            String reason = guard.check(SystemClock.elapsedRealtime(), voiceScreenOn(), currentMode == Mode.APPEND && !isWatchService());
            if (!reason.isEmpty()) {
                guardStoppedGenerations.add(gen); guardStopReasons.put(gen,reason);
                String id=activePendingSessionId;
                recordVoiceEvent(reason,id,id==null?0:voicePendingQueue.audioMs(id),0,0,"","",0,0);
                stopRecordingAndSend();
                updateStatus(voiceGuardStatus(reason));
            } else scheduleVoiceSessionGuard(gen);
        }, 250L);
    }

    private String voiceGuardStatus(String reason) {
        if ("screen_off_idle_stop".equals(reason)) return "螢幕關閉且一分鐘沒收到語音，已停止錄音；音訊已保存";
        if ("cap_stop".equals(reason)) return "已達單次錄音時間上限，錄音已停止；音訊已保存";
        return "伺服器二十秒沒有辨識進度，已停止錄音；音訊已保存";
    }

    private void queueRecordingInChunks(int gen, String id, String reason) {
        if (audioStreamWs != null) audioStreamWs.cancel();
        audioStreamWs=null; audioStreamActive=false; streamedChunks.clear();
        guardStoppedGenerations.add(gen);
        reserveUtteranceGeneration(gen); completeReservedUtteranceWithoutText(gen);
        pendingSessionByGeneration.remove(gen);
        if (id != null && voicePendingQueue != null) {
            voicePendingQueue.markPendingAsync(id,reason, () -> {
                voicePendingQueue.releaseReceiptWait(id); drainPendingVoiceQueue();
            });
        }
        updateStatus("long_audio_pending".equals(reason) ? "錄音已保存，正在分段辨識" : voiceGuardStatus(reason));
    }

    protected void stopRecordingAndSend() {
        if (!isRecording) return;
        isRecording = false;
        recordingFinalizing=true;
        final Thread stoppedThread=recordingThread;
        final String stoppedSessionId=activePendingSessionId;
        onDeviceAppendPreviewEnabled = false;

        // v6.1: 停止錄音 → 釋放螢幕常亮、清掉鍵盤預覽列（輸入框本來就沒被碰過）
        if (rootView != null) rootView.setKeepScreenOn(false);
        updatePreviewStrip("");

        final boolean wasStreaming = streamingMode;
        final int myGen = activeUtteranceGeneration;
        recordVoiceStage("stop",myGen,stoppedSessionId,null);
        if (!isWatchService()) {
            recordingDurationMs.put(myGen, android.os.SystemClock.elapsedRealtime() - activeRecordingStartedMs);
        }
        streamingMode = false;

        synchronized (recorderRestartLock) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping recorder", e);
            }
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
            if(stoppedThread!=null)stoppedThread.join(2500L);
        } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        if(stoppedThread!=null&&stoppedThread.isAlive()) {
            finishWhenRecorderStopped(stoppedThread,wasStreaming,myGen,stoppedSessionId);
            return;
        }
        finishStoppedRecordingAndSend(wasStreaming,myGen,stoppedSessionId);
    }

    private void finishWhenRecorderStopped(Thread stoppedThread,boolean wasStreaming,int gen,String id) {
        if(stoppedThread.isAlive()) {
            mainHandler.postDelayed(() -> finishWhenRecorderStopped(stoppedThread,wasStreaming,gen,id),100L);
            return;
        }
        finishStoppedRecordingAndSend(wasStreaming,gen,id);
    }

    private void finishStoppedRecordingAndSend(boolean wasStreaming,int myGen,String stoppedSessionId) {
        if(stoppedSessionId!=null&&stoppedSessionId.equals(activePendingSessionId))activePendingSessionId=null;
        recordingFinalizing=false;
        if (!isWatchService()) appendRescueCandidates.put(myGen, appendRescueCandidate(myGen));
        byte[] pcmData = pcmBuffer.toByteArray();
        boolean memoryTruncated = pcmBuffer instanceof BoundedPcmBuffer && ((BoundedPcmBuffer)pcmBuffer).truncated();
        byte[] fullPreview = fullPcmBuffer != null && !(fullPcmBuffer instanceof BoundedPcmBuffer && ((BoundedPcmBuffer)fullPcmBuffer).truncated())
                ? fullPcmBuffer.toByteArray() : null;
        if (currentMode == Mode.APPEND) { if (fullPreview != null) rememberFullPcmForGeneration(myGen, fullPreview); }
        else if (!memoryTruncated) rememberFullPcmForGeneration(myGen, pcmData);
        pcmBuffer = null;
        List<Runnable> waiting=new ArrayList<>(afterRecordingFinalization);
        afterRecordingFinalization.clear();
        for(Runnable callback:waiting)callback.run();

        mainHandler.post(() -> {
            btnMic.setBackgroundColor(getResources().getColor(R.color.mic_idle, null));
            if (btnMic instanceof Button) ((Button) btnMic).setText("🎤");
            if (!isWatchService() && !guardStoppedGenerations.contains(myGen)) updateStatus("辨識中...");
        });

        if (guardStoppedGenerations.contains(myGen)) {
            queueRecordingInChunks(myGen, stoppedSessionId, guardStopReasons.remove(myGen));
            return;
        }

        if (isWatchService()) {
            markPendingGeneration(myGen,"watch_audio_waiting_receipt");
            onWatchAudio(pcmData);
            return;
        }

        // v6.20: 資料夾語音命名攔截
        if (folderNamingMode) {
            markPendingGeneration(myGen,"folder_name_audio_waiting_receipt");
            folderNamingMode = false;
            final byte[] namePcm = pcmData;
            final int gen = fieldGeneration;
            if (!memoryTruncated && namePcm.length >= 3200 && localSTTReady && localSTT != null) {
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
            recoverUnfinishedVoiceGeneration(myGen,"stream_failed_at_stop");
            return;
        }

        if(currentMode==Mode.APPEND) {
            armVoiceFinalDeadline(myGen);
            if(!audioStreamActive||audioStreamGeneration!=myGen) {
                audioStreamWs=null;audioStreamActive=false;
                recoverUnfinishedVoiceGeneration(myGen,"stopped_before_auth_ok");
                // onOpen still sends auth first; auth_ok closes a stopped generation.
                return;
            }
        }
        // === v4.4: 音訊串流收尾必須在 "太短" 檢查之前 ===
        // 修正 bug: pcmBuffer 在每次送 WS chunk 時 reset()，所以停止錄音時殘餘可能 < 3200
        // 但此時 WS 已經送了 N 個 chunks，不能 cancel()，必須 finalize
        if (currentMode == Mode.APPEND && audioStreamWs != null && streamChunkTotal > 0) {
            // v4.4.2: 送出所有殘餘音訊（不管多短），避免末尾 1-2 字被裁切
            if (pcmData.length > 0) {
                sendStreamAudio(audioStreamWs,pcmData,stoppedSessionId);
                Log.i(TAG, "[AudioStream] 送出剩餘音訊 (" + pcmData.length + " bytes)");
            }
            // Send finalize command
            sendAudioEndOfStream(audioStreamWs,stoppedSessionId);
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
            markPendingGeneration(myGen,"short_recording_waiting_receipt");
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
                sendStreamAudio(audioStreamWs,pcmData,stoppedSessionId);
                Log.i(TAG, "[AudioStream] 送出剩餘音訊 (" + pcmData.length + " bytes)");
            }
            // Send finalize command
            sendAudioEndOfStream(audioStreamWs,stoppedSessionId);
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

        if(lineGenerations.contains(myGen)) {
            byte[] wavData=pcmToWav(pcmData,SAMPLE_RATE,1,16);
            sendLineGhostwriterAudio(wavData, myGen);
            return;
        }
        // LINE REPLACE must never fall back to inserting the spoken directive or deleting a draft.
        if(lineGhostwriterField()) {updateStatus("嘴替錄音已失效，請重新錄音");return;}

        // === 非串流模式：本機 STT → 文字上傳 ===
        // v4.0: 只有 REPLACE 模式用本機 STT（需要游標上下文）
        // APPEND/SPELL/TRANSLATE 一律傳音訊到 Server（Groq Whisper 品質遠超手機 SenseVoice）
        if (!memoryTruncated && localSTTReady && currentMode == Mode.REPLACE && !REPLACE_SERVER_ASR) {
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
                            sendAiCommand(finalText, aiContextText,myGen);
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
                        sendAiCommandAudio(wavData, aiContextText,myGen);
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
            sendAiCommandAudio(wavData, aiContextText,myGen);
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
    private void feedOnDeviceAppendPreview(byte[] pcm, int byteCount, int gen) {
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
                    if (!isRecording || !onDeviceAppendPreviewEnabled || gen != activeUtteranceGeneration) return;
                    String mapped = englishMapper.apply(segmentText != null ? segmentText.trim() : "");
                    if (mapped == null || mapped.isEmpty()) return;

                    String live;
                    synchronized (onDeviceAppendPreviewLock) {
                        if (gen != activeUtteranceGeneration) return;
                        onDeviceAppendPreviewSegments.add(mapped);
                        live = TextLossGuard.joinDedup(onDeviceAppendPreviewSegments, 6);
                    }

                    String tail = live.length() > 28 ? "…" + live.substring(live.length() - 28) : live;
                    int liveLen = live.length();
                    mainHandler.post(() -> {
                        if (!isRecording || !onDeviceAppendPreviewEnabled || gen != activeUtteranceGeneration) return;
                        updatePreviewStrip("📱 " + tail);
                        updateStatus("聆聽中…（本機預覽 " + liveLen + " 字）");
                    });
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
                        if(consumeSilentResult(gen,finalText))return;
                        serverFinalGenerations.add(gen);
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
            sendAiCommandAudio(wavData, aiContextText,gen);
            return;
        }

        String serverUrl = getServerUrl();

        String endpoint;
        MultipartBody.Builder bodyBuilder = AppVersion.withAppVersion(new MultipartBody.Builder())
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav", durableOrMemoryAudioBody(gen,wavData))
                .addFormDataPart("client_session_id",pendingSessionByGeneration.getOrDefault(gen,""));

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

        OkHttpClient callClient = mode == Mode.REPLACE
                ? httpClient.newBuilder()
                        .readTimeout(60, TimeUnit.SECONDS)
                        .callTimeout(75, TimeUnit.SECONDS)
                        .build()
                : httpClient;
        callClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "WTI request failed", e);
                notePendingGeneration(gen,"http_failure:"+e.getClass().getSimpleName());
                if (allowOfflineAppendFallback && mode == Mode.APPEND) {
                    runOfflineFullAudioFallback(gen, "HTTP request failed: " + e.getMessage(), false);
                } else if (mode == Mode.REPLACE && !isWatchService()) {
                    rescueReplaceAudio(gen, false);
                } else {
                    if (mode == Mode.APPEND) {
                        completeReservedUtteranceWithoutText(gen);
                    }
                    markPendingGeneration(gen,"http_terminal_failure");
                    mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        notePendingGeneration(gen,"http_status:"+response.code());
                        if (allowOfflineAppendFallback && mode == Mode.APPEND) {
                            runOfflineFullAudioFallback(gen, "HTTP response " + response.code(), false);
                        } else if (mode == Mode.REPLACE && !isWatchService()) {
                            rescueReplaceAudio(gen, false);
                        } else {
                            if (mode == Mode.APPEND) {
                                completeReservedUtteranceWithoutText(gen);
                            }
                            markPendingGeneration(gen,"http_status:"+response.code());
                            mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                        }
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    VoicePendingQueue.parseSuccessfulResponse(responseBody);
                    serverFullAudioGenerations.add(gen);
                    handleWTIResponse(json,mode,gen);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing response", e);
                    notePendingGeneration(gen,"http_parse_failure:"+e.getClass().getSimpleName());
                    if (allowOfflineAppendFallback && mode == Mode.APPEND) {
                        runOfflineFullAudioFallback(gen, "HTTP response parse error: " + e.getMessage(), false);
                    } else if (mode == Mode.REPLACE && !isWatchService()) {
                        rescueReplaceAudio(gen, false);
                    } else {
                        if (mode == Mode.APPEND) {
                            completeReservedUtteranceWithoutText(gen);
                        }
                        markPendingGeneration(gen,"http_parse_failure");
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

        MultipartBody.Builder bodyBuilder = AppVersion.withAppVersion(new MultipartBody.Builder())
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
                    String text=VoicePendingQueue.parseSuccessfulResponse(responseBody);
                    mainHandler.post(() -> {
                        if(consumeSilentResult(gen,text,mode==Mode.APPEND,mode.name()))return;
                        if(mode==Mode.APPEND&&gen>0) {
                            if(utteranceAlreadyReserved||reserveUtteranceGeneration(gen))completeReservedUtteranceWithText(gen,text);
                        } else handleWTIResponse(json,mode,gen);
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
                // A late local result can arrive after the budget callback already ran.
                mainHandler.post(() -> {
                    if (committedGenerations.containsKey(gen)) rescueDiscardedLocal(gen, corrected.trim());
                });
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
            String fastText = onDeviceResult.get();
            if (fastText == null || fastText.isEmpty()) return;
            if (committedGenerations.containsKey(gen) || !reserveUtteranceGeneration(gen)) {
                rescueDiscardedLocal(gen, fastText);
                return;
            }
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

        MultipartBody body = AppVersion.withAppVersion(new MultipartBody.Builder())
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
                if (!isWatchService()) rescueReplaceAudio(gen, false);
                else mainHandler.post(() -> updateStatus("連線失敗: " + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        if (!isWatchService()) rescueReplaceAudio(gen, false);
                        else mainHandler.post(() -> updateStatus("伺服器錯誤: " + response.code()));
                        return;
                    }
                    JSONObject json = new JSONObject(responseBody);
                    handleWTIResponse(json, Mode.REPLACE, gen);
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing replace-text response", e);
                    if (!isWatchService()) rescueReplaceAudio(gen, false);
                    else mainHandler.post(() -> updateStatus("解析錯誤"));
                }
            }
        });
    }

    private void handleWTIResponse(JSONObject json,Mode mode,int gen) {
        mainHandler.post(() -> {
            if(isDiscardedVoiceGeneration(gen))return;
            try {
                String text=VoicePendingQueue.parseSuccessfulResponse(json.toString());
                String result=mode==Mode.REPLACE?json.optString("insert",text):text;
                boolean deleteOnly=mode==Mode.REPLACE&&VoiceResultText.isEmptyFinal(result)
                        &&(json.optInt("delete_before",0)>0||json.optInt("delete_after",0)>0);
                if(!deleteOnly&&consumeSilentResult(gen,result,mode==Mode.APPEND,mode.name()))return;
                if(mode!=Mode.APPEND)serverFinalGenerations.add(gen);
                if(mode==Mode.APPEND) {
                    if(reserveUtteranceGeneration(gen))completeReservedUtteranceWithText(gen,result);
                    return;
                }
                deliverVoiceResult(gen,result,deleteOnly,false,() -> {
                    InputConnection ic=getCurrentInputConnection();
                    if(mode==Mode.REPLACE) {
                        if(ic==null) {updateStatus("無法取得輸入連線");return;}
                        int before=json.optInt("delete_before",0),after=json.optInt("delete_after",0);
                        if(!deleteOnly&&!isWatchService()&&result.length()<=replaceShortChars
                                &&recordingDurationMs.getOrDefault(gen,0L)>=replaceLongAudioMs)rescueReplaceAudio(gen,true);
                        if(before>0||after>0) {
                            if(!isWatchService()&&clipboardHelper!=null) {
                                CharSequence b=before>0?ic.getTextBeforeCursor(before,0):"";
                                CharSequence a=after>0?ic.getTextAfterCursor(after,0):"";
                                clipboardHelper.addToHistory((b==null?"":b.toString())+(a==null?"":a.toString()));
                            }
                            if(!deleteSurroundingTextProgrammatically(ic,before,after))return;
                            if(deleteOnly) {lastCommitInsertedOrCopied=true;updateStatus("🔄 已刪除指定文字");return;}
                        }
                    }
                    commitFinalText(result);
                    updateStatus((mode==Mode.REPLACE?"🔄 替換: ":mode==Mode.SPELL?"✏️ 拼字: ":"🌐 翻譯: ")+truncate(result,25));
                });
            } catch(Exception e) {
                markPendingGeneration(gen,"mode_response_failure:"+e.getClass().getSimpleName());
                updateStatus("處理錯誤，音訊保留待重試");
            }
        });
    }

    // ==================== Keyboard Switching ====================

    private void recordBopomofoTouch(View v,String key,MotionEvent e) {
        updateCompleteTypingHint(true);
        recordTouchLearning(v,key,e,"bopomofo");
        captureBopomofoKeyTouch(v, key, e);
        if (!protectedInputField && touchShadow != null && pendingBopomofoKeyTouch != null
                && (isBopomofoSymbol(key) || "space".equals(key)))
            pendingBopomofoKeyTouch.shadow = touchShadow.observe(bopomofoKeyboard, key, e);
        if ("backspace".equals(key) && touchShadow != null) touchShadow.invalidate();
    }
    private void captureBopomofoKeyTouch(View v, String key, MotionEvent e) {
        try {
            int[] p = new int[2]; v.getLocationOnScreen(p);
            float cx = p[0] + v.getWidth() / 2f, cy = p[1] + v.getHeight() / 2f;
            pendingBopomofoKeyTouch = new BopomofoKeyTouch(key, e.getRawX() - cx, e.getRawY() - cy, cx, cy);
        } catch (Exception ignored) {}
    }
    private void recordKeyTouch(View v,String key,MotionEvent e,String page){
        recordTouchLearning(v,key,e,page);
        try{int[] p=new int[2];v.getLocationOnScreen(p);float cx=p[0]+v.getWidth()/2f,cy=p[1]+v.getHeight()/2f;
            float dx=e.getRawX()-cx,dy=e.getRawY()-cy;
            if(imeTelemetry!=null)imeTelemetry.key(page,key,dx,dy,cx,cy,protectedInputField);
        }catch(Exception ignored){}
    }
    private void recordTouchLearning(View v,String key,MotionEvent e,String page){
        try{int[] p=new int[2];v.getLocationOnScreen(p);float cx=p[0]+v.getWidth()/2f,cy=p[1]+v.getHeight()/2f;
            float dx=e.getRawX()-cx,dy=e.getRawY()-cy;EditorInfo info=getCurrentInputEditorInfo();
            if(touchLearning!=null&&!protectedInputField)
                touchLearning.touch(System.currentTimeMillis(),page,key,dx,dy,cx,cy,info==null?"":info.packageName,touchSessionId);
        }catch(Exception ignored){}
    }
    private void recordBopomofoKeyOutcome(String key, long keyToCandidateMs) {
        if (imeTelemetry == null) return;
        BopomofoKeyTouch touch = pendingBopomofoKeyTouch;
        pendingBopomofoKeyTouch = null;
        if (touch != null && key.equals(touch.key)) {
            imeTelemetry.bopomofoKey(key, touch.x, touch.y, touch.centerX, touch.centerY,
                    keyToCandidateMs, touch.shadow, protectedInputField);
        } else {
            // Programmatic input has no physical touch centre.  Preserve it as a distinct
            // outcome event so it can never replace a real touch-learning key event.
            imeTelemetry.keyOutcome("bopomofo", key, keyToCandidateMs, protectedInputField);
        }
    }

    private static boolean isProtectedInputField(EditorInfo i){if(i==null)return true;int flags=i.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;int cls=i.inputType&android.text.InputType.TYPE_MASK_CLASS,var=i.inputType&android.text.InputType.TYPE_MASK_VARIATION;boolean password=(cls==android.text.InputType.TYPE_CLASS_TEXT&&(var==android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD||var==android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||var==android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))||(cls==android.text.InputType.TYPE_CLASS_NUMBER&&var==android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);return flags!=0||password;}

    private void recordCandidateEvent(String page,List<String> candidates,int chosen){
        if(!protectedInputField&&touchShadow!=null&&"bopomofo".equals(page)&&candidates!=null&&chosen>=0&&chosen<candidates.size()){
            // Accepting top-1 confirms the physical stream. A corrective choice
            // lacks key-level ground truth; discard those labels rather than guess.
            if(chosen==0&&zhuyinInput.previewBoundary()<0)touchShadow.confirmChoice();else touchShadow.discardTrace();
        }
        if(imeTelemetry==null)return;
        try{JSONArray shown=new JSONArray();if(candidates!=null)for(int i=0;i<Math.min(10,candidates.size());i++)shown.put(candidates.get(i));
            imeTelemetry.record("candidate",page,new JSONObject().put("shown",shown).put("chosen_index",chosen).put("candidate_kind","bopomofo".equals(page)?renderedZhuyinCandidateKind:"engine").put("chosen_kind",chosen>=0&&zhuyinInput!=null&&"bopomofo".equals(page)?("association".equals(renderedZhuyinCandidateKind)?"association":zhuyinInput.candidateOrigin(chosen)):""),protectedInputField);
        }catch(Exception error){Log.w(TAG,"candidate telemetry unavailable",error);}
    }
    private void recordCommitEvent(String page,String text,String first,String ai,boolean corrected,boolean aiTaken){if(imeTelemetry==null)return;try{imeTelemetry.record("commit",page,new JSONObject().put("text",text).put("engine_top1",first==null?"":first).put("ai_suggestion",ai==null?"":ai).put("corrected",corrected).put("ai_taken",aiTaken).put("via","bopomofo".equals(page)?zhuyinCommitVia:"other").put("committed_codepoints",text.codePointCount(0,text.length())).put("ai_shown",sentencePhone!=null&&sentencePhone.correctionShown()).put("reverted_spans",sentencePhone==null?0:sentencePhone.revertedSpans()).put("candidate_taps",textCandidateTaps),protectedInputField);}catch(Exception ignored){}}
    private void recordCorrectionEvent(String page,String segment,String from,String to,String via){if(imeTelemetry==null)return;try{imeTelemetry.record("correction",page,new JSONObject().put("segment",segment).put("from",from).put("to",to).put("via",via),protectedInputField);}catch(Exception ignored){}}

    private void switchKeyboard(KeyboardMode mode) {
        // v6.23: clear English buffer when leaving English keyboard
        if (currentKeyboardMode == KeyboardMode.ENGLISH && mode != KeyboardMode.ENGLISH) {
            clearEnWordBuffer();
        }
        if (currentKeyboardMode == KeyboardMode.BOPOMOFO && mode != KeyboardMode.BOPOMOFO) {
            clearBopomofoBuffer();
        }
        currentKeyboardMode = mode;
        updateCompleteTypingHint(false);
        voiceKeyboard.setVisibility(mode == KeyboardMode.VOICE ? View.VISIBLE : View.GONE);
        bopomofoKeyboard.setVisibility(mode == KeyboardMode.BOPOMOFO ? View.VISIBLE : View.GONE);
        englishKeyboard.setVisibility(mode == KeyboardMode.ENGLISH ? View.VISIBLE : View.GONE);
        numbersKeyboard.setVisibility(mode == KeyboardMode.NUMBERS ? View.VISIBLE : View.GONE);
        // Close any open panel when switching keyboards
        closePanel();
    }

    /**
     * Recursively find all views with "key:xxx" tags and set up click listeners.
     */
    private void setupTypingKeyboard(View parent) { setupTypingKeyboard(parent,parent==bopomofoKeyboard); }
    private void setupTypingKeyboard(View parent,boolean traditionalBopomofo) {
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup vg = (ViewGroup) parent;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            Object tag = child.getTag();
            if (tag != null && tag.toString().startsWith("key:")) {
                String key = tag.toString().substring(4);
                if (key.equals("comma")) {
                    setupPunctuationKey(child, "，", ",");
                } else if (key.equals("backspace")) {
                    setupBackspaceTouch(child);
                } else {
                    if(traditionalBopomofo)child.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP)recordBopomofoTouch(v,key,e);return false;});
                    child.setOnClickListener(v -> onTypingKeyPressed(key));
                }
            }
            if (child instanceof ViewGroup) {
                setupTypingKeyboard(child,traditionalBopomofo);
            }
        }
    }

    private void updateCompleteTypingHint(boolean firstKey){
        if(bopomofoKeyboard==null)return;
        View hint=bopomofoKeyboard.findViewById(R.id.boCompleteTypingHint);if(hint==null)return;
        // The legacy tutorial disappears on the first phonetic key. In the
        // text-only layout, remove it before opening the input window instead
        // of relocating that window during the user's next touch.
        if(textLayoutSelected()){hint.setVisibility(View.GONE);return;}
        android.content.SharedPreferences prefs=getSharedPreferences("zhuyin_hint",MODE_PRIVATE);
        if(firstKey&&hint.getVisibility()==View.VISIBLE)prefs.edit().putBoolean("v650_complete_seen",true).apply();
        hint.setVisibility(!firstKey&&currentKeyboardMode==KeyboardMode.BOPOMOFO&&!prefs.getBoolean("v650_complete_seen",false)?View.VISIBLE:View.GONE);
    }
    private String cursorInteractionKind="char_tap",cursorInteractionVia="text_caret";
    private int cursorEditSlot=-1,cursorEditSyllable=-1;
    private String cursorFromKey="",cursorToKey="";
    private void recordCursorEvent(String kind,String action,String chosen,int rank,int deleted,int inserted) {
        if(imeTelemetry==null||protectedInputField)return;
        try {
            JSONObject sizes=new JSONObject().put("homophone",0).put("regroup",0).put("slip",0).put("ai",0);
            int total=zhuyinInput==null?0:zhuyinInput.state().candidates.size();
            for(int i=0;i<total;i++){String origin=zhuyinInput.candidateOrigin(i);if(sizes.has(origin))sizes.put(origin,sizes.getInt(origin)+1);}
            if(sentencePhone!=null)sizes.put("ai",sentencePhone.options(false).size());
            int slot=cursorEditSlot>=0?cursorEditSlot:zhuyinInput==null?-1:Math.max(-1,zhuyinInput.keyCaret()-1);
            int syllableIndex=-1,offset=0;
            if(zhuyinInput!=null&&slot>=0)for(String syllable:zhuyinInput.phoneticSyllables()){syllableIndex++;offset+=syllable.length();if(slot<offset)break;}
            if(cursorEditSyllable>=0)syllableIndex=cursorEditSyllable;
            imeTelemetry.record("cursor","bopomofo",new JSONObject().put("kind",kind).put("action",action)
                .put("slot_index",slot).put("syllable_index",syllableIndex).put("from_key",cursorFromKey).put("to_key",cursorToKey).put("via",cursorInteractionVia)
                .put("option_sizes",sizes).put("chosen_kind",chosen).put("chosen_rank",rank).put("group","ai".equals(chosen)||zhuyinInput==null?"word":zhuyinInput.candidateGroup(rank-1))
                .put("key_delete_count",deleted).put("key_insert_count",inserted),protectedInputField);
        } catch(Exception error){Log.w(TAG,"Cursor metadata could not be recorded",error);}
    }
    private void recordCursorKeyDifferences(String before,String after,List<String> syllables,String origin){
        if(before.length()!=after.length())return; // Partial commits consume keys; they are not symbol deletions.
        int changed=0;for(int i=0;i<before.length();i++)if(before.charAt(i)!=after.charAt(i))changed++;
        if(changed!=1)return; // Only a witnessed single-key replacement belongs to this cursor edit event.
        int prefix=0;while(prefix<Math.min(before.length(),after.length())&&before.charAt(prefix)==after.charAt(prefix))prefix++;
        int suffix=0;while(suffix<Math.min(before.length(),after.length())-prefix&&before.charAt(before.length()-1-suffix)==after.charAt(after.length()-1-suffix))suffix++;
        int removed=before.length()-prefix-suffix,inserted=after.length()-prefix-suffix;
        for(int i=0;i<Math.max(removed,inserted);i++){
            cursorEditSlot=prefix+i;cursorFromKey=i<removed?before.substring(prefix+i,prefix+i+1).replace(" ","ˉ"):"";
            cursorToKey=i<inserted?after.substring(prefix+i,prefix+i+1).replace(" ","ˉ"):"";
            int offset=0;cursorEditSyllable=-1;for(String syllable:syllables){cursorEditSyllable++;offset+=syllable.length();if(cursorEditSlot<offset)break;}
            recordCursorEvent(cursorInteractionKind,"edit",origin,-1,i<removed?1:0,i<inserted?1:0);
        }
        cursorEditSlot=cursorEditSyllable=-1;cursorFromKey=cursorToKey="";
    }
    private ZhuyinInputController.State pressZhuyinWithTouch(String key){
        zhuyinCommitVia="other";
        if(!"space".equals(key)&&!"enter".equals(key))sentenceInstalledCommit=null;
        boolean editing=zhuyinInput.keyCaret()>=0&&(isBopomofoSymbol(key)||"space".equals(key)||"backspace".equals(key));
        String keysBefore=zhuyinInput.sentenceKeys();
        int keyCountBefore=keysBefore.length(),beforeCaret=zhuyinInput.keyCaret();
        if(editing){cursorEditSlot="backspace".equals(key)?beforeCaret-1:beforeCaret;cursorFromKey="backspace".equals(key)&&beforeCaret>0?keysBefore.substring(beforeCaret-1,beforeCaret).replace(" ","ˉ"):"";cursorToKey="space".equals(key)?"ˉ":isBopomofoSymbol(key)?key:"";
            int offset=0;cursorEditSyllable=-1;for(String syllable:zhuyinInput.phoneticSyllables()){cursorEditSyllable++;offset+=syllable.length();if(cursorEditSlot<offset)break;}}
        if(sentencePhone!=null&&textLayoutSelected())sentencePhone.userTouched();
        if(sentencePhone!=null&&sentencePhone.beforeKey(key)){cursorEditSlot=cursorEditSyllable=-1;cursorFromKey=cursorToKey="";return zhuyinInput.state();}
        ZhuyinInputController.State state=zhuyinInput.press(key);
        if(editing){int after=zhuyinInput.sentenceKeys().length();recordCursorEvent("key_caret","edit","",-1,Math.max(0,keyCountBefore-after),(isBopomofoSymbol(key)||"space".equals(key))?1:0);cursorEditSlot=-1;cursorEditSyllable=-1;cursorFromKey=cursorToKey="";}
        if(!protectedInputField&&touchShadow!=null&&pendingBopomofoKeyTouch!=null&&key.equals(pendingBopomofoKeyTouch.key)&&pendingBopomofoKeyTouch.shadow!=null)touchShadow.recordRepairTouch(zhuyinInput);
        if(sentencePhone!=null)sentencePhone.touch(key,pendingBopomofoKeyTouch!=null&&key.equals(pendingBopomofoKeyTouch.key)?pendingBopomofoKeyTouch.shadow:null);
        return state;
    }
    private void onTypingKeyPressed(String key) {
        if (imeTelemetry != null) imeTelemetry.noteInput();
        if(currentKeyboardMode==KeyboardMode.BOPOMOFO)updateCompleteTypingHint(true);
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
                } else if (currentKeyboardMode == KeyboardMode.BOPOMOFO) {
                    long started = SystemClock.elapsedRealtime();
                    applyPhysicalTextKey("space");
                    recordBopomofoKeyOutcome("space", SystemClock.elapsedRealtime() - started);
                    return;
                }
                commitTextProgrammatically(ic, " ");
                break;
            case "enter":
                // v6.23: learn current word before enter (English only)
                if (currentKeyboardMode == KeyboardMode.ENGLISH) {
                    learnEnglishWord();
                    clearEnWordBuffer();
                } else if (currentKeyboardMode == KeyboardMode.BOPOMOFO) {
                    if(retainedTextNeedsReclaim)return;
                    zhuyinCommitVia="enter";
                    ZhuyinInputController.State state = zhuyinInput.press("enter");
                    applyZhuyinState(state);
                    if (state.commitText.isEmpty() && state.composingText.isEmpty()) handleEnterKey();
                    return;
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
            case "toBopomofo":
                switchKeyboard(KeyboardMode.BOPOMOFO);
                break;
            default:
                if (currentKeyboardMode == KeyboardMode.BOPOMOFO && isBopomofoSymbol(key)) {
                    long started = SystemClock.elapsedRealtime();
                    applyPhysicalTextKey(key);
                    recordBopomofoKeyOutcome(key, SystemClock.elapsedRealtime() - started);
                    break;
                }
                // A Zhuyin-page event is always consumed.  The controller owns
                // failure rendering; falling through would commit the physical
                // QWERTY keysym into the application.
                if (currentKeyboardMode == KeyboardMode.BOPOMOFO) return;
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

    protected boolean deleteSelectionIfAny(InputConnection ic) {
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
                    if (touchShadow != null) touchShadow.invalidate();
                    backspacePressed = true;
                    backspaceRepeatCount = 0;
                    InputConnection ic0 = getCurrentInputConnection();
                    if (ic0 != null) {
                        if (currentKeyboardMode == KeyboardMode.BOPOMOFO && !zhuyinInput.state().composingText.isEmpty()) {
                            String before = zhuyinInput.state().composingText;
                            ZhuyinInputController.State after = pressZhuyinWithTouch("backspace");
                            applyZhuyinState(after);
                            if (!before.equals(after.composingText))
                                recordCorrectionEvent("bopomofo", "all", before, after.composingText, "backspace");
                        } else if (!deleteSelectionIfAny(ic0)) {
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
                                if (currentKeyboardMode == KeyboardMode.BOPOMOFO && !zhuyinInput.state().composingText.isEmpty()) {
                                    applyZhuyinState(pressZhuyinWithTouch("backspace"));
                                    mainHandler.postDelayed(this, 120);
                                    return;
                                }
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
                    if (event.getAction() == MotionEvent.ACTION_UP && currentKeyboardMode == KeyboardMode.BOPOMOFO)
                        recordBopomofoTouch(v, "backspace", event);
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

    // ==================== Bopomofo System-1 Input ====================

    private static boolean isBopomofoSymbol(String key) {
        return key != null && key.length() == 1
                && "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙".contains(key);
    }

    private boolean commitZhuyinText(InputConnection ic,ZhuyinInputController.State state){
        if(!commitTextProgrammatically(ic,state.commitText))return false;
        recordCommitEvent("bopomofo",state.commitText,state.engineTop1,state.aiSuggestion,state.corrected,state.aiTaken);
        if(textLayoutSelected()){textCandidateTaps=0;if(sentencePhone!=null)sentencePhone.resetFeedback();}
        return true;
    }

    private boolean textLayoutSelected(){return !isWatchService()&&!"legacy_zhuyin".equals(getSharedPreferences("simon_ime_prefs",MODE_PRIVATE).getString("layout_mode","text_word_char"));}
    private TextView boTextCount;
    private void updateTextCount(){
        if(boTextCount==null||zhuyinInput==null)return;
        String preview=zhuyinInput.previewText();int count=preview.codePointCount(0,preview.length());
        android.content.SharedPreferences p=getSharedPreferences("simon_ime_prefs",MODE_PRIVATE);
        boolean enabled="live".equals(p.getString("ai_sentence_mode","suggestions"))&&p.getBoolean("ai_sentence_auto_apply",false);
        boTextCount.setText(count+" 字 · "+(count<10?"滿十字可啟用整句校正":enabled?"停一秒檢查整句校正":"已達十字門檻（校正關閉）"));
    }
    private void configureTextRows(){
        boolean text=textLayoutSelected();if(zhuyinInput!=null)zhuyinInput.setTextLayout(text);
        if(text&&(boTextCount==null||boTextCount.getParent()!=bopomofoKeyboard)&&bopomofoKeyboard instanceof ViewGroup){
            boTextCount=new TextView(this);boTextCount.setTag("composition-count");boTextCount.setTextSize(11);boTextCount.setTextColor(getColor(R.color.key_text));boTextCount.setPadding(dp(12),0,dp(12),0);
            ((ViewGroup)bopomofoKeyboard).addView(boTextCount,1,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(18)));
        }
        if(boTextCount!=null)boTextCount.setVisibility(text?View.VISIBLE:View.GONE);updateTextCount();
        configurePreviewViewport(text);
        View reading=rootView==null?null:rootView.findViewById(R.id.boPhoneticPreviewScroll);
        if(reading!=null)reading.setVisibility(text?View.GONE:View.VISIBLE);
        if(boWordCandidateScroll!=null)boWordCandidateScroll.setVisibility(text?View.VISIBLE:View.GONE);
    }
    private void configurePreviewViewport(boolean wrap){
        if(rootView==null)return;TextView preview=rootView.findViewById(R.id.boStreamPreview);if(preview==null)return;
        ViewGroup old=(ViewGroup)preview.getParent();
        preview.setSingleLine(!wrap);preview.setHorizontallyScrolling(!wrap);
        preview.setGravity(wrap?Gravity.TOP:Gravity.CENTER_VERTICAL);
        if(wrap&&old instanceof PreviewScrollView||!wrap&&old instanceof HorizontalScrollView)return;
        ViewGroup container=(ViewGroup)old.getParent();int index=container.indexOfChild(old);ViewGroup.LayoutParams outer=old.getLayoutParams();
        old.removeView(preview);container.removeViewAt(index);
        ViewGroup replacement=wrap?new PreviewScrollView(this,null):new HorizontalScrollView(this);
        replacement.setId(R.id.boStreamPreviewScroll);outer.height=wrap?ViewGroup.LayoutParams.WRAP_CONTENT:dp(50);
        replacement.setMinimumHeight(dp(50));replacement.setLayoutParams(outer);
        preview.setLayoutParams(new android.widget.FrameLayout.LayoutParams(wrap?ViewGroup.LayoutParams.MATCH_PARENT:ViewGroup.LayoutParams.WRAP_CONTENT,wrap?ViewGroup.LayoutParams.WRAP_CONTENT:ViewGroup.LayoutParams.MATCH_PARENT));
        replacement.addView(preview);container.addView(replacement,index);
    }
    private CharSequence shownTextPreview(){
        String shown=textLayoutSelected()?zhuyinInput.textPreview():zhuyinInput.previewText();
        android.text.SpannableString marked=sentencePhone==null?new android.text.SpannableString(shown):sentencePhone.mark(shown);
        int provisional=textLayoutSelected()?zhuyinInput.provisionalCharacter():-1;
        if(provisional>=0&&provisional<shown.codePointCount(0,shown.length())){int from=shown.offsetByCodePoints(0,provisional),to=shown.offsetByCodePoints(from,1);marked.setSpan(new android.text.style.ForegroundColorSpan(0xff999999),from,to,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return marked;
    }
    private void applyZhuyinState(ZhuyinInputController.State state) {
        if (state == null) return;
        if(zhuyinInput!=null&&!zhuyinInput.previewText().isEmpty()){selectionRevision++;clearExternalSelection();}
        if(retainedTextNeedsReclaim){
            renderZhuyinStreamPreview(zhuyinInput.textPreview());updateTextCount();return;
        }
        if(sentencePhone!=null)sentencePhone.changed(false);
        renderZhuyinStreamPreview(textLayoutSelected()?zhuyinInput.textPreview():zhuyinInput.previewText());
        if(boStreamPreview!=null)boStreamPreview.setText(shownTextPreview());
        updateTextCount();
        if(boStreamPreview instanceof PreviewCursorView)((PreviewCursorView)boStreamPreview).setBoundary(zhuyinInput.keyCaret()>=0?zhuyinInput.keyPreviewCaret():zhuyinInput.previewBoundary()>=0?zhuyinInput.previewBoundary():zhuyinInput.wordFocused()?zhuyinInput.tappedCharacter():-1);
        TextView reading=bopomofoKeyboard==null?null:bopomofoKeyboard.findViewById(R.id.boPhoneticPreview);
        if(reading!=null&&textLayoutSelected()){reading.setText("");reading.setContentDescription("");}
        if(reading!=null&&!textLayoutSelected()){
            String phonetic=zhuyinInput.phoneticText();
            android.text.SpannableString spaced=new android.text.SpannableString(phonetic);
            for(int i=0;i<phonetic.length();i++)spaced.setSpan(new android.text.style.ReplacementSpan(){
                @Override public int getSize(android.graphics.Paint paint,CharSequence text,int start,int end,android.graphics.Paint.FontMetricsInt fm){if(fm!=null)paint.getFontMetricsInt(fm);return Math.max(dp(24),(int)Math.ceil(paint.measureText(text,start,end)));}
                @Override public void draw(android.graphics.Canvas canvas,CharSequence text,int start,int end,float x,int top,int y,int bottom,android.graphics.Paint paint){float width=Math.max(dp(24),paint.measureText(text,start,end));canvas.drawText(text,start,end,x+(width-paint.measureText(text,start,end))/2,y,paint);}
            },i,i+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            reading.setText(spaced);
            int slot=zhuyinInput.keyCaret(),start=0,end=0;
            for(String syllable:zhuyinInput.phoneticSyllables()){end=start+syllable.length();if((slot==0&&start==0)||(slot>start&&slot<=end))break;start=end;}
            if(reading instanceof PreviewCursorView)((PreviewCursorView)reading).setActiveSpan(slot>=0?start:-1,slot>=0?end:-1);
            reading.setContentDescription("注音游標 "+slot+"；作用音節 "+start+"–"+end);
            if(reading instanceof PreviewCursorView)((PreviewCursorView)reading).setBoundary(zhuyinInput.keyCaret());
            installKeyCaretTouch(reading);
            revealZhuyinReadingAfterLayout(reading);
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (!state.commitText.isEmpty()) {
                commitZhuyinText(ic,state);
                if(sentencePhone!=null)sentencePhone.committed(state.commitText,state.commitText.equals(sentenceInstalledCommit));
                sentenceInstalledCommit=null;
                zhuyinComposingConnection = null;
                confirmStableZhuyinCommit(ic, state.commitText);
                if (!protectedInputField && lastCommittedZhuyinWord != null && zhuyinAssociationHistory != null)
                    zhuyinAssociationHistory.record(lastCommittedZhuyinWord, state.commitText);
                lastCommittedZhuyinWord = protectedInputField ? null : state.commitText;
                if (zhuyinWordIndex != null && state.composingText.isEmpty()) {
                    java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>(zhuyinWordIndex.continuations(state.commitText));
                    if (!protectedInputField && zhuyinAssociationHistory != null) {
                        next.addAll(zhuyinAssociationHistory.nextWords(state.commitText));
                        next = new java.util.LinkedHashSet<>(zhuyinAssociationHistory.rankCandidates(new ArrayList<>(next), x -> x, state.commitText, "", "", ""));
                    }
                    if (!next.isEmpty()) state = zhuyinInput.showAssociations(new ArrayList<>(next));
                }
            }
            if (state.composingText.isEmpty()) {
                // finishComposingText only removes spans; replace the owned preedit
                // first so deleting its last symbol cannot leave committed residue.
                // An idle/new editor may have selected text that must stay untouched.
                if (zhuyinComposingConnection == ic) ic.setComposingText("", 1);
                ic.finishComposingText();
                zhuyinComposingConnection = null;
            }
            else {
                android.text.SpannableString composing=highlightZhuyinTarget(textLayoutSelected()?zhuyinInput.textPreview():state.composingText,state.targetStart,state.targetEnd);
                if (ic.setComposingText(composing, 1)) {
                    zhuyinComposingConnection = ic; // cursor stays after the full composition
                    if (sentencePhone != null) sentencePhone.written();
                }
            }
        }
        if(state.composingText.isEmpty()){retainedTextNeedsReclaim=false;cancelPendingTextCandidates();lastTextKeyUptime=-1;retainedTextEnd=-1;retainedTextField=retainedTextBefore=retainedText="";}
        else if(textLayoutSelected())rememberTextComposition(ic,zhuyinInput.textPreview());

        if(zhuyinInput.showIdleShortcuts()) renderZhuyinShortcuts();
        else if(textLayoutSelected()){renderTextCandidateRows();}
        else { renderZhuyinCandidates(state.candidates, state.candidateKind); renderSentenceOptions(); }
    }

    private void revealZhuyinReadingAfterLayout(TextView reading) {
        revealZhuyinPreviewAfterLayout(reading, true);
    }

    private void revealZhuyinPreviewAfterLayout(TextView row, boolean phonetic) {
        PreviewReveal pending=phonetic?readingReveal:textReveal;
        if(pending!=null){
            if(pending.reading==row)return;
            pending.cancel();
        }
        PreviewReveal reveal=new PreviewReveal(row,phonetic);
        if(phonetic)readingReveal=reveal;else textReveal=reveal;
        row.addOnAttachStateChangeListener(reveal);
        reveal.tree.addOnPreDrawListener(reveal);
    }

    private final class PreviewReveal implements android.view.ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
        final TextView reading;
        final boolean phonetic;
        android.view.ViewTreeObserver tree;
        PreviewReveal(TextView reading,boolean phonetic){this.reading=reading;this.phonetic=phonetic;tree=reading.getViewTreeObserver();}
        void cancel(){
            if(tree.isAlive())tree.removeOnPreDrawListener(this);
            android.view.ViewTreeObserver current=reading.getViewTreeObserver();
            if(current!=tree&&current.isAlive())current.removeOnPreDrawListener(this);
            reading.removeOnAttachStateChangeListener(this);
            if(readingReveal==this)readingReveal=null;
            if(textReveal==this)textReveal=null;
        }
        @Override public void onViewAttachedToWindow(View view){tree=reading.getViewTreeObserver();}
        @Override public void onViewDetachedFromWindow(View view){cancel();}
        @Override public boolean onPreDraw(){
            cancel();
            if(!reading.isAttachedToWindow()||zhuyinInput==null||reading.getLayout()==null)return true;
            if(!phonetic&&reading.getParent() instanceof PreviewScrollView){
                int cp=zhuyinInput.keyCaret()>=0?zhuyinInput.keyPreviewCaret():zhuyinInput.previewBoundary()>=0?zhuyinInput.previewBoundary():zhuyinInput.wordFocused()?zhuyinInput.tappedCharacter():reading.getText().toString().codePointCount(0,reading.getText().length());
                ((PreviewScrollView)reading.getParent()).revealBoundary(cp);return true;
            }
            if(!(reading.getParent() instanceof HorizontalScrollView))return true;
            int at;boolean centered;
            if(phonetic){
                at=zhuyinInput.keyCaret();centered=at>=0;
                if(at<0){
                    int boundary=zhuyinInput.previewBoundary();
                    if(boundary>=0){
                        centered=true;at=0;int index=0;
                        for(String syllable:zhuyinInput.phoneticSyllables()){if(index++>=boundary)break;at+=syllable.length();}
                    }else if(!zhuyinInput.wordFocused())at=reading.getText().length();
                    else return true;
                }
                at=Math.min(at,reading.getText().length());
            }else{
                String text=reading.getText().toString();int count=text.codePointCount(0,text.length());
                int cp=zhuyinInput.keyCaret()>=0?zhuyinInput.keyPreviewCaret():zhuyinInput.previewBoundary()>=0?zhuyinInput.previewBoundary():zhuyinInput.wordFocused()?zhuyinInput.tappedCharacter():count;
                centered=zhuyinInput.keyCaret()>=0||zhuyinInput.previewBoundary()>=0||zhuyinInput.wordFocused();
                at=text.offsetByCodePoints(0,Math.max(0,Math.min(count,cp)));
            }
            int x=Math.round(reading.getLayout().getPrimaryHorizontal(at))+reading.getPaddingLeft();
            HorizontalScrollView scroll=(HorizontalScrollView)reading.getParent();
            // Explicit caret moves retain their original centered context.
            if(centered){scroll.scrollTo(Math.max(0,x-scroll.getWidth()/2),0);return true;}
            int left=scroll.getScrollX(),right=left+scroll.getWidth(),handle=dp(5);
            // Forward typing reveals only what left the viewport, without an
            // animation towards a stale width or a second scroll mechanism.
            if(x<left+handle)scroll.scrollTo(Math.max(0,Math.min(left,x-Math.max(reading.getPaddingLeft(),handle))),0);
            else if(x>right-handle)scroll.scrollTo(Math.max(left,x+Math.max(reading.getPaddingRight(),handle)-scroll.getWidth()),0);
            return true;
        }
    }

    private void confirmStableZhuyinCommit(InputConnection ic, String committed) {
        if (protectedInputField || touchShadow == null) return;
        try {
            android.view.inputmethod.ExtractedText value = ic.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (value == null || value.text == null || value.selectionStart < committed.length()
                    || value.selectionStart > value.text.length()) { touchShadow.invalidate(); return; }
            final String prefix = value.text.subSequence(0, value.selectionStart).toString();
            if (!prefix.endsWith(committed)) { touchShadow.invalidate(); return; }
            final String session = touchSessionId;
            touchShadow.confirmAfterDelay(() -> {
                if (protectedInputField || !session.equals(touchSessionId)) return false;
                InputConnection current = getCurrentInputConnection();
                if (current == null) return false;
                android.view.inputmethod.ExtractedText after = current.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(), 0);
                return after != null && after.text != null && after.text.toString().startsWith(prefix);
            });
        } catch (Exception error) { touchShadow.invalidate(); Log.w(TAG, "Touch confirmation unavailable", error); }
    }

    private void renderZhuyinStreamPreview(String text) {
        if (boStreamPreview == null) return;
        if(layoutDiagnostics!=null)layoutDiagnostics.composition(text!=null&&!text.isEmpty());
        if (text == null || text.isEmpty()) {
            boStreamPreview.setText("");
            return;
        }
        boStreamPreview.setText(text);
        if(boStreamPreview.getTag(R.id.boStreamPreview)==null){
        boStreamPreview.setTag(R.id.boStreamPreview,Boolean.TRUE);
        boStreamPreview.setOnTouchListener(new View.OnTouchListener() {
            float downX,downY;boolean dragging;int lastBoundary=-1;
            @Override public boolean onTouch(View v,MotionEvent event) {
                android.text.Layout layout=boStreamPreview.getLayout();if(layout==null||zhuyinInput==null)return false;
                float x=event.getX()-boStreamPreview.getTotalPaddingLeft()+boStreamPreview.getScrollX();
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    downX=event.getX();downY=event.getY();dragging=false;lastBoundary=-1;
                    v.getParent().requestDisallowInterceptTouchEvent(true);return true;
                }
                if(action==MotionEvent.ACTION_CANCEL){if(v instanceof PreviewCursorView)((PreviewCursorView)v).setDragging(false);v.getParent().requestDisallowInterceptTouchEvent(false);return true;}
                if(action!=MotionEvent.ACTION_MOVE&&action!=MotionEvent.ACTION_UP)return true;
                if(action==MotionEvent.ACTION_MOVE&&v.getParent() instanceof PreviewScrollView&&Math.abs(event.getY()-downY)>dp(8)&&Math.abs(event.getY()-downY)>Math.abs(event.getX()-downX)){
                    v.getParent().requestDisallowInterceptTouchEvent(false);return false;
                }
                if(Math.abs(event.getX()-downX)>dp(4)||Math.abs(event.getY()-downY)>dp(4))dragging=true;
                if(v instanceof PreviewCursorView)((PreviewCursorView)v).setDragging(dragging&&action!=MotionEvent.ACTION_UP);
                String shown=boStreamPreview.getText().toString();int count=shown.codePointCount(0,shown.length());
                int touchedLine=layout.getLineForVertical(Math.round(event.getY()-boStreamPreview.getTotalPaddingTop()+boStreamPreview.getScrollY()));
                int nearest=0;float distance=Float.MAX_VALUE;
                for(int cp=0;cp<=count;cp++){
                    int offset=shown.offsetByCodePoints(0,cp);if(layout.getLineForOffset(offset)!=touchedLine)continue;
                    float at=layout.getPrimaryHorizontal(offset);
                    float d=Math.abs(x-at);if(d<distance){nearest=cp;distance=d;}
                }
                if(action==MotionEvent.ACTION_UP&&!dragging&&sentencePhone!=null){
                    int touched=layout.getOffsetForHorizontal(touchedLine,x);if(touched>0&&layout.getPrimaryHorizontal(touched)>x)touched--;
                    if(sentencePhone.tapRevert(touched)){v.getParent().requestDisallowInterceptTouchEvent(false);return true;}
                }
                boolean boundary=dragging||distance<=dp(4);
                if(action==MotionEvent.ACTION_UP&&!boundary){
                    int utf=layout.getOffsetForHorizontal(touchedLine,x);int cp=shown.codePointCount(0,Math.min(utf,shown.length()));
                    if(cp>0&&layout.getPrimaryHorizontal(utf)>x)cp--;
                    if(touchShadow!=null)touchShadow.invalidate();
                    if(sentencePhone!=null)sentencePhone.userTouched();
                    cursorInteractionKind="char_tap";cursorInteractionVia="text_caret";
                    applyZhuyinState(zhuyinInput.moveCursorToPreviewBoundary(nearest));
                    recordCursorEvent("char_tap",zhuyinInput.wordFocused()?"open":"cancel","",-1,0,0);
                }else if(boundary&&nearest!=lastBoundary){
                    lastBoundary=nearest;if(touchShadow!=null)touchShadow.invalidate();
                    if(sentencePhone!=null)sentencePhone.userTouched();
                    cursorInteractionKind=dragging?"drag":"boundary";cursorInteractionVia="text_caret";
                    applyZhuyinState(zhuyinInput.moveCursorToPreviewBoundary(nearest));
                    recordCursorEvent(cursorInteractionKind,"open","",-1,0,0);
                }
                if(action==MotionEvent.ACTION_UP)v.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        });
        }
        revealZhuyinPreviewAfterLayout(boStreamPreview,false);
    }

    private void installKeyCaretTouch(TextView row) {
        if(row.getTag(R.id.boPhoneticPreview)!=null)return;
        row.setTag(R.id.boPhoneticPreview,Boolean.TRUE);
        row.setOnTouchListener(new View.OnTouchListener(){
            float downX;boolean dragging,scrolling;int scrollStart;int last=-1;
            @Override public boolean onTouch(View view,MotionEvent event){
                if(zhuyinInput==null||row.getLayout()==null)return false;
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    downX=event.getRawX();dragging=false;scrolling=false;last=-1;
                    scrollStart=view.getParent() instanceof HorizontalScrollView?((HorizontalScrollView)view.getParent()).getScrollX():0;
                    view.getParent().requestDisallowInterceptTouchEvent(true);return true;
                }
                if(action==MotionEvent.ACTION_CANCEL){if(row instanceof PreviewCursorView)((PreviewCursorView)row).setDragging(false);view.getParent().requestDisallowInterceptTouchEvent(false);return true;}
                if(action!=MotionEvent.ACTION_UP&&action!=MotionEvent.ACTION_MOVE)return true;
                float travel=event.getRawX()-downX;
                if(Math.abs(travel)>dp(4))dragging=true;
                if(Math.abs(travel)>dp(48))scrolling=true;
                if(scrolling&&view.getParent() instanceof HorizontalScrollView){
                    ((HorizontalScrollView)view.getParent()).scrollTo(Math.max(0,scrollStart-Math.round(travel)),0);
                    if(row instanceof PreviewCursorView)((PreviewCursorView)row).setDragging(false);
                    if(action==MotionEvent.ACTION_UP)view.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                if(row instanceof PreviewCursorView)((PreviewCursorView)row).setDragging(dragging&&action!=MotionEvent.ACTION_UP);
                // Short drags select a key on release; a broad swipe scrolls without
                // moving the caret or scheduling caret-centering callbacks.
                if(action==MotionEvent.ACTION_MOVE)return true;
                String text=row.getText().toString();float x=event.getX()-row.getTotalPaddingLeft()+row.getScrollX();
                int nearest=0;float distance=Float.MAX_VALUE;
                for(int cp=0;cp<=text.codePointCount(0,text.length());cp++){
                    float at=row.getLayout().getPrimaryHorizontal(text.offsetByCodePoints(0,cp));
                    if(Math.abs(at-x)<distance){distance=Math.abs(at-x);nearest=cp;}
                }
                int utf=text.offsetByCodePoints(0,nearest);
                int key=text.substring(0,utf).replace("│","").length();
                if(key!=last){
                    last=key;cursorInteractionKind=dragging?"drag":"key_caret";cursorInteractionVia=dragging?"key_caret_drag":"key_caret_tap";if(touchShadow!=null)touchShadow.invalidate();
                    applyZhuyinState(zhuyinInput.moveCursorToKey(key));
                    recordCursorEvent(dragging?"drag":"key_caret",zhuyinInput.wordFocused()?"open":"cancel","",-1,0,0);
                }
                if(action==MotionEvent.ACTION_UP)view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        });
    }

    private TextView sentenceUndo(){
        TextView item=new TextView(this);item.setText("復原");item.setTag("sentence-option");item.setContentDescription("復原 AI 選項");
        item.setTextColor(getColor(R.color.key_text));item.setTextSize(16);item.setGravity(Gravity.CENTER);item.setPadding(dp(12),0,dp(12),0);
        item.setOnClickListener(v->sentencePhone.undo());return item;
    }
    private final java.util.Map<Integer,View> rowEngineViews=new java.util.LinkedHashMap<>();
    private TextView sentenceOption(JSONObject candidate){
        String text=sentencePhone.optionText(candidate);
        android.text.SpannableString diff=new android.text.SpannableString(text);
        if(!text.isEmpty())diff.setSpan(new android.text.style.BackgroundColorSpan(0xff805900),0,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView item=new TextView(this);item.setTag("sentence-option");item.setText(diff);item.setContentDescription(("local".equals(candidate.optString("source"))?"本機校正 ":"AI 選項 ")+text);
        item.setTextColor(getColor(R.color.key_text));item.setTextSize(16);item.setGravity(Gravity.CENTER);item.setPadding(dp(12),0,dp(12),0);
        item.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener(){
            boolean done;
            public void onDraw(){if(done||!item.isShown())return;done=true;sentencePhone.rendered(candidate,((ViewGroup)item.getParent()).indexOfChild(item)+1);item.post(()->{if(item.getViewTreeObserver().isAlive())item.getViewTreeObserver().removeOnDrawListener(this);});}
        });
        item.setOnClickListener(v->{
            int rank=((ViewGroup)item.getParent()).indexOfChild(item)+1;
            recordCursorEvent(cursorInteractionKind,"choose","ai",rank,0,0);sentencePhone.apply(candidate);
        });return item;
    }
    private void applyPhysicalTextKey(String key){
        lastTextKeyUptime=SystemClock.uptimeMillis();textKeyRender=true;
        try{applyZhuyinState(pressZhuyinWithTouch(key));}finally{textKeyRender=false;}
    }
    private void cancelPendingTextCandidates(){
        if(pendingTextCandidates!=null&&mainHandler!=null)mainHandler.removeCallbacks(pendingTextCandidates);
        pendingTextCandidates=null;
    }
    private boolean textCandidateRefreshRunning,textKeyRender;
    private String rankingBefore="",rankingAfter="";
    private long rankingField=-1,rankingSelection=0;
    private Runnable pendingRankingContext;
    private void clearRankingContext(){
        rankingSelection++;
        if(pendingRankingContext!=null&&mainHandler!=null)mainHandler.removeCallbacks(pendingRankingContext);
        pendingRankingContext=null;rankingBefore=rankingAfter="";rankingField=-1;
    }
    private void deferRankingContext(){
        clearRankingContext();
        if(mainHandler==null||protectedInputField||currentKeyboardMode!=KeyboardMode.BOPOMOFO||!textLayoutSelected()||getCurrentInputConnection()==null)return;
        final long selection=rankingSelection,field=fieldGeneration;
        final InputConnection connection=getCurrentInputConnection();
        final ZhuyinInputController owner=zhuyinInput;
        pendingRankingContext=()->{
            pendingRankingContext=null;
            if(selection!=rankingSelection||field!=fieldGeneration||protectedInputField||currentKeyboardMode!=KeyboardMode.BOPOMOFO||!isInputViewShown()
                    ||connection!=getCurrentInputConnection()||owner!=zhuyinInput)return;
            try{
                CharSequence left=connection.getTextBeforeCursor(50,0),right=connection.getTextAfterCursor(30,0);
                if(selection!=rankingSelection||field!=fieldGeneration||protectedInputField||connection!=getCurrentInputConnection()||owner!=zhuyinInput||currentKeyboardMode!=KeyboardMode.BOPOMOFO||!isInputViewShown())return;
                rankingBefore=left==null?"":left.toString();rankingAfter=right==null?"":right.toString();
                String composition=owner==null?"":owner.textPreview();
                if(!composition.isEmpty()&&rankingBefore.endsWith(composition))rankingBefore=rankingBefore.substring(0,rankingBefore.length()-composition.length());
                rankingField=field;
            }catch(RuntimeException error){rankingBefore=rankingAfter="";rankingField=-1;Log.w(TAG,"Local ranking context unavailable",error);return;}
            if(zhuyinInput!=null)deferTextCandidateRows();
        };
        mainHandler.postDelayed(pendingRankingContext,100);
    }
    private List<ZhuyinInputController.TextChoice> externalTextChoices=Collections.emptyList();
    private InputConnection externalConnection;
    private long externalField,externalRevision,selectionRevision;
    private int selectionStart=-1,selectionEnd=-1,externalOffset;
    private String externalText="";
    private void clearExternalSelection(){externalConnection=null;externalTextChoices=Collections.emptyList();externalText="";}
    private android.view.inputmethod.ExtractedText readExternalText(InputConnection connection){
        try{return connection.getExtractedText(new android.view.inputmethod.ExtractedTextRequest(),0);}
        catch(RuntimeException unavailable){return null;}
    }
    private boolean externalSelectionCurrent(){
        if(protectedInputField||externalConnection==null||externalConnection!=getCurrentInputConnection()
                ||externalField!=fieldGeneration||externalRevision!=selectionRevision||selectionStart<0||selectionStart==selectionEnd)return false;
        android.view.inputmethod.ExtractedText value=readExternalText(externalConnection);
        return value!=null&&value.text!=null&&value.startOffset==externalOffset
                &&value.partialStartOffset<0&&value.partialEndOffset<0&&externalText.contentEquals(value.text)
                &&Math.min(value.selectionStart,value.selectionEnd)+value.startOffset==selectionStart&&Math.max(value.selectionStart,value.selectionEnd)+value.startOffset==selectionEnd;
    }
    private void updateExternalSelection(int start,int end){
        selectionRevision++;selectionStart=Math.min(start,end);selectionEnd=Math.max(start,end);clearExternalSelection();
        if(!textLayoutSelected()||protectedInputField||zhuyinInput==null||!zhuyinInput.previewText().isEmpty()||start<0||end<0||start==end)return;
        final long revision=selectionRevision,field=fieldGeneration;final InputConnection connection=getCurrentInputConnection();
        if(mainHandler==null||connection==null)return;
        mainHandler.postDelayed(()->{
            if(revision!=selectionRevision||field!=fieldGeneration||connection!=getCurrentInputConnection()||protectedInputField||!isInputViewShown()||!zhuyinInput.previewText().isEmpty())return;
            android.view.inputmethod.ExtractedText value=readExternalText(connection);
            if(value==null||value.text==null||value.partialStartOffset>=0||value.partialEndOffset>=0){
                android.widget.Toast.makeText(this,"此輸入框未提供可讀取的選取文字",android.widget.Toast.LENGTH_SHORT).show();return;
            }
            int left=selectionStart-value.startOffset,right=selectionEnd-value.startOffset;
            if(left<0||right>value.text.length()||left>=right||Math.min(value.selectionStart,value.selectionEnd)+value.startOffset!=selectionStart||Math.max(value.selectionStart,value.selectionEnd)+value.startOffset!=selectionEnd)return;
            String selected=value.text.subSequence(left,right).toString();
            List<ZhuyinInputController.TextChoice> choices=zhuyinInput.externalTextChoices(selected);
            if(revision!=selectionRevision||field!=fieldGeneration||connection!=getCurrentInputConnection()||protectedInputField)return;
            externalConnection=connection;externalField=field;externalRevision=revision;externalOffset=value.startOffset;
            externalText=value.text.toString();externalTextChoices=choices;
            if(!externalSelectionCurrent()){clearExternalSelection();return;}
            renderTextCandidateRows();
        },100);
    }
    private void chooseExternalTextCandidate(ZhuyinInputController.TextChoice choice){
        if(!externalTextChoices.contains(choice)||!externalSelectionCurrent()){clearExternalSelection();renderTextCandidateRows();return;}
        InputConnection connection=externalConnection;
        connection.beginBatchEdit();
        try{
            // Commit into the validated existing selection; never replay preview or reset its range.
            connection.commitText(choice.label,1);
        }catch(RuntimeException unavailable){Log.w(TAG,"Selected text replacement unavailable: "+unavailable.getClass().getSimpleName());}
        finally{connection.endBatchEdit();clearExternalSelection();renderTextCandidateRows();}
    }
    private final List<TextView> textWordViewPool=new ArrayList<>(),textCharViewPool=new ArrayList<>();
    private void deferTextCandidateRows(){
        // Keep the first deadline: subsequent keys must not postpone a pending refresh.
        if(pendingTextCandidates!=null)return;
        final ZhuyinInputController owner=zhuyinInput;final long field=fieldGeneration;
        for(LinearLayout row:new LinearLayout[]{boWordCandidateItems,boCandidateItems})
            for(int n=0;n<row.getChildCount();n++)row.getChildAt(n).setEnabled(false);
        pendingTextCandidates=()->{
            pendingTextCandidates=null;
            if(zhuyinInput!=owner||fieldGeneration!=field||currentKeyboardMode!=KeyboardMode.BOPOMOFO||!isInputViewShown())return;
            long started=SystemClock.elapsedRealtime();textCandidateRefreshRunning=true;
            try{renderTextCandidateRows();}finally{textCandidateRefreshRunning=false;}
            if(imeTelemetry!=null)try{imeTelemetry.record("key_outcome","bopomofo",new JSONObject().put("key","").put("key_to_candidate_ms",JSONObject.NULL).put("step","text_candidates_refresh").put("ms",SystemClock.elapsedRealtime()-started).put("ok",true).put("debounce_ms",100),false);}catch(Exception ignored){}
        };
        mainHandler.postDelayed(pendingTextCandidates,100);
    }
    private TextView pooledTextChoice(List<TextView> pool,int index,ZhuyinInputController.TextChoice choice){
        if(index==pool.size()){
            TextView item=new TextView(this);item.setTextSize(18);item.setGravity(Gravity.CENTER);item.setPadding(dp(12),0,dp(12),0);
            item.setOnClickListener(v->{ZhuyinInputController.TextChoice selected=(ZhuyinInputController.TextChoice)v.getTag();if(externalTextChoices.contains(selected)){chooseExternalTextCandidate(selected);return;}textCandidateTaps++;if(sentencePhone!=null)sentencePhone.userSelected();applyZhuyinState(zhuyinInput.chooseTextCandidate(selected));});pool.add(item);
        }
        TextView item=pool.get(index);if(!choice.label.contentEquals(item.getText()))item.setText(choice.label);
        item.setTag(choice);item.setEnabled(true);item.setTextColor(boStreamPreview.getCurrentTextColor());return item;
    }
    private void reconcileTextRow(LinearLayout row,List<View> desired){
        for(int n=0;n<desired.size();n++){
            View view=desired.get(n);if(n<row.getChildCount()&&row.getChildAt(n)==view)continue;
            if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);
            row.addView(view,n,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.MATCH_PARENT));
        }
        while(row.getChildCount()>desired.size())row.removeViewAt(row.getChildCount()-1);
    }
    private void renderTextCandidateRows(){
        if(boWordCandidateItems==null||boCandidateItems==null||zhuyinInput==null)return;
        boolean focused=zhuyinInput.wordFocused()||zhuyinInput.previewBoundary()>=0||zhuyinInput.keyCaret()>=0;
        if(mainHandler!=null&&!focused&&!textCandidateRefreshRunning&&textKeyRender){deferTextCandidateRows();return;}
        cancelPendingTextCandidates();
        int wordScroll=boWordCandidateScroll.getScrollX(),charScroll=boCandidateScroll.getScrollX();
        List<View> words=new ArrayList<>(),characters=new ArrayList<>();
        // Z2: sentence suggestions stay off the word row until the Z3 preview design.
        int wi=0,ci=0;
        List<ZhuyinInputController.TextChoice> textChoices=new ArrayList<>(externalConnection!=null?externalTextChoices:zhuyinInput.textChoices());
        android.view.inputmethod.EditorInfo editor=getCurrentInputEditorInfo();
        String localContext="";
        if(!protectedInputField&&editor!=null&&getSharedPreferences("simon_ime_prefs",MODE_PRIVATE).getBoolean("local_screen_context",false))
            localContext=LocalConversationContext.text(editor.packageName,SystemClock.elapsedRealtime());
        else LocalConversationContext.clear();
        final String hint=localContext;
        // Stable reorder only: no candidates introduced, no selected text changed, no network payload.
        if(!protectedInputField&&zhuyinAssociationHistory!=null&&!focused){
            List<ZhuyinInputController.TextChoice> wordChoices=new ArrayList<>();
            for(ZhuyinInputController.TextChoice choice:textChoices)if("word".equals(choice.kind))wordChoices.add(choice);
            String before=rankingField==fieldGeneration?rankingBefore:"",after=rankingField==fieldGeneration?rankingAfter:"";
            List<ZhuyinInputController.TextChoice> ranked=zhuyinAssociationHistory.rankCandidates(wordChoices,c->c.label,
                    "",before,after,hint);
            // Reuse original transactions; character order and selected native targets stay untouched.
            textChoices.removeIf(c->"word".equals(c.kind));textChoices.addAll(0,ranked);
        }else if(!protectedInputField&&!focused&&!hint.isEmpty()){
            List<ZhuyinInputController.TextChoice> wordChoices=new ArrayList<>();
            for(ZhuyinInputController.TextChoice choice:textChoices)if("word".equals(choice.kind))wordChoices.add(choice);
            wordChoices.sort(java.util.Comparator.comparing(c->!hint.contains(c.label)));
            textChoices.removeIf(c->"word".equals(c.kind));textChoices.addAll(0,wordChoices);
        }
        for(ZhuyinInputController.TextChoice choice:textChoices){
            if("char".equals(choice.kind))characters.add(pooledTextChoice(textCharViewPool,ci++,choice));
            else words.add(pooledTextChoice(textWordViewPool,wi++,choice));
        }
        if(externalConnection!=null&&textChoices.isEmpty()){
            TextView status=new TextView(this);status.setText("此詞暫無本機候選");status.setEnabled(false);status.setTextColor(boStreamPreview.getCurrentTextColor());status.setGravity(Gravity.CENTER);status.setPadding(dp(12),0,dp(12),0);words.add(status);
        }
        // Use the same current-generation AI options and manual transaction as the phonetic layout.
        List<JSONObject> options=sentencePhone==null||externalConnection!=null?Collections.emptyList():sentencePhone.rowOptions();
        if(sentencePhone!=null)sentencePhone.beginDisplay();
        List<String> labels=new ArrayList<>(),groups=new ArrayList<>(),suggestions=new ArrayList<>();
        for(View view:words){labels.add(((TextView)view).getText().toString());groups.add("word");}
        for(JSONObject option:options)suggestions.add(sentencePhone.optionText(option));
        List<View> orderedWords=new ArrayList<>();java.util.Map<View,JSONObject> displayedOptions=new java.util.IdentityHashMap<>();
        if(options.isEmpty())orderedWords.addAll(words);
        else for(int index:AiSentence.rowOrder(zhuyinInput.previewText(),focused,labels,groups,suggestions,Integer.MAX_VALUE)){
            if(index>=0)orderedWords.add(words.get(index));
            else {JSONObject option=options.get(-1-index);TextView item=sentenceOption(option);orderedWords.add(item);displayedOptions.put(item,option);}
        }
        reconcileTextRow(boWordCandidateItems,orderedWords);reconcileTextRow(boCandidateItems,characters);
        if(sentencePhone!=null)for(java.util.Map.Entry<View,JSONObject> entry:displayedOptions.entrySet())sentencePhone.displayed(entry.getValue(),boWordCandidateItems.indexOfChild(entry.getKey())+1);
        boWordCandidateScroll.scrollTo(wordScroll,0);boCandidateScroll.scrollTo(charScroll,0);updateCandidateRightHint();
    }
    private void renderSentenceOptions(){
        if(textLayoutSelected()){renderTextCandidateRows();return;}
        if(boCandidateItems==null||zhuyinInput==null)return;
        if(zhuyinInput.showIdleShortcuts()){renderZhuyinShortcuts();return;}
        // Assemble from the live focus on EVERY render, including delayed AI delivery.
        boolean focused=zhuyinInput.wordFocused()||zhuyinInput.keyCaret()>=0||zhuyinInput.previewBoundary()>=0;
        List<String> labels=new ArrayList<>(),groups=new ArrayList<>(),suggestions=new ArrayList<>();
        for(int i=0;i<renderedZhuyinCandidateCount;i++){labels.add(renderedZhuyinCandidates.get(i));groups.add(zhuyinInput.candidateGroup(i));}
        List<JSONObject> options=sentencePhone==null?Collections.emptyList():sentencePhone.rowOptions();
        for(JSONObject option:options)suggestions.add(sentencePhone.optionText(option));
        ZhuyinInputController.State current=zhuyinInput.state();
        int limit=focused?Math.max(1,current.targetEnd-current.targetStart):zhuyinInput.rowWordLimit();
        List<Integer> order=AiSentence.rowOrder(zhuyinInput.previewText(),focused,labels,groups,suggestions,limit);
        int scrollX=boCandidateScroll==null?0:boCandidateScroll.getScrollX(),undoAt=focused?-1:0;
        boCandidateItems.removeAllViews();
        if(sentencePhone!=null)sentencePhone.beginDisplay();
        java.util.Map<View,JSONObject> displayedOptions=new java.util.IdentityHashMap<>();
        for(int index:order){
            int rank=boCandidateItems.getChildCount()+1;
            if(index>=0){View item=rowEngineViews.get(index);if(item!=null)boCandidateItems.addView(item);}
            else {
                JSONObject option=options.get(-1-index);TextView item=sentenceOption(option);displayedOptions.put(item,option);
                boCandidateItems.addView(item,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(48)));undoAt=boCandidateItems.getChildCount();
            }
        }
        if(sentencePhone!=null&&sentencePhone.canUndo()){
            if(undoAt<0){undoAt=boCandidateItems.getChildCount();for(int slot=0;slot<order.size();slot++){int index=order.get(slot);if(index>=0&&"char".equals(groups.get(index))){undoAt=slot;break;}}}
            boCandidateItems.addView(sentenceUndo(),undoAt,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(48)));
        }
        if(sentencePhone!=null)for(java.util.Map.Entry<View,JSONObject> entry:displayedOptions.entrySet())sentencePhone.displayed(entry.getValue(),boCandidateItems.indexOfChild(entry.getKey())+1);
        if(boCandidateScroll!=null)boCandidateScroll.scrollTo(scrollX,0);
        updateCandidateRightHint();
    }

    private static android.text.SpannableString highlightZhuyinTarget(String value,int selectionStart,int selectionEnd){
        return new android.text.SpannableString(value);
    }

    private void moveEditorCursorWithinComposition(InputConnection ic, String composing, int cursor) {
        if (cursor < 0 || cursor > composing.length()) return;
        try {
            android.view.inputmethod.ExtractedText extracted = ic.getExtractedText(
                    new android.view.inputmethod.ExtractedTextRequest(), 0);
            if (extracted == null || extracted.selectionStart < composing.length()) return;
            int start = extracted.selectionStart - composing.length();
            int target = start + cursor;
            ic.setSelection(target, target);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not place cursor inside Zhuyin composition", error);
        }
    }

    private void renderZhuyinShortcuts() {
        if(textLayoutSelected()&&boWordCandidateItems!=null)boWordCandidateItems.removeAllViews();
        if(boCandidateItems==null || ("shortcuts".equals(renderedZhuyinCandidateKind)&&boCandidateItems.getChildCount()==2))return;
        boCandidateItems.removeAllViews();rowEngineViews.clear();
        renderedZhuyinCandidates=java.util.Collections.emptyList();renderedZhuyinCandidateCount=0;
        renderedZhuyinCandidateKind="shortcuts";
        String[] labels={"📋 剪貼簿","⚡ 常用詞"};Panel[] panels={Panel.CLIPBOARD,Panel.COMMANDS};
        for(int i=0;i<labels.length;i++){
            TextView chip=new TextView(this);chip.setText(labels[i]);chip.setContentDescription(labels[i]);
            chip.setGravity(Gravity.CENTER);chip.setTextSize(15f);chip.setTextColor(getColor(R.color.key_text));
            chip.setBackgroundResource(R.color.key_bg);chip.setPadding(dp(12),0,dp(12),0);
            final Panel panel=panels[i];chip.setOnClickListener(v->togglePanel(panel));
            boCandidateItems.addView(chip,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(48)));
        }
        if(boCandidateScroll!=null)boCandidateScroll.scrollTo(0,0);
    }

    private void renderZhuyinCandidates(List<String> candidates, String kind) {
        if (boCandidateItems == null || candidates == null || (candidates.equals(renderedZhuyinCandidates) && kind.equals(renderedZhuyinCandidateKind))) return;
        boCandidateItems.removeAllViews();
        rowEngineViews.clear();
        renderedZhuyinCandidateCount = 0;
        renderedZhuyinCandidates = new ArrayList<>(candidates);
        renderedZhuyinCandidateKind = kind;
        recordCandidateEvent("bopomofo",renderedZhuyinCandidates,-1);
        appendZhuyinCandidateBatch();
        if (boCandidateScroll != null) boCandidateScroll.scrollTo(0, 0);
    }

    private void appendZhuyinCandidateBatch() {
        if (boCandidateItems == null || renderedZhuyinCandidateCount >= renderedZhuyinCandidates.size()) return;
        int end = Math.min(renderedZhuyinCandidates.size(), renderedZhuyinCandidateCount + 100);
        for (int i = renderedZhuyinCandidateCount; i < end; i++) {
            final int candidateIndex = i;
            TextView candidate = new TextView(this);
            candidate.setGravity(Gravity.CENTER);
            candidate.setTextSize(15f);
            candidate.setTextColor(getColor(R.color.key_text));
            candidate.setBackgroundResource(R.color.key_bg);
            candidate.setSingleLine(true);
            candidate.setEllipsize(android.text.TextUtils.TruncateAt.END);
            candidate.setPadding(dp(12), 0, dp(12), 0);
            String value = renderedZhuyinCandidates.get(i);
            String origin=zhuyinInput==null?"engine":zhuyinInput.candidateOrigin(i);
            candidate.setText(value);
            candidate.setContentDescription(renderedZhuyinCandidateKind+" "+origin+" "+value);
            String kind = renderedZhuyinCandidateKind;
            candidate.setOnClickListener(v -> {
                if(sentencePhone!=null)sentencePhone.userSelected();
                boolean cursorChoice=zhuyinInput.wordFocused()||zhuyinInput.keyCaret()>=0;
                String beforeKeys=cursorChoice?zhuyinInput.sentenceKeys():"",choiceOrigin=zhuyinInput.candidateOrigin(candidateIndex);
                List<String> beforeSyllables=cursorChoice?new ArrayList<>(zhuyinInput.phoneticSyllables()):java.util.Collections.emptyList();
                if(cursorChoice)recordCursorEvent(cursorInteractionKind,"choose",choiceOrigin,candidateIndex+1,0,0);
                zhuyinCommitVia="candidate";
                recordCandidateEvent("bopomofo",renderedZhuyinCandidates,candidateIndex);
                if(!"association".equals(kind)&&candidateIndex>0&&candidateIndex<renderedZhuyinCandidates.size())
                    recordCorrectionEvent("bopomofo","all",renderedZhuyinCandidates.get(0),renderedZhuyinCandidates.get(candidateIndex),"candidate");
                sentenceInstalledCommit=zhuyinWordIndex!=null&&zhuyinWordIndex.isInstalledWord(value)?value:null;
                applyZhuyinState("association".equals(kind)
                    ? zhuyinInput.chooseAssociation(candidateIndex) : zhuyinInput.chooseCandidate(candidateIndex));
                if(cursorChoice)recordCursorKeyDifferences(beforeKeys,zhuyinInput.sentenceKeys(),beforeSyllables,choiceOrigin);
            });
            rowEngineViews.put(i,candidate);
            boCandidateItems.addView(candidate,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.MATCH_PARENT));
        }
        renderedZhuyinCandidateCount = end;
        boCandidateItems.post(this::updateCandidateRightHint);
    }

    private void updateCandidateRightHint(){
        if(boCandidateRightHint!=null&&boCandidateScroll!=null)boCandidateRightHint.setVisibility(boCandidateScroll.canScrollHorizontally(1)?View.VISIBLE:View.GONE);
    }

    /** The native engine class lives only in the phone flavor; watch never resolves or packages it. */
    private ZhuyinInputController.Engine createZhuyinEngine() {
        try {
            Class<?> type = Class.forName("com.simon.voiceime.RimeZhuyinEngine");
            return (ZhuyinInputController.Engine) type
                    .getDeclaredConstructor(android.content.Context.class).newInstance(this);
        } catch (Throwable error) {
            Log.e(TAG, "Traditional-page Rime unavailable; falling back to libchewing", error);
            try {
                Class<?> fallback = Class.forName("com.simon.voiceime.ChewingEngine");
                return (ZhuyinInputController.Engine) fallback
                        .getDeclaredConstructor(android.content.Context.class).newInstance(this);
            } catch (Throwable fallbackError) {
                Log.e(TAG, "Phone Zhuyin fallback could not be created", fallbackError);
                return null;
            }
        }
    }

    /** Leaving the Zhuyin page discards unfinished composition and clears the editor underline. */
    private void clearBopomofoBuffer() {
        if (touchShadow != null) touchShadow.invalidate();
        if (zhuyinInput != null) applyZhuyinState(zhuyinInput.clear());
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.finishComposingText();
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

    protected void updateStatus(String text) {
        silenceStatusGeneration++;
        if (text != null && text.startsWith("聆聽中")) {
            lastRecognitionStatusMs = android.os.SystemClock.elapsedRealtime();
        }
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
        if((isRecording||recordingFinalizing)&&gen==activeUtteranceGeneration) {
            afterRecordingFinalization.add(() -> httpFallbackFullAudio(gen));return;
        }
        if(recoverySessionByGeneration.containsKey(gen))return;
        // v6.25: 終局守衛——同一 generation 只允許一次 commit/fallback，擋 WS 晚到回呼造成的重複提交
        if(guardStoppedGenerations.contains(gen)||isDiscardedVoiceGeneration(gen)||deliveredVoiceGenerations.contains(gen)||serverFinalGenerations.contains(gen)
                ||acceptedTextLengths.containsKey(gen)||!fullAudioRequests.add(gen))return;
        armVoiceFinalDeadline(gen);
        reserveUtteranceGeneration(gen);
        byte[] pcm = getFullPcmForGeneration(gen);
        String id=pendingSessionByGeneration.get(gen);
        boolean durable=id!=null&&voicePendingQueue!=null&&!voicePendingQueue.backupFailed(id);
        if (durable && voicePendingQueue.audioMs(id) > 120_000) {
            queueRecordingInChunks(gen,id,"long_audio_pending"); return;
        }
        if (!durable&&(pcm==null||pcm.length==0)) {
            completeReservedUtteranceWithoutText(gen);
            mainHandler.post(() -> updateStatus("沒有可用的音訊，請再試一次"));return;
        }
        if(pcm==null)pcm=new byte[0];
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
        MultipartBody.Builder bodyBuilder = AppVersion.withAppVersion(new MultipartBody.Builder())
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav",
                        durableOrMemoryAudioBody(gen,wavData))
                .addFormDataPart("client_session_id",pendingSessionByGeneration.getOrDefault(gen,""));

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

                    String text=VoicePendingQueue.parseSuccessfulResponse(responseBody);
                    serverFullAudioGenerations.add(gen);
                    completeReservedUtteranceWithText(gen,text);
                } catch (Exception e) {
                    Log.e(TAG, "[AudioStream] HTTP fallback parse error", e);
                    runOfflineFullAudioFallback(gen, "HTTP fallback parse error: " + e.getMessage(), true, pcm);
                }
            }
        });
    }

    private RequestBody durableOrMemoryAudioBody(int gen,byte[] wavData){
        String id=pendingSessionByGeneration.get(gen);
        if(id!=null&&voicePendingQueue!=null&&!voicePendingQueue.backupFailed(id)){java.io.File full=voicePendingQueue.pcmFile(id);return pcmWavBody(full,SAMPLE_RATE);}
        return RequestBody.create(wavData,MediaType.parse("audio/wav"));
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

            if (!VoiceResultText.isSilence(text)&&!VoiceResultText.isHallucinationMarker(text)) {
                final String finalText = text;
                Log.i(TAG, "[OfflineFallback] SenseVoice success after " + reason
                        + " (" + sttMs + "ms), routing to server correction: '"
                        + truncate(finalText, 50) + "'");
                mainHandler.post(() -> updateStatus("伺服器校正中…"));
                sendTextProcess(finalText, Mode.APPEND, gen, true);
            } else {
                Log.e(TAG, "[OfflineFallback] SenseVoice returned empty after server failure: " + reason);
                markPendingGeneration(gen,"offline_empty_unconfirmed");
                completeReservedUtteranceWithoutText(gen);
                mainHandler.post(() -> updateStatus("離線辨識無結果，音訊保留待補傳"));
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
    private void sendAiCommand(String instruction,String context) {sendAiCommand(instruction,context,-1);}
    private void sendAiCommand(String instruction, String context,int audioGeneration) {
        if(consumeSilentResult(audioGeneration,VoiceResultText.isNonSpeech(instruction,false,-1)?"":instruction,false,"AI_COMMAND"))return;
        if (instruction == null || instruction.trim().isEmpty()) {
            updateStatus("未辨識到指令");
            return;
        }
        final int capturedGeneration = fieldGeneration;
        okhttp3.FormBody body = AppVersion.withAppVersion(new okhttp3.FormBody.Builder())
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
                keepPendingModeResult(audioGeneration,"AI_COMMAND","ai_instruction_http_failure");
                mainHandler.post(() -> updateStatus("AI 指令失敗，素材保留可重試"));
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                handleAiCommandResponse(response, capturedGeneration, audioGeneration);
            }
        });
    }

    /**
     * v6.20 R2: 音訊指令（本機 STT 未取得文字時）→ POST /v1/ai-command（file 由伺服器轉錄為指令）。
     */
    private void sendAiCommandAudio(byte[] wavData, String context,int gen) {
        if (wavData == null || wavData.length == 0) {
            updateStatus("沒有可用的音訊，素材保留可重試");
            return;
        }
        final int capturedGeneration = fieldGeneration;
        MultipartBody.Builder bodyBuilder = AppVersion.withAppVersion(new MultipartBody.Builder())
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav",
                        RequestBody.create(wavData, MediaType.parse("audio/wav")))
                .addFormDataPart("client_session_id",pendingSessionByGeneration.getOrDefault(gen,""))
                .addFormDataPart("context", context != null ? context : "")
                .addFormDataPart("language", "zh-TW");
        LineContextAccessibilityService.Snapshot lineSnapshot=lineRequests.get(gen);
        if(lineSnapshot!=null) {
            bodyBuilder.addFormDataPart("mode","ghostwriter");
            bodyBuilder.addFormDataPart("chat_history",lineSnapshot.history.toString());
        }
        MultipartBody body=bodyBuilder.build();
        Request.Builder rb = new Request.Builder()
                .url(getServerUrl() + "/v1/ai-command")
                .post(body);
        String auth = getAuthPassword();
        if (auth != null && !auth.isEmpty()) rb.addHeader("Authorization", "Bearer " + auth);
        httpClient.newCall(rb.build()).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                keepPendingModeResult(gen,"AI_COMMAND","ai_command_audio_http_failure");
                mainHandler.post(() -> updateStatus("AI 指令失敗，素材保留可重試"));
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                handleAiCommandResponse(response, capturedGeneration, gen);
            }
        });
    }

    /** v6.20 R2: 共用 /v1/ai-command 回應處理（marshal 回主緒後 insert）。 */
    private void handleAiCommandResponse(Response response, int capturedGeneration) { handleAiCommandResponse(response,capturedGeneration,-1); }
    private void handleAiCommandResponse(Response response, int capturedGeneration, int audioGeneration) {
        String bodyStr;
        boolean ok;
        int code;
        try (Response r = response) {
            code = r.code();
            ok = r.isSuccessful();
            bodyStr = r.body() != null ? r.body().string() : "";
        } catch (Exception e) {
            keepPendingModeResult(audioGeneration,"AI_COMMAND","ai_command_response_failure");
            mainHandler.post(() -> updateStatus("AI 指令失敗，素材保留可重試"));
            return;
        }
        final boolean fok = ok;
        final int fcode = code;
        final String fbody = bodyStr;
        LineContextAccessibilityService.Snapshot expectedLine=lineRequests.get(audioGeneration);
        LineContextAccessibilityService.Snapshot freshLine=lineGenerations.contains(audioGeneration)?LineContextAccessibilityService.capture():null;
        final boolean sameLine=expectedLine!=null&&freshLine!=null
            &&expectedLine.history.toString().equals(freshLine.history.toString());
        mainHandler.post(() -> {
            if(isDiscardedVoiceGeneration(audioGeneration))return;
            if(lineGenerations.contains(audioGeneration)) {
                LineContextAccessibilityService.Snapshot snapshot=lineRequests.get(audioGeneration);
                if(snapshot==null||!snapshot.current()||!sameLine||!lineDrafts.containsKey(audioGeneration)
                        ||!lineDrafts.get(audioGeneration).equals(lineDraft())||!lineGhostwriterField()||audioGeneration!=activeUtteranceGeneration
                        ||capturedGeneration!=fieldGeneration) {updateStatus("LINE 對話已變動，回覆已丟棄，請重錄");return;}
            } else if(lineGhostwriterField()) {updateStatus("嘴替回覆已失效，請重錄");return;}
            if (!fok) {
                keepPendingModeResult(audioGeneration,"AI_COMMAND","ai_command_http_status:"+fcode);
                updateStatus("AI 伺服器錯誤 " + fcode + "，素材保留");
                return; // keep armed
            }
            if(lineGenerations.contains(audioGeneration)) {
                String ghostwriterStatus="";
                try {ghostwriterStatus=new JSONObject(fbody).optString("status","");} catch(Exception malformed) { }
                if(!"ok".equals(ghostwriterStatus)) {
                    keepPendingModeResult(audioGeneration,"GHOSTWRITER","ghostwriter_service_not_ready");
                    updateStatus("嘴替服務未準備或生成失敗，音訊保留，未填入口述顧慮");
                    return;
                }
            }
            final String text;
            try {text=VoicePendingQueue.parseSuccessfulResponse(fbody);}
            catch(Exception e) {keepPendingModeResult(audioGeneration,"AI_COMMAND","ai_command_malformed_response");updateStatus("AI 回應錯誤，音訊保留");return;}
            if(consumeSilentResult(audioGeneration,text,false,"AI_COMMAND"))return;
            serverFinalGenerations.add(audioGeneration);
            if (capturedGeneration != fieldGeneration) {
                Log.w(TAG, "AI response discarded: field switched");
                keepPendingModeResult(audioGeneration,"AI_COMMAND","ai_command_field_changed");
                return;
            }
            deliverVoiceResult(audioGeneration,text,false,false,() -> {
                if(lineGenerations.contains(audioGeneration)) {
                    LineContextAccessibilityService.Snapshot snapshot=lineRequests.get(audioGeneration);
                    if(snapshot==null||!snapshot.current()||freshLine==null||!freshLine.contentCurrent()||capturedGeneration!=fieldGeneration
                            ||audioGeneration!=activeUtteranceGeneration||!lineGhostwriterField()
                            ||!lineDrafts.containsKey(audioGeneration)||!lineDrafts.get(audioGeneration).equals(lineDraft())) {
                        updateStatus("LINE 對話或草稿已變動，回覆未填入，請重錄");return;
                    }
                }
                commitFinalText(text);
                updateStatus("🤖 " + truncate(text,20));
                if(lastCommitInsertedOrCopied)clearAiState();
            });
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
                .url(AppVersion.withAppVersion(getServerUrl() + "/"))
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
        return AuthConfig.password(prefs);
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    protected static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
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
        if(retainedTextNeedsReclaim&&reclaimTextComposition(getCurrentInputEditorInfo()))applyZhuyinState(zhuyinInput.state());
        if(textLayoutSelected()&&!protectedInputField&&zhuyinInput!=null&&zhuyinComposingConnection!=null&&zhuyinComposingConnection==getCurrentInputConnection()){
            String value=zhuyinInput.textPreview();
            if(newSelStart==newSelEnd&&candidatesStart>=0&&candidatesEnd-candidatesStart==value.length()){
                if(newSelEnd==candidatesEnd){
                    retainedTextEnd=candidatesEnd;retainedTextField=textFieldIdentity(getCurrentInputEditorInfo());
                    if(!value.equals(retainedText))retainedTextBefore=value;retainedText=value;
                }else if(newSelStart>=0&&retainedTextEnd==candidatesEnd
                        &&retainedText.equals(value)&&retainedTextField.equals(textFieldIdentity(getCurrentInputEditorInfo()))){
                    // A native editor caret move releases this preedit; preview-row editing keeps the editor caret at its end.
                    try {getCurrentInputConnection().finishComposingText();}
                    catch(RuntimeException error){Log.w(TAG,"Text composition finalization unavailable: "+error.getClass().getSimpleName());}
                    discardRetainedTextComposition();
                }
            }
        }
        if(sentencePhone!=null)sentencePhone.selection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd);
        updateExternalSelection(newSelStart,newSelEnd);
        if(textLayoutSelected()&&zhuyinInput!=null&&zhuyinInput.previewText().isEmpty())renderTextCandidateRows();
        deferRankingContext();
        if (touchShadow != null && newSelStart < oldSelStart) touchShadow.invalidate();
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
        if (isWatchService()) return;
        drainPendingVoiceQueue();
        configureTextRows();
        if(protectedInputField&&zhuyinInput!=null&&!zhuyinInput.textPreview().isEmpty()
                &&zhuyinComposingConnection!=getCurrentInputConnection())discardRetainedTextComposition();
        if(textLayoutSelected()&&zhuyinInput!=null&&!protectedInputField&&!zhuyinInput.previewText().isEmpty()) {
            if(reclaimTextComposition(info))applyZhuyinState(zhuyinInput.state());
            else {
                // An earlier selection callback may already have cleared the reclaim flag.
                // Failed range validation on reopen still releases the old controller snapshot.
                // Finalize only the editor's existing region; never replay old text at a moved caret.
                InputConnection ic=getCurrentInputConnection();
                try {if(ic!=null)ic.finishComposingText();}
                catch(RuntimeException error){Log.w(TAG,"Text composition finalization unavailable: "+error.getClass().getSimpleName());}
                // A disconnected or rejecting editor cannot retain controller ownership.
                // Leave its existing text untouched and let fresh input use the current caret.
                discardRetainedTextComposition();
            }
        }
        warmUpConnection();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(connectionWarmUpRunnable);
            mainHandler.postDelayed(connectionWarmUpRunnable, CONNECTION_WARM_UP_INTERVAL_MS);
        }
    }

    private void flushPendingVoiceAudio(boolean bounded) {
        if(voicePendingQueue==null)return;
        try {
            if(bounded) {
                if(!voicePendingQueue.flushAllBounded())Log.w(TAG,"Pending PCM flush still queued after 100 ms");
            } else voicePendingQueue.flushAll();
        }
        catch(IOException e) {Log.e(TAG,"Pending PCM lifecycle fsync failed",e);}
    }

    @Override public void onLowMemory() {
        flushPendingVoiceAudio(true);
        super.onLowMemory();
    }

    @Override public void onTrimMemory(int level) {
        flushPendingVoiceAudio(false);
        super.onTrimMemory(level);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        selectionRevision++;clearExternalSelection();
        invalidateLineGhostwriter();
        flushPendingVoiceAudio(false);
        dismissSymbolPopup();
        if(textLayoutSelected()&&zhuyinInput!=null)rememberTextComposition(getCurrentInputConnection(),zhuyinInput.textPreview());
        if(textLayoutSelected()&&!protectedInputField&&zhuyinInput!=null&&!zhuyinInput.textPreview().isEmpty())retainedTextNeedsReclaim=true;
        clearRankingContext();
        cancelPendingTextCandidates();lastTextKeyUptime=-1;
        if (!isWatchService() && zhuyinInput != null && !textLayoutSelected()) clearBopomofoBuffer();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(connectionWarmUpRunnable);
            clearServerWaitBudgetCallbacks();
        }
        settlePendingCorrectionCapture();
        // v6.1: 鍵盤收起 → 釋放螢幕常亮，避免非錄音時殘留 keepScreenOn 拖電
        if (rootView != null) rootView.setKeepScreenOn(false);
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onDestroy() {
        invalidateLineGhostwriter(); fieldGeneration++;
        flushPendingVoiceAudio(true);
        dismissSymbolPopup();
        clearRankingContext();
        cancelPendingTextCandidates();lastTextKeyUptime=-1;
        if(layoutDiagnostics!=null){layoutDiagnostics.close();layoutDiagnostics=null;}
        if (sentencePhone != null) { sentencePhone.close(); sentencePhone = null; }
        if (zhuyinInput != null) { zhuyinInput.close(); zhuyinInput = null; }
        if (zhuyinWordIndex != null) { zhuyinWordIndex.close(); zhuyinWordIndex = null; }
        if (touchLearning != null) { touchLearning.close(); touchLearning = null; }
        if (isRecording) {
            String pendingId=activePendingSessionId;
            if(pendingId!=null&&voicePendingQueue!=null){voicePendingQueue.execute(() -> voicePendingQueue.markPending(pendingId,"service_destroyed"));recordVoiceEvent("pending_saved",pendingId,voicePendingQueue.audioMs(pendingId),0,0,"","",voicePendingQueue.totalBytes(),0);}
            isRecording = false;
            streamingMode = false;
            synchronized (recorderRestartLock) {
                try {
                    audioRecord.stop();
                    audioRecord.release();
                } catch (Exception e) {
                    Log.w(TAG, "Recorder release during destroy failed", e);
                }
            }
        }
        try{ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);if(cm!=null&&voiceNetworkCallback!=null)cm.unregisterNetworkCallback(voiceNetworkCallback);}catch(Exception ignored){}
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
        for(Integer gen:new java.util.ArrayList<>(pendingSessionByGeneration.keySet()))
            recoverUnfinishedVoiceGeneration(gen,"ime_destroy");
        for(Runnable callback:voiceFinalDeadlines.values())mainHandler.removeCallbacks(callback);
        voiceFinalDeadlines.clear();
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
        pendingVoiceExecutor.shutdown();
        synchronized(this){if(pendingDrainTask!=null)pendingDrainTask.cancel(false);}
        super.onDestroy();
    }
}
