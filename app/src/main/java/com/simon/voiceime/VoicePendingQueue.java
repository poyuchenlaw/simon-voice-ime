package com.simon.voiceime;

import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.util.*;
import java.util.concurrent.*;

/** Durable PCM and delivery outbox. Every filesystem operation belongs to ioExecutor. */
final class VoicePendingQueue {
    interface Uploader { String transcribe(File pcm, String sessionId, int sampleRate) throws Exception; }
    interface Delivery {
        void deliver(String text, String startedAt);
        default void archiveToHistory(String text,String startedAt)throws Exception {throw new IOException("archive history sink unavailable");}
    }
    interface Observer { void event(String phase, String sessionId, long audioMs, long bytes, int attempts, String error); }
    private static final long MIN_BACKOFF_MS = 5_000L, MAX_BACKOFF_MS = 600_000L;
    private final File dir;
    private final int sampleRate;
    private volatile Thread ioThread;
    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoiceQueueIO"); t.setDaemon(true); ioThread = t; return t;
    });
    private final Map<String, JSONObject> metadata = new HashMap<>(); // IO thread only
    private final Map<String, FileOutputStream> streams = new HashMap<>();
    private final Map<String, BufferedOutputStream> buffers = new HashMap<>();
    private final Map<String, Long> syncTimes = new HashMap<>();
    private final Map<String, Long> sizes = new HashMap<>();
    private final Set<String> receiptWaits = ConcurrentHashMap.newKeySet();
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();
    private final Set<String> discarded = ConcurrentHashMap.newKeySet();
    private final Map<String,Long> discardNext=new ConcurrentHashMap<>();
    private final Map<String,Integer> discardAttempts=new ConcurrentHashMap<>();
    private final Map<String, DigestOutputStream> digestStreams = new HashMap<>();
    private final Map<String, MessageDigest> digests = new HashMap<>();
    private final Set<String> backupFailures = ConcurrentHashMap.newKeySet();
    private volatile long cachedTotalBytes;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private static final Map<String, VoicePendingQueue> INSTANCES = new ConcurrentHashMap<>();
    private static final class Snapshot {
        final long start, bytes, next; final int attempts; final String state; final boolean delivered,archived;
        Snapshot(JSONObject m, long bytes) {
            start=m.optLong("started_at"); next=m.optLong("next_attempt_at"); attempts=m.optInt("attempts");
            state=m.optString("state"); archived=m.optBoolean("audio_archived"); delivered=m.optBoolean("delivered"); this.bytes=bytes;
        }
    }
    VoicePendingQueue(File filesDir, int sampleRate) { this(filesDir,sampleRate,false); }
    private VoicePendingQueue(File filesDir, int sampleRate, boolean async) {
        dir=new File(filesDir,"voice_pending"); this.sampleRate=sampleRate;
        if(async) execute(this::recover); else runIO(() -> {recover(); return null;});
        ioExecutor.scheduleAtFixedRate(this::pruneArchivedMetadata,1,1,TimeUnit.HOURS);
        ioExecutor.scheduleAtFixedRate(() -> {
            try {syncOpenFiles();}catch(IOException e){System.err.println("VoicePendingQueue periodic fsync failed: "+e);}
        },200,200,TimeUnit.MILLISECONDS);
    }
    static VoicePendingQueue getInstance(File filesDir,int sampleRate) {
        // Lexical normalization only: canonicalPath would touch disk on service creation.
        String key=new File(filesDir,"voice_pending").getAbsoluteFile().toPath().normalize().toString();
        return INSTANCES.computeIfAbsent(key,k->new VoicePendingQueue(filesDir,sampleRate,true));
    }
    void execute(Runnable task) {
        ioExecutor.execute(() -> {try {task.run();} catch(Exception e) {System.err.println("VoicePendingQueue IO failure: "+e.getClass().getSimpleName());}});
    }
    <T> T runIO(Callable<T> task) {
        try {return Thread.currentThread()==ioThread ? task.call() : ioExecutor.submit(task).get();}
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException("queue IO interrupted",e);}
        catch(ExecutionException e) {throw new IllegalStateException("queue IO failed",e.getCause());}
        catch(Exception e) {throw new IllegalStateException("queue IO failed",e);}
    }
    static String parseSuccessfulResponse(String raw)throws IOException {
        try {
            JSONObject j=new JSONObject(raw==null?"":raw);
            if(j.has("error")&&!j.isNull("error"))throw new IOException("transcription error payload");
            if(!j.has("text")||!(j.get("text") instanceof String))throw new IOException("malformed transcription response");
            return j.getString("text");
        } catch(org.json.JSONException e) {throw new IOException("malformed transcription response",e);}
    }
    String beginAsync() {
        return beginAsync(null);
    }
    String beginAsync(Runnable onFailure) {
        String id=UUID.randomUUID().toString();capturing.add(id);
        execute(() -> {try {beginFile(id);}catch(IOException e){
            backupFailures.add(id);capturing.remove(id);
            System.err.println("VoicePendingQueue backup failed: "+e.getClass().getSimpleName());
            if(onFailure!=null)onFailure.run();
        }});
        return id;
    }
    boolean backupFailed(String id) {return backupFailures.contains(id);}
    String begin() throws IOException {
        String id=UUID.randomUUID().toString(); runIO(() -> {beginFile(id);return null;}); return id;
    }
    private void beginFile(String id)throws IOException {
        if(discarded.contains(id))return;
        if(!pcm(id).createNewFile())throw new IOException("session file exists");
        try {
            JSONObject m=new JSONObject().put("schema_version",6).put("started_at",System.currentTimeMillis()).put("sample_rate",sampleRate)
                    .put("state","recording").put("attempts",0).put("last_error","");
            writeMeta(id,m);metadata.put(id,m);sizes.put(id,0L);publish(id);
        } catch(org.json.JSONException e) {throw new IOException(e);}
    }
    void appendAsync(String id,byte[] bytes,int length) {
        byte[] owned=Arrays.copyOf(bytes,length); execute(() -> {try {append(id,owned,owned.length);}catch(IOException e){throw new IllegalStateException(e);}});
    }
    void append(String id,byte[] bytes,int length)throws IOException {
        try {runIO(() -> {
            if(id==null||length<=0||discarded.contains(id)||!metadata.containsKey(id))return null;
            FileOutputStream out=streams.get(id); BufferedOutputStream buffer=buffers.get(id);
            if(out==null) {out=new FileOutputStream(pcm(id),true);buffer=new BufferedOutputStream(out,64*1024);streams.put(id,out);buffers.put(id,buffer);}
            DigestOutputStream digestStream=digestStreams.get(id);
            if(digestStream==null) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                // A reopened legacy file may already have a prefix.
                if(sizes.getOrDefault(id,0L)>0)try(InputStream in=new FileInputStream(pcm(id))) {
                    byte[] block=new byte[16384];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);
                }
                digestStream=new DigestOutputStream(buffer,digest);digestStreams.put(id,digestStream);digests.put(id,digest);
            }
            digestStream.write(bytes,0,length);
            long now=System.currentTimeMillis();
            if(now-syncTimes.getOrDefault(id,0L)>=200) {buffer.flush();out.getFD().sync();syncTimes.put(id,now);}
            sizes.put(id,sizes.getOrDefault(id,0L)+length);cachedTotalBytes+=length;publish(id);return null;
        });}catch(RuntimeException e){recordBackupFailure(id);throw e;}
    }
    private void recordBackupFailure(String id) {
        if(id==null)return;backupFailures.add(id);
        runIO(() -> {JSONObject m=metadata.get(id);if(m!=null)try {m.put("local_write_failed",true);writeMeta(id,m);}catch(Exception e){System.err.println("VoicePendingQueue failure marker not durable: "+e);}return null;});
    }
    void noteStreamedBytes(String id,int bytes) {
        if(id==null||bytes<=0)return;
        execute(() -> {JSONObject m=metadata.get(id);if(m!=null)try {
            m.put("streamed_bytes",m.optLong("streamed_bytes")+bytes);writeMeta(id,m);
        }catch(Exception e){System.err.println("VoicePendingQueue streamed-byte marker failed: "+e);}});
    }
    void sealStreamedBytes(String id) {
        runIO(() -> {JSONObject m=metadata.get(id);if(m!=null){m.put("streamed_bytes_final",true);writeMeta(id,m);}return null;});
    }
    boolean acceptServerCopyAfterWriteFailure(String id,JSONObject receipt) {
        return runIO(() -> {
            JSONObject m=metadata.get(id);
            if(m==null||capturing.contains(id)||discarded.contains(id)||!backupFailures.contains(id)||receipt==null
                    ||!id.equals(receipt.optString("client_session_id"))||m.optLong("streamed_bytes")<=0||!m.optBoolean("streamed_bytes_final")
                    ||receipt.optLong("byte_count",-1)<m.optLong("streamed_bytes")
                    ||!receipt.optString("sha256").matches("[a-f0-9]{64}"))return false;
            FileOutputStream stream=streams.remove(id);if(stream!=null)stream.close();buffers.remove(id);digestStreams.remove(id);digests.remove(id);
            m.put("audio_receipt",new JSONObject(receipt.toString())).put("audio_archived",true).put("server_transcript_pending",true).put("state","pending").put("archived_at",System.currentTimeMillis());writeMeta(id,m);
            if(pcm(id).exists()&&!pcm(id).delete())throw new IOException("bad PCM deletion failed");
            cachedTotalBytes=Math.max(0,cachedTotalBytes-sizes.getOrDefault(id,0L));sizes.remove(id);publish(id);return true;
        });
    }
    private void syncOpenFiles()throws IOException {
        for(String id:new ArrayList<>(streams.keySet())) {
            try {buffers.get(id).flush();streams.get(id).getFD().sync();syncTimes.put(id,System.currentTimeMillis());}
            catch(IOException e){recordBackupFailure(id);throw e;}
        }
    }
    void flushAll()throws IOException {execute(() -> {try {syncOpenFiles();}catch(IOException e){throw new IllegalStateException(e);}});}
    boolean flushAllBounded()throws IOException {
        Future<?> flush=ioExecutor.submit(() -> {syncOpenFiles();return null;});
        try {flush.get(100,TimeUnit.MILLISECONDS);return true;}
        catch(TimeoutException e){return false;} // Leave queued flush alive: it still owns the PCM.
        catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        catch(ExecutionException e){throw new IOException("lifecycle fsync failed",e.getCause());}
    }
    void flush(String id)throws IOException {runIO(() -> {flushFile(id);return null;});}
    private void flushFile(String id)throws IOException {
        if(id==null||!pcm(id).exists())return;
        FileOutputStream out=streams.remove(id); BufferedOutputStream buffer=buffers.remove(id);
        if(out!=null) {try {buffer.flush();out.getFD().sync();} finally {out.close();}}
        MessageDigest digest=digests.remove(id);digestStreams.remove(id);
        JSONObject m=metadata.get(id);
        if(m!=null)try {
            if(digest!=null)m.put("sha256",hex(digest.digest()));
            else if(!m.has("sha256"))m.put("sha256",hash(pcm(id)));
            m.put("byte_count",sizes.getOrDefault(id,0L));writeMeta(id,m);
        }catch(org.json.JSONException e){throw new IOException(e);}
    }
    void holdForReceipt(String id) {if(id!=null)receiptWaits.add(id);}
    void releaseReceiptWait(String id) {
        receiptWaits.remove(id);
        runIO(() -> {JSONObject m=metadata.get(id);if(m!=null&&pcm(id).exists())update(id,"pending","audio_receipt_not_confirmed",0);return null;});
    }
    void finishRecording(String id) { finishRecording(id,null); }
    void finishRecording(String id,Runnable completion) {
        execute(() -> {
            try {flushFile(id);capturing.remove(id);
                update(id,"pending","recording_stopped_waiting_receipt",0);
                JSONObject m=metadata.get(id);
                if(m!=null&&m.has("audio_receipt"))acceptReceipt(id,m.optJSONObject("audio_receipt"));
            } catch(Exception e) {recordBackupFailure(id);capturing.remove(id);runIO(() -> {update(id,"pending","local_write_failed",0);return null;});System.err.println("VoicePendingQueue finish failed: "+e);}
            finally {if(completion!=null)completion.run();}
        });
    }
    void markPending(String id,String error) {runIO(() -> {if(!capturing.contains(id))flushFile(id);update(id,"pending",error,0);return null;});}
    void markPendingAsync(String id,String error,Runnable completion) {
        execute(() -> {try {if(!capturing.contains(id))flushFile(id);update(id,"pending",error,0);}catch(IOException e){throw new IllegalStateException(e);}if(completion!=null)completion.run();});
    }
    void discard(String id) {
        if(id==null)return;discarded.add(id);
        runIO(() -> {
            File marker=new File(dir,id+".discard");
            if(!marker.exists())writeDiscardRetry(id,0,0);
            flushFile(id);capturing.remove(id);digests.remove(id);digestStreams.remove(id);deleteFiles(id);return null;
        });
    }
    List<String> pendingServerDiscards(){return new ArrayList<>(discarded);}
    void confirmServerDiscard(String id) {runIO(() -> {File marker=new File(dir,id+".discard");if(marker.exists()&&!marker.delete())throw new IOException("discard marker deletion failed");discarded.remove(id);discardNext.remove(id);discardAttempts.remove(id);return null;});}
    boolean serverDiscardDue(String id,long now){return now>=discardNext.getOrDefault(id,0L);}
    long serverDiscardDelayMs(String id){return Math.max(0,discardNext.getOrDefault(id,0L)-System.currentTimeMillis());}
    void serverDiscardFailed(String id) {
        runIO(() -> {if(discarded.contains(id)) {
            int attempts=discardAttempts.getOrDefault(id,0)+1;
            long delay=Math.min(3_600_000L,5_000L*(1L<<Math.min(20,attempts-1)));
            writeDiscardRetry(id,attempts,System.currentTimeMillis()+delay);
        }return null;});
    }
    private void writeDiscardRetry(String id,int attempts,long next)throws IOException {
        // Preserve backoff even if the disk cannot persist its retry marker.
        discardAttempts.put(id,attempts);discardNext.put(id,next);
        try(FileOutputStream out=new FileOutputStream(new File(dir,id+".discard"))) {
            out.write(new JSONObject().put("attempts",attempts).put("next_attempt_at",next).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();
        }catch(org.json.JSONException e){throw new IOException(e);}
    }
    void discardAsync(String id) {if(id==null)return;discarded.add(id);execute(() -> discard(id));}
    void persistResult(String id,String text) {persistResult(id,text,true,true);}
    void persistResult(String id,String text,boolean fullAudioTranscribed) {persistResult(id,text,fullAudioTranscribed,true);}
    void persistResult(String id,String text,boolean fullAudioTranscribed,boolean append) {
        runIO(() -> {
            if(discarded.contains(id))return null;
            JSONObject m=metadata.get(id);if(m==null)throw new IOException("missing session metadata");
            if(m.optBoolean("delivered")||m.optBoolean("clipboard_written"))return null;
            m.put("result_text",VoiceResultText.clean(text)).put("append_result",append);
            if(!m.has("delivered"))m.put("delivered",false);
            if(!m.has("clipboard_written"))m.put("clipboard_written",false);
            writeMeta(id,m);publish(id);return null;
        });
    }
    JSONObject recordingIdentity(String id) {
        return runIO(() -> {
            if(capturing.contains(id))throw new IOException("capture still active");
            flushFile(id);JSONObject m=metadata.get(id);if(m==null)throw new IOException("missing session metadata");
            return new JSONObject().put("client_session_id",id).put("byte_count",m.getLong("byte_count")).put("sha256",m.getString("sha256"));
        });
    }
    boolean acceptReceipt(String id,JSONObject receipt) {
        return runIO(() -> {
            if(id==null||discarded.contains(id)||receipt==null)return false;
            JSONObject m=metadata.get(id);if(m==null)return false;
            if(!id.equals(receipt.optString("client_session_id")))return false;
            if(m.optBoolean("audio_archived")&&m.optJSONObject("audio_receipt")!=null
                    &&m.optJSONObject("audio_receipt").toString().equals(receipt.toString())) {
                if(pcm(id).exists()&&!pcm(id).delete())throw new IOException("receipted PCM deletion failed");
                cachedTotalBytes=Math.max(0,cachedTotalBytes-sizes.getOrDefault(id,0L));sizes.remove(id);publish(id);return true;
            }
            if(capturing.contains(id)) {m.put("audio_receipt",new JSONObject(receipt.toString()));writeMeta(id,m);return false;}
            if(pcm(id).exists())flushFile(id);
            if(!m.has("byte_count")||!m.has("sha256")||receipt.optLong("byte_count",-1)!=m.optLong("byte_count",-2)
                    ||!m.getString("sha256").equals(receipt.optString("sha256")))return false;
            m.put("audio_receipt",new JSONObject(receipt.toString())).put("audio_archived",true).put("state","archived");if(!m.has("archived_at"))m.put("archived_at",System.currentTimeMillis());writeMeta(id,m);
            // This is the only ordinary PCM deletion path. Text never authorizes it.
            if(pcm(id).exists()&&!pcm(id).delete())throw new IOException("PCM deletion failed");
            cachedTotalBytes=Math.max(0,cachedTotalBytes-sizes.getOrDefault(id,0L));sizes.remove(id);publish(id);return true;
        });
    }
    void acceptReceiptAsync(String id,JSONObject receipt,Runnable completion) {
        execute(() -> {acceptReceipt(id,receipt);if(completion!=null)completion.run();});
    }
    private static String hex(byte[] bytes) {StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
    private static String hash(File file)throws IOException {
        MessageDigest digest;try {digest=MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
        try(InputStream in=new FileInputStream(file)) {byte[] block=new byte[16384];int n;while((n=in.read(block))!=-1)digest.update(block,0,n);}
        return hex(digest.digest());
    }
    void acknowledgeDelivery(String id,boolean clipboardWritten) {
        runIO(() -> {
            JSONObject m=metadata.get(id);if(m==null)throw new IOException("missing session metadata");
            m.put("delivered",true).put("clipboard_written",clipboardWritten);
            writeMeta(id,m);publish(id);return null;
        });
    }
    private void deleteFiles(String id)throws IOException {
        // Never remove metadata if PCM deletion failed: recovery must retain the done marker.
        if(pcm(id).exists()&&!pcm(id).delete())throw new IOException("PCM deletion failed");
        cachedTotalBytes=Math.max(0,cachedTotalBytes-sizes.getOrDefault(id,0L));sizes.remove(id);
        if(resultFile(id).exists()&&!resultFile(id).delete())throw new IOException("result deletion failed");
        if(meta(id).exists()&&!meta(id).delete())throw new IOException("metadata deletion failed");
        metadata.remove(id);syncTimes.remove(id);publish(id);
    }
    List<String> pendingOldestFirst() {
        List<Map.Entry<String,Snapshot>> entries=new ArrayList<>();
        for(Map.Entry<String,Snapshot> entry:snapshots.entrySet())
            if("pending".equals(entry.getValue().state)&&!capturing.contains(entry.getKey())&&!receiptWaits.contains(entry.getKey()))entries.add(entry);
        entries.sort(Comparator.comparingLong(entry->entry.getValue().start));
        List<String> ids=new ArrayList<>();for(Map.Entry<String,Snapshot> entry:entries)ids.add(entry.getKey());return ids;
    }
    boolean due(String id,long now) {Snapshot s=snapshots.get(id);return s!=null&&now>=s.next;}
    long retryDelayMs(String id) {Snapshot s=snapshots.get(id);return s==null?MIN_BACKOFF_MS:Math.max(0,s.next-System.currentTimeMillis());}
    byte[] pcmBytes(String id)throws IOException {return runIO(() -> {flushFile(id);return Files.readAllBytes(pcm(id).toPath());});}
    File pcmFile(String id) {return pcm(id);} // Path only; callers must use runIO for reading it.
    String startedAt(String id) {Snapshot s=snapshots.get(id);return s==null?"":String.valueOf(s.start);}
    int attempts(String id) {Snapshot s=snapshots.get(id);return s==null?0:s.attempts;}
    boolean receiptConfirmed(String id) {Snapshot s=snapshots.get(id);return s!=null&&s.archived;}
    boolean delivered(String id) {Snapshot s=snapshots.get(id);return s!=null&&s.delivered;}
    long audioMs(String id) {Snapshot s=snapshots.get(id);return s==null?0:s.bytes/(sampleRate*2L/1000L);}
    long totalBytes() {return cachedTotalBytes;}
    void uploadOne(String id,Uploader uploader,Delivery delivery,Observer observer) {
        try {
            JSONObject claim=runIO(() -> {
                JSONObject m=metadata.get(id);
                if(m==null||!"pending".equals(m.optString("state"))||capturing.contains(id)||discarded.contains(id))return null;
                if(pcm(id).exists())flushFile(id);
                if(!m.has("result_text")&&!m.optBoolean("ignore_legacy_result")) {String legacy=readResult(id);if(legacy!=null)m.put("result_text",legacy);}
                m.put("state","uploading").put("attempts",m.optInt("attempts")+1);writeMeta(id,m);publish(id);
                return new JSONObject(m.toString());
            });
            if(claim==null)return;
            boolean archived=claim.has("audio_receipt")&&acceptReceipt(id,claim.getJSONObject("audio_receipt"));
            String text=claim.has("result_text")?claim.getString("result_text"):null;
            boolean missingText=!claim.optBoolean("delivered")&&!claim.optBoolean("clipboard_written")
                    &&(text==null||VoiceResultText.clean(text).isEmpty());
            if(!archived||claim.optBoolean("server_transcript_pending")||missingText) {
                if(archived&&missingText)runIO(() -> {JSONObject m=metadata.get(id);m.put("server_transcript_pending",true);writeMeta(id,m);return null;});
                String raw=uploader.transcribe(pcm(id),id,sampleRate);
                // Archive responses carry two independent facts: text and durable custody.
                JSONObject response=new JSONObject(raw);
                String archiveText=parseSuccessfulResponse(raw);
                if(discarded.contains(id))return;
                if((claim.optBoolean("delivered")||claim.optBoolean("clipboard_written"))
                        &&VoiceResultText.clean(archiveText).length()>VoiceResultText.clean(text).length()
                        &&!VoiceResultText.isNonSpeech(archiveText,claim.optBoolean("append_result",true),-1)) {
                    runIO(() -> {JSONObject m=metadata.get(id);m.put("archive_history_text",VoiceResultText.clean(archiveText)).put("archive_history_written",false);writeMeta(id,m);return null;});
                    claim.put("archive_history_text",VoiceResultText.clean(archiveText)).put("archive_history_written",false);
                }
                if(text==null||VoiceResultText.isSilence(text)||VoiceResultText.isNonSpeech(text,claim.optBoolean("append_result",true),-1)) {text=archiveText;persistResult(id,text);}
                if(!acceptReceipt(id,response.optJSONObject("receipt")))throw new IOException("audio receipt missing or mismatched");
                runIO(() -> {JSONObject m=metadata.get(id);if(m!=null){m.put("server_transcript_pending",false);writeMeta(id,m);}return null;});
            }
            if(discarded.contains(id))return;
            if(claim.has("archive_history_text")&&!claim.optBoolean("archive_history_written")) {
                delivery.archiveToHistory(claim.getString("archive_history_text"),String.valueOf(claim.optLong("started_at")));
                runIO(() -> {JSONObject m=metadata.get(id);m.put("archive_history_written",true);writeMeta(id,m);return null;});
            }
            if(claim.optBoolean("delivered")||claim.optBoolean("clipboard_written"))return;
            if(text==null)throw new IOException("missing archived text");
            if(VoiceResultText.isSilence(text)||VoiceResultText.isNonSpeech(text,claim.optBoolean("append_result",true),-1)) {
                acknowledgeDelivery(id,false);
                if(observer!=null)observer.event("silence",id,0,0,attempts(id),"");return;
            }
            delivery.deliver(VoiceResultText.clean(text),String.valueOf(claim.optLong("started_at")));
            acknowledgeDelivery(id,true);
        } catch(Exception e) {
            long backoff=Math.min(MAX_BACKOFF_MS,MIN_BACKOFF_MS*(1L<<Math.min(16,Math.max(0,attempts(id)-1))));
            runIO(() -> {update(id,"pending",e.getClass().getSimpleName(),System.currentTimeMillis()+backoff);return null;});
            if(observer!=null)observer.event("pending_failed",id,audioMs(id),audioMs(id)*sampleRate*2/1000,attempts(id),e.getClass().getSimpleName());
        }
    }
    private void recover() {
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("queue directory creation failed");
        File[] discards=dir.listFiles((d,n)->n.endsWith(".discard"));
        if(discards!=null)for(File marker:discards) {
            String id=marker.getName().substring(0,marker.getName().length()-8);discarded.add(id);
            try {JSONObject retry=new JSONObject(new String(Files.readAllBytes(marker.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                discardAttempts.put(id,retry.optInt("attempts"));discardNext.put(id,retry.optLong("next_attempt_at"));
            }catch(org.json.JSONException legacy){discardNext.put(id,0L);}catch(IOException e){throw new IllegalStateException("discard recovery failed",e);}
            discard(id);
        }
        File[] metas=dir.listFiles((d,n)->n.endsWith(".json"));
        if(metas!=null)for(File f:metas) {
            String id=f.getName().substring(0,f.getName().length()-5);JSONObject m=readMeta(id);
            // Old done/text flags are not proof of custody; never delete legacy PCM.
            if(!pcm(id).exists()&&(m==null||!m.has("audio_receipt"))) {System.err.println("VoicePendingQueue: dropping metadata without audio (including legacy delivered-without-text)");f.delete();continue;}
            if(m!=null) {
                try {
                    if(m.optInt("schema_version",0)<6) {
                        String saved=m.has("result_text")?m.optString("result_text"):readResult(id);
                        boolean acknowledged=saved!=null&&(m.optBoolean("clipboard_written")
                                ||(m.has("full_audio_transcribed")&&m.optBoolean("delivered")));
                        m.put("delivered",acknowledged).put("clipboard_written",acknowledged&&m.optBoolean("clipboard_written"));
                        // r3 persisted an intent, not a receipt or authoritative full-file result.
                        if(!acknowledged&&!m.has("full_audio_transcribed")) {
                            m.remove("result_text");m.put("ignore_legacy_result",true);
                        }
                        m.put("schema_version",6);writeMeta(id,m);
                    }
                    metadata.put(id,m);if(m.optBoolean("local_write_failed"))backupFailures.add(id);
                }catch(Exception e){throw new IllegalStateException("legacy delivery migration failed",e);}
            }
        }
        File[] files=dir.listFiles((d,n)->n.endsWith(".pcm"));
        if(files!=null)for(File f:files) {
            String id=f.getName().substring(0,f.getName().length()-4);sizes.put(id,f.length());cachedTotalBytes+=f.length();
            JSONObject m=metadata.get(id);
            try {
                if(m==null) {m=new JSONObject().put("schema_version",6).put("started_at",f.lastModified()).put("sample_rate",sampleRate).put("state","pending").put("attempts",0).put("last_error","recovered_missing_metadata");metadata.put(id,m);writeMeta(id,m);}
                else {
                    m.remove("retry_mode");m.remove("requires_full_verification");
                    // Migrate size/hash from the actual legacy file, regardless of text flags.
                    m.put("byte_count",f.length()).put("sha256",hash(f));
                    if("done".equals(m.optString("state"))||"recording".equals(m.optString("state"))||"uploading".equals(m.optString("state")))update(id,"pending","recovered_after_process_exit",m.optLong("next_attempt_at"));
                    else writeMeta(id,m);
                }
            } catch(Exception e) {throw new IllegalStateException("queue recovery failed",e);}
        }
        for(Map.Entry<String,JSONObject> entry:metadata.entrySet()) {
            JSONObject m=entry.getValue();
            if(!pcm(entry.getKey()).exists()&&m.has("audio_receipt")&&((!m.optBoolean("delivered")&&!m.optBoolean("clipboard_written"))||m.optBoolean("server_transcript_pending"))) {
                try {update(entry.getKey(),"pending","recovered_text_outbox",m.optLong("next_attempt_at"));}catch(IOException e){throw new IllegalStateException(e);}
            }
        }
        metadata.forEach((id,m)->publish(id));
        pruneArchivedMetadata();
    }
    void afterRecovery(Runnable callback){execute(callback);}
    private void update(String id,String state,String error,long next)throws IOException {
        JSONObject m=metadata.get(id);if(m==null||discarded.contains(id))return;
        try {m.put("state",state).put("last_error",error==null?"":error).put("next_attempt_at",next);writeMeta(id,m);publish(id);}
        catch(org.json.JSONException e) {throw new IOException(e);}
    }
    private void publish(String id) {
        JSONObject m=metadata.get(id);
        if(m==null)snapshots.remove(id);
        else snapshots.put(id,new Snapshot(m,sizes.getOrDefault(id,0L)));
    }
    private void pruneArchivedMetadata() {
        long cutoff=System.currentTimeMillis()-7L*24*60*60*1000;
        for(String id:new ArrayList<>(metadata.keySet())) {
            JSONObject m=metadata.get(id);
            boolean textSettled=m.optBoolean("delivered")||m.optBoolean("clipboard_written");
            if(m.optBoolean("audio_archived")&&textSettled&&!m.optBoolean("server_transcript_pending")&&!pcm(id).exists()
                    &&(!m.has("archive_history_text")||m.optBoolean("archive_history_written"))
                    &&m.optLong("archived_at",m.optLong("started_at"))<cutoff) {
                try {deleteFiles(id);}catch(IOException e){System.err.println("VoicePendingQueue metadata expiry failed: "+e);}
            }
        }
    }
    private JSONObject readMeta(String id) {try {return new JSONObject(new String(Files.readAllBytes(meta(id).toPath()),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){return null;}}
    private void writeMeta(String id,JSONObject m)throws IOException {
        File tmp=new File(dir,id+".json.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)) {out.write(m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        if(!tmp.renameTo(meta(id)))throw new IOException("metadata rename failed");
    }
    private String readResult(String id)throws IOException {
        File f=resultFile(id);return f.isFile()?new String(Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8):null;
    }
    private File pcm(String id) {return new File(dir,id+".pcm");}
    private File meta(String id) {return new File(dir,id+".json");}
    private File resultFile(String id) {return new File(dir,id+".result");}
}
