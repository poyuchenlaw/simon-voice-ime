package com.simon.voiceime;

import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

/** Main-thread editor ownership; model calls are explicit, cancellable and never retried. */
final class DualHandEditor extends CorrectionEditor implements DualHandKeyboard.Hooks {
    private final DualHandKeyboard keyboard;
    private boolean singleMode;
    private DualHandMemory memory;
    private final OkHttpClient http=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build();
    private Call call;
    private Runnable deadline;
    private long requestedRevision,sent;
    private List<DualHandTracks.Choice> candidates=Collections.emptyList();
    private List<Integer> warnings=Collections.emptyList();
    private String shownKey="";

    DualHandEditor(DualHandKeyboard keyboard,SimonIMEService service,Handler ui){
        super(service,ui,null);this.keyboard=keyboard;
        this.page=new Page(){
            public View view(){return keyboard.view();}public LinearLayout candidateRow(){return keyboard.candidateRow();}public DualHandState buffer(){return keyboard.buffer();}
            public boolean dual(){return true;}public String name(){return "dual_hand";}public void refresh(){keyboard.refresh();}
            public boolean compose(List<Integer> warnings){return keyboard.compose(warnings);}public List<Integer> warnings(){return DualHandEditor.this.warnings(keyboard);}
            public void blur(PopupWindow popup,int radius){DualHandKeyboard.blurPopup(service,popup,radius);}
        };
        ui.post(service::dualHandCursorUpdates);
    }
    @Override protected void ready(){memory=super.memory;if(!closed)changed(keyboard);}
    @Override public boolean beforeWrite(DualHandKeyboard keyboard){return super.beforeWrite(page);}
    @Override public void written(DualHandKeyboard keyboard){super.written(page);}
    @Override public void committed(DualHandKeyboard keyboard,String text,List<String> sounds){super.committed(page,text,sounds);warnings=Collections.emptyList();cancelCall("cancelled");dismissGrid();}
    @Override public void selection(DualHandKeyboard keyboard,int start,int end,int left,int right){super.selection(page,start,end,left,right);}
    @Override public void keyPressed(){super.keyPressed();}
    @Override public void cursorAnchor(CursorAnchorInfo info){super.cursorAnchor(info);}
    @Override protected void gridEvent(String action,int index,int rank,boolean personal){super.gridEvent(action,index,rank,personal);event("dh_grid",action,index,rank,0,false,personal);}
    @Override public void changed(DualHandKeyboard page){
        if(closed)return;
        dismissMarker();
        if(call!=null&&requestedRevision!=page.buffer().revision())cancelCall("cancelled");
        if(!page.buffer().aiShown())warnings=Collections.emptyList();
        singleMode=page.buffer().selected()>=0||page.buffer().syllables().size()==1;
        int index=page.buffer().selected()>=0?page.buffer().selected():0;
        candidates=tracks==null?Collections.emptyList():singleMode?tracks.singles(page.buffer().syllables().get(index),index,page.buffer().locked(index)):
            page.buffer().aiShown()||page.buffer().hasLocks()?Collections.emptyList():tracks.candidates(page.buffer().syllables());
        String key=page.buffer().revision()+":"+candidates.size();
        LinearLayout row=page.candidateRow();row.removeAllViews();
        for(int i=0;i<candidates.size();i++){
            final int rank=i;DualHandTracks.Choice choice=candidates.get(i);TextView item=new TextView(service);
            item.setText(choice.text);KeyboardCandidates.apply(item,KeyboardRoles.dark(service),singleMode&&choice.text.equals(page.buffer().locked(choice.start)));item.setGravity(Gravity.CENTER);item.setContentDescription((singleMode?"單字候選 ":"短句候選 ")+choice.text+(item.isSelected()?" 已鎖定":""));
            row.addView(item,new LinearLayout.LayoutParams(-2,-1));item.setOnClickListener(v->{if(singleMode)chooseSingle(choice);else choosePrefix(choice,rank);});
        }
        if(!key.equals(shownKey)){shownKey=key;event("dh_cand","shown",candidates.size(),-1,0,false);}
    }
    @Override public boolean beforeSyllable(DualHandKeyboard page){
        if(!candidates.isEmpty())event("dh_cand","skipped",candidates.size(),-1,0,false);
        return !page.buffer().aiShown()||page.finish();
    }
    @Override public boolean space(DualHandKeyboard page){
        page.buffer().discardPending();
        if(page.buffer().aiShown()||page.buffer().hasLocks()){page.finish();return true;}
        if(!singleMode&&!candidates.isEmpty()){choosePrefix(candidates.get(0),0);return true;}
        if(page.buffer().syllables().size()>=5){if(service.dualHandProtected()){haptic();page.finish();}else if(!connected()){service.dualHandStatus("AI 未連線");page.finish();}else ai(page);return true;}
        return false;
    }
    private void chooseSingle(DualHandTracks.Choice choice){
        if(!singleMode||!candidates.contains(choice)||!beforeWrite(keyboard))return;
        cancelCall("cancelled");dismissGrid();keyboard.buffer().lock(choice.start,choice.text);
        if(keyboard.compose(Collections.emptyList()))remember(choice.syllables,choice.text,beforeWrite.field);
        keyboard.refresh();
    }
    private boolean choosePrefix(DualHandTracks.Choice choice,int rank){
        if(!candidates.contains(choice)||keyboard.buffer().aiShown())return false;
        int shown=candidates.size();dismissGrid();cancelCall("cancelled");if(!beforeWrite(keyboard))return false;
        List<String> sounds=keyboard.buffer().syllables();List<String> used=new ArrayList<>(sounds.subList(0,choice.end));
        if(!service.dualHandCommit(choice.text))return false;
        // commitText replaces the full old composing range; immediately compose only the unused suffix.
        committed(keyboard,choice.text,used);remember(used,choice.text,beforeWrite.field);keyboard.buffer().consume(choice.end);
        keyboard.compose(Collections.emptyList());keyboard.refresh();event("dh_cand","chosen",shown,rank,0,false,choice.personal);return true;
    }
    private android.content.SharedPreferences prefs(){return service.getSharedPreferences("simon_ime_prefs",0);}
    private String server(){return prefs().getString("server_url","http://100.84.86.128:8001");}
    private boolean connected(){String base=server();return base!=null&&!base.trim().isEmpty();}
    @Override public void ai(DualHandKeyboard page){
        if(page.buffer().hasLocks()){haptic();return;}
        page.buffer().discardPending();
        if(service.dualHandProtected())return;
        if(!connected()){service.dualHandStatus("AI 未連線");event("dh_ai","unconnected",page.buffer().syllables().size(),-1,0,false);return;}
        if(page.buffer().syllables().isEmpty()||call!=null)return;
        Snapshot owner=snapshot();validate(owner);String current=page.buffer().draft();
        if(owner==null||owner.start!=owner.end||compositionStart<0||owner.end!=compositionStart+current.length()||!current.equals(owner.range(compositionStart,owner.end))){service.dualHandStatus("輸入框未確認組字位置，草稿已保留");return;}
        String left=owner.range(owner.offset,compositionStart),draft=page.buffer().aiDraft();
        if(left==null)return;
        try{
            JSONObject request=new JSONObject().put("context",DualHandTracks.tail(left,50)).put("draft",draft);
            Request wire=new Request.Builder().url(server().replaceAll("/+$","")+"/v1/ime/dual-hand/reconstruct")
                .header("Authorization",AuthConfig.authorizationHeader(AuthConfig.password(prefs())))
                .post(RequestBody.create(request.toString(),MediaType.parse("application/json; charset=utf-8"))).build();
            Call delivery=http.newCall(wire);call=delivery;requestedRevision=page.buffer().revision();sent=SystemClock.elapsedRealtime();
            final long revision=requestedRevision,started=sent;event("dh_ai","sent",page.buffer().syllables().size(),-1,0,false);
            deadline=()->{if(call==delivery)failed(delivery,"timeout");};ui.postDelayed(deadline,DualHandTracks.TIMEOUT_MS);
            delivery.enqueue(new Callback(){
                public void onFailure(Call ignored,IOException failure){ui.post(()->failed(delivery,failure instanceof java.io.InterruptedIOException?"timeout":"unconnected"));}
                public void onResponse(Call ignored,Response response){
                    String payload=null,reason=null;
                    try(Response result=response){
                        if(!result.isSuccessful())reason=result.code()==504?"timeout":"unconnected";
                        else if(result.body()==null)reason="invalid";
                        else try(InputStream in=result.body().byteStream()){
                            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];
                            for(int n;(n=in.read(buffer,0,Math.min(buffer.length,4097-bytes.size())))!=-1;){bytes.write(buffer,0,n);if(bytes.size()>4096)throw new IOException("response too large");}
                            payload=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                        }
                    }catch(Exception invalid){reason="invalid";}
                    final String body=payload,error=reason;ui.post(()->{
                        if(call!=delivery)return;
                        if(error!=null){failed(delivery,error);return;}
                        if(!DualHandTracks.fresh(revision,page.buffer().revision(),started,SystemClock.elapsedRealtime())||!owner.same(snapshot())||service.dualHandProtected()){
                            cancelCall("stale");return;
                        }
                        try{
                            JSONObject result=new JSONObject(body);if(!(result.get("text") instanceof String))throw new JSONException("text contract");String text=result.getString("text");
                            if(!(result.get("length_ok") instanceof Boolean)||!(result.get("latency_ms") instanceof Number)||!Double.isFinite(result.getDouble("latency_ms"))||result.getDouble("latency_ms")<0||!(result.get("model") instanceof String)||!result.has("thinking"))throw new JSONException("contract");
                            boolean lengthOk=result.getBoolean("length_ok")&&text.codePointCount(0,text.length())==page.buffer().syllables().size();
                            long elapsed=SystemClock.elapsedRealtime()-started;
                            if(!lengthOk){clearCall();service.dualHandStatus("AI 字數不符，保留原稿");haptic();event("dh_ai","length_mismatch",page.buffer().syllables().size(),-1,elapsed,false);return;}
                            warnings=tracks==null?allIndices(page.buffer().syllables().size()):tracks.mismatches(text,page.buffer().syllables());
                            clearCall();page.buffer().applyAi(text);boolean adopted=page.compose(warnings);
                            if(!adopted)page.buffer().restoreAi();page.refresh();event("dh_ai",adopted?"accepted":"editor_rejected",page.buffer().syllables().size(),-1,elapsed,false);
                        }catch(Exception invalid){failed(delivery,"invalid");}
                    });
                }
            });
        }catch(Exception invalid){cancelCall("invalid");service.dualHandStatus("AI 未連線");event("dh_ai","unconnected",page.buffer().syllables().size(),-1,0,false);}
    }
    private static List<Integer> allIndices(int count){List<Integer> values=new ArrayList<>();for(int i=0;i<count;i++)values.add(i);return values;}
    private void failed(Call delivery,String outcome){if(call!=delivery)return;long elapsed=SystemClock.elapsedRealtime()-sent;delivery.cancel();clearCall();service.dualHandStatus("timeout".equals(outcome)?"AI 逾時，保留原稿":"AI 未連線");event("dh_ai",outcome,keyboard.buffer().syllables().size(),-1,elapsed,false);}
    private void clearCall(){call=null;if(deadline!=null)ui.removeCallbacks(deadline);deadline=null;}
    protected void cancelCall(String outcome){if(call!=null){long elapsed=SystemClock.elapsedRealtime()-sent;call.cancel();clearCall();event("dh_ai",outcome,keyboard.buffer().syllables().size(),-1,elapsed,false);}}
    private void haptic(){KeyboardHaptics.lock(keyboard.view());}
    @Override public boolean restore(DualHandKeyboard page){
        cancelCall("cancelled");dismissGrid();
        DualHandState.Delete deleted=page.buffer().eraseCharacter();
        if(deleted==DualHandState.Delete.EDITOR)service.dualHandErase();
        else if(deleted==DualHandState.Delete.BLOCKED)haptic();
        else {warnings=Collections.emptyList();if(deleted!=DualHandState.Delete.PENDING)page.compose(warnings);page.refresh();}
        if(deleted==DualHandState.Delete.RESTORED)event("dh_ai","restored",page.buffer().syllables().size(),-1,0,true);
        return true;
    }

    @Override public void grid(DualHandKeyboard page,int index){Snapshot now=snapshot();if(now!=null&&compositionStart>=0&&page.buffer().draft().equals(now.range(compositionStart,compositionStart+page.buffer().draft().length())))openGrid(now,compositionStart,index,true);}







    @Override public void cancel(DualHandKeyboard page,boolean clear){super.cancel(clear);cancelCall("cancelled");}
    @Override public void close(DualHandKeyboard page){closed=true;}
    @Override public List<Integer> warnings(DualHandKeyboard page){return page.buffer().aiShown()?tracks==null?allIndices(page.buffer().syllables().size()):tracks.mismatches(page.buffer().draft(),page.buffer().syllables()):Collections.emptyList();}
    private void event(String name,String action,int count,int rank,long latency,boolean restored){event(name,action,count,rank,latency,restored,false);}
    private void event(String name,String action,int count,int rank,long latency,boolean restored,boolean personal){
        try{
            JSONObject fields=new JSONObject();
            if("dh_cand".equals(name))fields.put("action",action).put("shown",Math.min(3,count)).put("chosen_index",rank);
            else if("dh_grid".equals(name))fields.put("action",action).put("index",Math.max(0,count)).put("chosen_index",rank);
            else fields.put("outcome",action).put("chars",count).put("latency_ms",Math.max(0,latency)).put("restored",restored);
            if("dh_cand".equals(name)||"dh_grid".equals(name))fields.put("personal",personal);
            keyboard.event(name,fields);
        }catch(JSONException invalid){throw new IllegalStateException(invalid);}
    }
}
