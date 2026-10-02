package com.simon.voiceime;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Private test-build diagnostics spool. Never called synchronously from an input event. */
final class ImeTelemetry {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final int BATCH_SIZE = 200;
    static final int MAX_CRASH_STACK_CHARS = 8192;
    private static volatile ImeTelemetry instance;
    static synchronized ImeTelemetry install(Context context) {
        if (instance == null) instance = new ImeTelemetry(context.getApplicationContext());
        return instance;
    }
    static ImeTelemetry get() { return instance; }

    interface Transport { int post(String url, String bearer, String body) throws Exception; }
    private final Context context;
    private final TelemetrySpool spool;
    private final String appVersion;
    private final String session = UUID.randomUUID().toString();
    private final HandlerThread thread = new HandlerThread("IME-Diagnostics");
    private Handler handler;
    private final Transport transport;
    private volatile String lastResult = "尚未上傳";
    private volatile long lastUploadMs;
    private volatile long lastInputElapsed = android.os.SystemClock.elapsedRealtime();
    private long lastAttemptElapsed = android.os.SystemClock.elapsedRealtime();

    ImeTelemetry(Context context) {
        this(context, new File(context.getFilesDir(), "ime-diagnostics.jsonl"), version(context), ImeTelemetry::httpPost);
    }
    ImeTelemetry(Context context, File spool, String version, Transport transport) {
        this.context=context; this.spool=new TelemetrySpool(spool,MAX_BYTES); this.appVersion=version; this.transport=transport;
        thread.start(); handler=new Handler(thread.getLooper());
        handler.postDelayed(new Runnable(){public void run(){flush(false);if(handler!=null)handler.postDelayed(this,15_000);}},15_000);
        installCrashHandlers(); collectPreviousExits();
    }
    private static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName; }
        catch(Exception e) { return "unknown"; }
    }
    void noteInput(){lastInputElapsed=android.os.SystemClock.elapsedRealtime();}
    private String punctuationDestination;
    void punctuationDestination(String into){punctuationDestination=into;}
    void record(String type,String page,JSONObject fields,boolean protectedField) {
        if("key".equals(type)||"key_outcome".equals(type)||"candidate".equals(type)||"commit".equals(type)||"correction".equals(type)||"key_outcome".equals(type))noteInput();
        if(!context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE).getBoolean("ime_auto_upload",true))return;
        try {
            JSONObject event=makeEvent(System.currentTimeMillis(),session,appVersion,type,page,fields,protectedField);
            Handler target=handler;if(target!=null)target.post(()->enqueue(event));
        }catch(Exception e){Log.w("ImeTelemetry","event dropped",e);}
    }
    private void recordUrgent(String type,String page,JSONObject fields){
        try{JSONObject event=makeEvent(System.currentTimeMillis(),session,appVersion,type,page,fields,false);enqueue(event);}catch(Exception e){Log.w("ImeTelemetry","urgent event could not be stored",e);}
    }
    static JSONObject makeEvent(long ts,String session,String version,String type,String page,JSONObject fields,boolean protectedField)throws Exception {
        if(protectedField && ("key".equals(type)||"key_outcome".equals(type)||"candidate".equals(type)||"commit".equals(type)||"correction".equals(type))) {
            fields=new JSONObject().put("step","protected_field_skipped").put("ms",0).put("ok",true).put("message","protected field; content omitted");
            type="protected_field_skipped";page="bopomofo";
        }
        JSONObject event=new JSONObject().put("ts",ts).put("type",type).put("session_id",session).put("page",page).put("app_version",version);
        if(fields!=null)for(java.util.Iterator<String> i=fields.keys();i.hasNext();){String k=i.next();event.put(k,fields.get(k));}
        return event;
    }
    static JSONObject makeCrashEvent(String threadName, Throwable failure, int maxStackChars) throws Exception {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        String stack = writer.toString();
        if (stack.length() > maxStackChars) stack = stack.substring(0, maxStackChars);
        return new JSONObject().put("type", "crash")
                .put("where", threadName == null ? "unknown" : threadName)
                .put("exception_class", failure.getClass().getName())
                .put("message", redact(failure.getMessage() == null ? "" : failure.getMessage()))
                .put("stack", redact(stack));
    }
    private void enqueue(JSONObject event){spool.add(event);}
    void key(String page,String key,float x,float y,float cx,float cy,boolean protectedField) {
        try{record("key",page,new JSONObject().put("key",key).put("x",x).put("y",y).put("key_center_x",cx).put("key_center_y",cy),protectedField);}catch(Exception ignored){}
    }
    static JSONObject makeBopomofoKeyEvent(long ts, String session, String version, String key,
                                            float x, float y, float cx, float cy,
                                            long keyToCandidateMs) throws Exception {
        JSONObject fields = new JSONObject().put("key", key).put("x", x).put("y", y)
                .put("key_center_x", cx).put("key_center_y", cy)
                .put("key_to_candidate_ms", Math.max(0L, keyToCandidateMs));
        return makeEvent(ts, session, version, "key", "bopomofo", fields, false);
    }
    void bopomofoKey(String key, float x, float y, float cx, float cy,
                     long keyToCandidateMs, JSONObject shadow, boolean protectedField) {
        try {
            JSONObject fields = new JSONObject().put("key", key).put("x", x).put("y", y)
                    .put("key_center_x", cx).put("key_center_y", cy)
                    .put("key_to_candidate_ms", Math.max(0L, keyToCandidateMs));
            if(punctuationDestination!=null){fields.put("into",punctuationDestination);punctuationDestination=null;}
            if (shadow != null) for (Iterator<String> i=shadow.keys();i.hasNext();) { String k=i.next();fields.put(k,shadow.get(k)); }
            record("key", "bopomofo", fields, protectedField);
        } catch (Exception ignored) {}
    }
    /**
     * Records the app-local part of a Bopomofo key press.  This is deliberately
     * an event field rather than a new endpoint: phone telemetry can therefore
     * measure the A10 key-to-candidate budget without exposing input text.
     */
    void keyOutcome(String page, String key, long keyToCandidateMs, boolean protectedField) {
        try {
            JSONObject fields=new JSONObject().put("key",key).put("key_to_candidate_ms",Math.max(0L,keyToCandidateMs));
            if(punctuationDestination!=null){fields.put("into",punctuationDestination);punctuationDestination=null;}
            record("key_outcome",page,fields,protectedField);
        } catch (Exception ignored) {}
    }
    void init(String step,long ms,boolean ok,String message) {
        try{JSONObject j=new JSONObject().put("step",step).put("ms",ms).put("ok",ok);if(message!=null&&!message.isEmpty())j.put("message",message);record("rime_init","bopomofo",j,false);}catch(Exception ignored){}
    }
    int size(){return spool.size();}
    private boolean onWifi() {
        try { android.net.ConnectivityManager manager=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities caps=manager==null?null:manager.getNetworkCapabilities(manager.getActiveNetwork());
            return caps!=null&&caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        }catch(Exception unavailable){return false;}
    }
    private void flush(boolean manual) {
        SharedPreferences settings=context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE);
        if(!manual&&!settings.getBoolean("ime_auto_upload",true))return;
        String mode=settings.getString("ime_upload_frequency","idle");
        long now=android.os.SystemClock.elapsedRealtime();
        if(!TelemetryFlushPolicy.eligible(mode,now,lastAttemptElapsed,lastInputElapsed,onWifi(),manual)) {
            if(manual&&"wifi".equals(mode)){lastResult="等待 Wi-Fi 連線";settings.edit().putString("ime_last_upload_result",lastResult).apply();}
            return;
        }
        List<JSONObject> batch=spool.batch(BATCH_SIZE);if(batch.isEmpty())return;
        lastAttemptElapsed=now;
        try {
            SharedPreferences p=context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE);
            String base=p.getString("server_url","http://100.84.86.128:8001"); URI uri=URI.create(base);
            String host=uri.getHost();if(host==null||host.isEmpty()){lastResult="伺服器位址無效";return;}
            String url=new URI(uri.getScheme(),null,host,8094,"/v1/ime/log",null,null).toString();
            String password=AuthConfig.password(p);
            String batchId=stableBatchId(batch);JSONArray events=new JSONArray();for(JSONObject e:batch)events.put(e);
            JSONObject body=new JSONObject().put("device",deviceId()).put("app_version",appVersion).put("batch_id",batchId).put("events",events);
            int status=transport.post(url,AuthConfig.authorizationHeader(password),body.toString());
            if(status<200||status>=300)throw new IOException("HTTP "+status);
            if(!spool.acknowledge(batchId,batch.size()))throw new IOException("local batch changed before acknowledgement");
            lastUploadMs=System.currentTimeMillis();lastResult="成功（"+batch.size()+" 筆）";
            p.edit().putLong("ime_last_upload_ms",lastUploadMs).putString("ime_last_upload_result",lastResult).apply();
        }catch(Exception e){lastResult="待下次重試（"+e.getClass().getSimpleName()+"）";context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE).edit().putString("ime_last_upload_result",lastResult).apply();}
    }
    private static int httpPost(String url,String bearer,String body)throws Exception{
        OkHttpClient c=new OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(5,TimeUnit.SECONDS).writeTimeout(5,TimeUnit.SECONDS).build();
        try(Response r=c.newCall(makeRequest(url,bearer,body)).execute()){return r.code();}
    }
    static Request makeRequest(String url,String bearer,String body){
        Request.Builder b=new Request.Builder().url(url).post(RequestBody.create(body,MediaType.parse("application/json; charset=utf-8")));
        if(bearer!=null&&!bearer.isEmpty())b.header("Authorization",bearer.startsWith("Bearer ")?bearer:AuthConfig.authorizationHeader(bearer));
        return b.build();
    }
    private String deviceId(){SharedPreferences p=context.getSharedPreferences("ime_telemetry",Context.MODE_PRIVATE);String id=p.getString("id",null);if(id==null){id=UUID.randomUUID().toString();p.edit().putString("id",id).apply();}return id;}
    static String stableBatchId(List<JSONObject> events)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");for(JSONObject e:events)d.update(e.toString().getBytes(StandardCharsets.UTF_8));byte[] b=d.digest();StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
    String lastResult(){return lastResult;} long lastUploadMs(){return lastUploadMs;}
    void uploadNow(){if(handler!=null)handler.post(()->flush(true));}
    private void installCrashHandlers(){Thread.UncaughtExceptionHandler prior=Thread.getDefaultUncaughtExceptionHandler();Thread.setDefaultUncaughtExceptionHandler((t,e)->{
        try{JSONObject crash=makeCrashEvent(t.getName(),e,MAX_CRASH_STACK_CHARS);crash.remove("type");recordUrgent("crash","voice",crash);}catch(Exception ignored){}
        if(prior!=null)prior.uncaughtException(t,e);
    });}
    private static String redact(String s){return s.replaceAll("(?i)(Bearer\\s+)[^\\s]+","$1[redacted]");}
    private static String exitReasonName(int reason) {
        switch(reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "PACKAGE_UPDATED";
            default: return "OTHER";
        }
    }
    private void collectPreviousExits(){if(Build.VERSION.SDK_INT<30)return;try{
        SharedPreferences p=context.getSharedPreferences("ime_telemetry",Context.MODE_PRIVATE);long sent=p.getLong("exit_timestamp",0);ActivityManager am=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ApplicationExitInfo> rows=am.getHistoricalProcessExitReasons(context.getPackageName(),0,10);long newest=sent;
        if(rows!=null)for(ApplicationExitInfo x:rows){long ts=x.getTimestamp();if(ts<=sent)continue;int reason=x.getReason();
            if(reason==ApplicationExitInfo.REASON_CRASH||reason==ApplicationExitInfo.REASON_CRASH_NATIVE||reason==ApplicationExitInfo.REASON_ANR){
                String kind=reason==ApplicationExitInfo.REASON_ANR?"anr":reason==ApplicationExitInfo.REASON_CRASH_NATIVE?"native":"java";
                String trace="";try(InputStream in=x.getTraceInputStream()){if(in!=null){byte[] b=new byte[4096];int n=in.read(b);if(n>0)trace=redact(new String(b,0,n,StandardCharsets.UTF_8).substring(0,Math.min(n,2048)));}}catch(Exception ignored){}
                recordUrgent("crash","voice",new JSONObject().put("kind",kind).put("reason",x.getDescription()==null?"exit-"+reason:x.getDescription()).put("trace",trace));
            } else {
                recordUrgent("exit","voice",new JSONObject().put("reason_code",reason).put("reason_name",exitReasonName(reason))
                        .put("status",x.getStatus()).put("importance",x.getImportance()).put("pss_kb",x.getPss()).put("rss_kb",x.getRss())
                        .put("description",x.getDescription()==null?"":x.getDescription()).put("exit_ts",ts));
            }
            newest=Math.max(newest,ts);}
        if(newest>sent)p.edit().putLong("exit_timestamp",newest).apply();
    }catch(Exception e){Log.w("ImeTelemetry","exit history unavailable",e);}}
    void close(){if(handler!=null)handler.removeCallbacksAndMessages(null);thread.quitSafely();}
}
