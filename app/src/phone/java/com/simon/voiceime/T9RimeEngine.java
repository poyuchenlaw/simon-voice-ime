package com.simon.voiceime;

import android.content.Context;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private app-data librime session. All native text crosses JNI as UTF-8 byte arrays. */
final class T9RimeEngine implements T9Engine {
    private static final String TAG = "T9RimeEngine";
    static { System.loadLibrary("rime"); System.loadLibrary("rime_jni"); }
    private volatile long handle;
    private volatile String sharedPath = "";
    private volatile String userPath = "";
    private volatile boolean initialized = false;
    private volatile boolean closed = false;
    private volatile String initStep = "啟動";
    private volatile String initError = "";
    private final long initStartedAt = System.currentTimeMillis();
    private final StringBuilder keys = new StringBuilder();
    private final StringBuilder currentFullKeys = new StringBuilder();
    private volatile boolean twoPress = true;
    private boolean fullSegmentPlaced = false;
    private final T9CompositionModel composition = new T9CompositionModel();
    T9RimeEngine(Context context) {
        Context app = context.getApplicationContext();
        new Thread(() -> initialize(app), "T9-Rime-Init").start();
    }
    private void initialize(Context context) {
        String activeStep = "計算資料版本";
        try {
            File files = context.getFilesDir();
            File shared = new File(files, "rime/shared");
            File user = new File(files, "rime/user");
            sharedPath = shared.getAbsolutePath(); userPath = user.getAbsolutePath();
            long stepStart=System.currentTimeMillis();
            String packagedVersion = assetVersion(context);
            reportStep("計算資料版本",stepStart,true,"");
            File marker = new File(files, "rime/.asset-version");
            String installedVersion = marker.isFile() ? readText(marker) : null;
            if (T9AssetVersion.shouldCopy(installedVersion, packagedVersion) || !shared.isDirectory()) {
                activeStep="複製資料"; stepStart=System.currentTimeMillis();
                copyTree(context, "rime", shared, true);
                writeTextAtomically(marker, packagedVersion);
                reportStep(activeStep,stepStart,true,"");
            }
            activeStep="建立使用者資料夾"; stepStart=System.currentTimeMillis();
            if (!user.isDirectory() && !user.mkdirs()) throw new IOException("cannot create Rime user data");
            reportStep(activeStep,stepStart,true,"");
            activeStep="儲存初始化標記"; stepStart=System.currentTimeMillis();
            T9InitGuard.Preferences prefs = T9InitGuard.adapt(
                    context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE));
            if (!T9InitGuard.markInitializationPending(prefs)) {
                throw new IOException("cannot persist T9 initialization marker");
            }
            reportStep(activeStep,stepStart,true,"");
            activeStep="建立 Rime 工作階段"; stepStart=System.currentTimeMillis();
            long created = nativeCreate(shared.getAbsolutePath(), user.getAbsolutePath(), this);
            reportStep(activeStep,stepStart,created!=0,created==0?"nativeCreate returned zero":"");
            if (created == 0) throw new IOException("librime session initialization failed");
            T9InitGuard.markInitializationFinished(prefs);
            if (closed) nativeDestroy(created); else handle = created;
        } catch (Throwable e) {
            initError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();
            ImeTelemetry telemetry=ImeTelemetry.get(); if(telemetry!=null)telemetry.init("失敗:"+activeStep,0,false,initError);
            try {
                T9InitGuard.markInitializationFinished(T9InitGuard.adapt(
                        context.getSharedPreferences("simon_ime_prefs", Context.MODE_PRIVATE)));
            } catch (Throwable ignored) { Log.w(TAG, "Could not clear failed T9 initialization marker", ignored); }
            Log.e(TAG, "Rime unavailable", e);
            handle = 0;
        }
        finally { initialized = true; }
    }
    private static void copyTree(Context c, String asset, File dir, boolean overwrite) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        String[] children = c.getAssets().list(asset);
        if (children == null || children.length == 0) {
            File out = new File(dir.getParentFile(), dir.getName());
            try (InputStream in = c.getAssets().open(asset); OutputStream os = new FileOutputStream(out)) { in.transferTo(os); }
            return;
        }
        Arrays.sort(children);
        for (String child : children) {
            String path = asset + "/" + child;
            String[] nested = c.getAssets().list(path);
            if (nested != null && nested.length > 0) copyTree(c, path, new File(dir, child), overwrite);
            else {
                File out = new File(dir, child);
                if (!overwrite && out.isFile() && out.length() > 0) continue;
                try (InputStream in = c.getAssets().open(path); OutputStream os = new FileOutputStream(out)) { in.transferTo(os); }
            }
        }
    }
    private static String assetVersion(Context context) throws IOException {
        try {
            android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(context.getPackageName(),0);
            long code=android.os.Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;
            return T9AssetVersion.fromPackage(code,info.lastUpdateTime);
        } catch(android.content.pm.PackageManager.NameNotFoundException e) { throw new IOException("cannot read APK version",e); }
    }
    private void reportStep(String step,long started,boolean ok,String message){
        if(ok||initError.isEmpty())initStep=step; ImeTelemetry t=ImeTelemetry.get();if(t!=null)t.init(step,System.currentTimeMillis()-started,ok,message);
        if(!ok&&initError.isEmpty())initError=message;
    }
    private void onNativeInitStep(String step,long ms,boolean ok,String message){initStep=step;if(!ok)initError=message==null?"初始化失敗":message;ImeTelemetry t=ImeTelemetry.get();if(t!=null)t.init(step,ms,ok,message);}
    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private static void writeTextAtomically(File file, String value) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File temporary = new File(parent, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(temporary)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
            ((FileOutputStream) out).getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("cannot replace Rime asset marker");
        if (!temporary.renameTo(file)) throw new IOException("cannot install Rime asset marker");
    }
    public boolean available() { return initialized && handle != 0 && !closed; }
    public boolean ready() { return initialized; }
    public String initializationStep(){return initStep;}
    public long initializationElapsedMs(){return Math.max(0,System.currentTimeMillis()-initStartedAt);}
    public String initializationError(){return initError;}
    public void key(String key) { if (!available() || !T9KeyMap.isDigit(key)) return; keys.append(key); if(twoPress) refreshSegments(); else {currentFullKeys.append(key);refreshFullSegment();} }
    public void backspace() { if (!available() || keys.length()==0) return; keys.deleteCharAt(keys.length()-1); if(twoPress)refreshSegments();else {if(currentFullKeys.length()>0)currentFullKeys.deleteCharAt(currentFullKeys.length()-1);else if(composition.size()>0){T9CompositionModel.Segment s=composition.segment(composition.size()-1);currentFullKeys.append(s.keys);composition.removeLast();}refreshFullSegment();} }
    public void clear() { keys.setLength(0); currentFullKeys.setLength(0); fullSegmentPlaced=false; composition.trim(0); }
    public boolean twoPressMode(){return twoPress;}
    public boolean setTwoPressMode(boolean enabled){if(keys.length()!=0)return false;twoPress=enabled;return true;}
    public void finishSegment(){if(!twoPress&&currentFullKeys.length()>0){currentFullKeys.setLength(0);fullSegmentPlaced=false;}}
    public String snapshot() {
        try { JSONObject out=new JSONObject().put("input",keys.toString()).put("preedit",composition.preview()).put("preview",composition.preview()).put("caret",composition.size());JSONArray cs=new JSONArray(candidatesAt(composition.size()-1));out.put("candidates",cs);JSONArray segs=new JSONArray();for(int i=0;i<composition.size();i++){T9CompositionModel.Segment s=composition.segment(i);JSONArray ca=new JSONArray();for(String c:s.candidates)ca.put(c);segs.put(new JSONObject().put("keys",s.keys).put("text",s.selected).put("candidates",ca));}return out.put("segments",segs).toString(); } catch(Exception e){return "{}";}
    }
    public String candidatesAt(int offset) { JSONArray a=new JSONArray();for(String c:composition.candidates(offset))a.put(c);return a.toString(); }
    public boolean chooseAt(int offset, int index) { return composition.choose(offset,index); }
    public boolean chooseCurrent(int index) { return composition.choose(composition.size()-1,index); }
    public String selected() { return composition.preview(); }
    public String candidatesFor(String keys) { return decode(available() ? nativeQuery(sharedPath, userPath, keys) : null); }
    public String keySequence() { if(twoPress)return keys.toString();StringBuilder out=new StringBuilder();for(String part:segmentKeys()){if(out.length()>0)out.append('\'');out.append(part);}return out.toString(); }
    public java.util.List<String> segmentKeys(){java.util.ArrayList<String> out=new java.util.ArrayList<>();if(twoPress){for(int i=0;i<keys.length();i+=2)out.add(keys.substring(i,Math.min(i+2,keys.length())));}else{for(int i=0;i<composition.size();i++)out.add(composition.segment(i).keys);if(currentFullKeys.length()>0&&!fullSegmentPlaced)out.add(currentFullKeys.toString());}return out;}
    private void refreshSegments(){
        int count=(keys.length()+1)/2;composition.trim(count);
        for(int i=0;i<count;i++){String part=keys.substring(i*2,Math.min(i*2+2,keys.length()));T9CompositionModel.Segment old=i<composition.size()?composition.segment(i):null;if(old!=null&&old.keys.equals(part))continue;
            ArrayList<String> candidates=new ArrayList<>();try{JSONArray values=new JSONArray(candidatesFor(part));for(int j=0;j<values.length();j++)candidates.add(values.getString(j));}catch(Exception ignored){}
            composition.update(i,part,candidates);
        }
    }
    private void refreshFullSegment(){
        if(currentFullKeys.length()==0 && fullSegmentPlaced){composition.removeLast();fullSegmentPlaced=false;return;}
        int count=composition.size()+(currentFullKeys.length()>0?1:0);composition.trim(count);
        if(currentFullKeys.length()==0)return;
        String part=currentFullKeys.toString();ArrayList<String> candidates=new ArrayList<>();
        try{JSONArray values=new JSONArray(candidatesFor(part));for(int j=0;j<values.length();j++)candidates.add(values.getString(j));}catch(Exception ignored){}
        int index=fullSegmentPlaced?composition.size()-1:composition.size();
        if(fullSegmentPlaced && composition.segment(index).keys.equals(part)) return;
        composition.update(index,part,candidates);
        fullSegmentPlaced=true;
    }
    @Override public void close() { closed=true; long old=handle; handle=0; if(old!=0) nativeDestroy(old); }
    private static String decode(byte[] b) { return b == null ? "" : new String(b, StandardCharsets.UTF_8); }
    private static native long nativeCreate(String shared, String user, T9RimeEngine listener);
    private static native void nativeDestroy(long handle);
    private static native byte[] nativeQuery(String shared, String user, String keys);
    static byte[] roundTripUtf8ForTest(String value) { return nativeRoundTrip(value); }
    private static native byte[] nativeRoundTrip(String value);
}
