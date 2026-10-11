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
final class DualHandEditor implements DualHandKeyboard.Hooks {
    private final DualHandKeyboard keyboard;
    private final SimonIMEService service;
    private final Handler ui;
    private DualHandTracks tracks;
    private DualHandMemory memory;
    private boolean closed;
    private final OkHttpClient http=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(3,TimeUnit.SECONDS).readTimeout(8,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build();
    private Call call;
    private Runnable deadline,selectionTask;
    private long requestedRevision,sent;
    private PopupWindow popup;
    private CursorAnchorInfo anchor;
    private int compositionStart=-1,compositionEnd=-1;
    private Snapshot witness,beforeWrite;
    private int beforeCompositionEnd=-1;
    private final Map<Integer,String> positions=new TreeMap<>(),glyphs=new TreeMap<>();
    private List<DualHandTracks.Choice> candidates=Collections.emptyList();
    private List<Integer> warnings=Collections.emptyList();
    private String shownKey="";
    private static final class Snapshot {
        final InputConnection connection;final long field;final String text;final int offset,start,end;
        Snapshot(InputConnection connection,long field,ExtractedText value){this.connection=connection;this.field=field;text=value.text.toString();offset=value.startOffset;start=offset+Math.min(value.selectionStart,value.selectionEnd);end=offset+Math.max(value.selectionStart,value.selectionEnd);}
        boolean sameText(Snapshot other){return other!=null&&connection==other.connection&&field==other.field&&offset==other.offset&&text.equals(other.text);}
        boolean same(Snapshot other){return sameText(other)&&start==other.start&&end==other.end;}
        String range(int left,int right){return left>=offset&&right>=left&&right<=offset+text.length()?text.substring(left-offset,right-offset):null;}
    }
    DualHandEditor(DualHandKeyboard keyboard,SimonIMEService service,Handler ui){
        this.keyboard=keyboard;this.service=service;this.ui=ui;
        ui.post(service::dualHandCursorUpdates);
        Thread loader=new Thread(()->{
            try(Reader words=asset("phrase_words.tsv");Reader grid=asset("grid_chars.tsv");Reader readings=asset("readings.tsv")){
                DualHandMemory personal=null;
                try{personal=new DualHandMemory(new File(service.getFilesDir(),"dual_hand_personal.tsv"));}
                catch(IOException failure){ui.post(()->{if(!closed)service.dualHandStatus("個人記憶未載入，本機詞庫仍可使用");});}
                DualHandTracks loaded=new DualHandTracks(words,grid,readings,personal);final DualHandMemory learned=personal;
                ui.post(()->{if(!closed){memory=learned;tracks=loaded;changed(keyboard);}});
            }catch(IOException failure){ui.post(()->{if(!closed)service.dualHandStatus("本機詞庫未載入，草稿已保留");});}
        },"DualHandDictionary");loader.setDaemon(true);loader.start();
    }
    private Reader asset(String file)throws IOException{return new InputStreamReader(service.getAssets().open("dual_hand/"+file),StandardCharsets.UTF_8);}
    private Snapshot snapshot(){
        try{
            InputConnection connection=service.getCurrentInputConnection();if(connection==null)return null;
            ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=512;request.hintMaxLines=10;
            ExtractedText value=connection.getExtractedText(request,0);
            if(value==null||value.text==null||value.partialStartOffset>=0||value.partialEndOffset>=0||value.startOffset<0||value.selectionStart<0||value.selectionEnd<0||value.selectionStart>value.text.length()||value.selectionEnd>value.text.length())return null;
            return new Snapshot(connection,service.dualHandFieldGeneration(),value);
        }catch(RuntimeException unavailable){return null;}
    }
    private void forgetPositions(){positions.clear();glyphs.clear();}
    private void validate(Snapshot now){if(witness!=null&&!witness.sameText(now))forgetPositions();}
    @Override public boolean beforeWrite(DualHandKeyboard page){
        beforeWrite=snapshot();beforeCompositionEnd=compositionStart<0?-1:compositionEnd;validate(beforeWrite);
        if(beforeWrite==null||witness!=null&&!witness.same(beforeWrite)){
            cancelCall("stale");dismissGrid();service.dualHandStatus("文字已變動，草稿已保留，請重新定位游標");return false;
        }
        return true;
    }
    @Override public void written(DualHandKeyboard page){
        Snapshot now=snapshot();
        if(now==null){forgetPositions();witness=null;compositionStart=-1;return;}
        String text=page.buffer().draft();int left=now.end-text.length();
        int oldLeft=beforeWrite==null?-1:compositionStart>=0?compositionStart:beforeWrite.start;
        if(beforeWrite!=null&&oldLeft>=0)rebase(beforeWrite,now,oldLeft,beforeCompositionEnd>=0?beforeCompositionEnd:beforeWrite.end,text);
        compositionStart=now.start==now.end&&text.equals(now.range(left,now.end))?left:-1;compositionEnd=compositionStart<0?-1:now.end;
        witness=now;
    }
    @Override public void committed(DualHandKeyboard page,String text,List<String> sounds){
        Snapshot now=snapshot();validateBeforeCommit(now,text);
        if(now!=null&&now.start==now.end&&text.equals(now.range(now.end-text.length(),now.end))&&sounds.size()==text.codePointCount(0,text.length())){
            int at=now.end-text.length(),index=0;
            for(int cp:text.codePoints().toArray()){String glyph=new String(Character.toChars(cp));positions.put(at,sounds.get(index++));glyphs.put(at,glyph);at+=glyph.length();}
        }
        witness=now;compositionStart=compositionEnd=-1;warnings=Collections.emptyList();cancelCall("cancelled");dismissGrid();
    }
    private void validateBeforeCommit(Snapshot now,String text){
        if(beforeWrite==null||now==null||beforeWrite.connection!=now.connection||beforeWrite.field!=now.field||beforeWrite.offset!=now.offset){forgetPositions();return;}
        int left=compositionStart>=0?compositionStart:beforeWrite.start;
        String prefix=beforeWrite.range(beforeWrite.offset,left),suffix=beforeWrite.range(beforeCompositionEnd>=0?beforeCompositionEnd:beforeWrite.end,beforeWrite.offset+beforeWrite.text.length());
        if(prefix==null||suffix==null||!(prefix+text+suffix).equals(now.text))forgetPositions();
        else rebase(beforeWrite,now,left,beforeCompositionEnd>=0?beforeCompositionEnd:beforeWrite.end,text);
    }
    private void rebase(Snapshot before,Snapshot after,int left,int right,String value){
        String prefix=before.range(before.offset,left),suffix=before.range(right,before.offset+before.text.length());
        if(after==null||before.connection!=after.connection||before.field!=after.field||before.offset!=after.offset||prefix==null||suffix==null||!(prefix+value+suffix).equals(after.text)){forgetPositions();return;}
        int delta=value.length()-(right-left);Map<Integer,String> moved=new TreeMap<>(),letters=new TreeMap<>();
        for(Map.Entry<Integer,String> entry:positions.entrySet()){
            int at=entry.getKey();if(at>=left&&at<right)continue;int next=at>=right?at+delta:at;moved.put(next,entry.getValue());letters.put(next,glyphs.get(at));
        }
        positions.clear();positions.putAll(moved);glyphs.clear();glyphs.putAll(letters);
    }
    @Override public void changed(DualHandKeyboard page){
        if(closed)return;
        if(call!=null&&requestedRevision!=page.buffer().revision())cancelCall("cancelled");
        if(!page.buffer().aiShown())warnings=Collections.emptyList();
        candidates=tracks==null||page.buffer().aiShown()?Collections.emptyList():tracks.candidates(page.buffer().syllables());
        String key=page.buffer().revision()+":"+candidates.size();
        LinearLayout row=page.candidateRow();row.removeAllViews();
        for(int i=0;i<candidates.size();i++){
            final int rank=i;DualHandTracks.Choice choice=candidates.get(i);TextView item=new TextView(service);
            item.setText(choice.text);item.setTextSize(19);item.setGravity(Gravity.CENTER);item.setTextColor(service.getColor(R.color.keyboard_key_text));item.setContentDescription("短句候選 "+choice.text);
            row.addView(item,new LinearLayout.LayoutParams(0,-1,1));item.setOnClickListener(v->choosePrefix(choice,rank));
        }
        if(!key.equals(shownKey)){shownKey=key;event("dh_cand","shown",candidates.size(),-1,0,false);}
    }
    @Override public boolean beforeSyllable(DualHandKeyboard page){
        if(!candidates.isEmpty())event("dh_cand","skipped",candidates.size(),-1,0,false);
        return !page.buffer().aiShown()||page.finish();
    }
    @Override public boolean space(DualHandKeyboard page){
        if(page.buffer().aiShown()){page.finish();return true;}
        if(!candidates.isEmpty()){choosePrefix(candidates.get(0),0);return true;}
        if(page.buffer().syllables().size()>=5){if(service.dualHandProtected()){haptic();page.finish();}else if(!connected()){service.dualHandStatus("AI 未連線");page.finish();}else ai(page);return true;}
        return false;
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
    private void cancelCall(String outcome){if(call!=null){long elapsed=SystemClock.elapsedRealtime()-sent;call.cancel();clearCall();event("dh_ai",outcome,keyboard.buffer().syllables().size(),-1,elapsed,false);}}
    private void haptic(){if(prefs().getBoolean("haptic_enabled",true))keyboard.view().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
    @Override public boolean restore(DualHandKeyboard page){
        cancelCall("cancelled");dismissGrid();
        if(page.buffer().restoreAi()){warnings=Collections.emptyList();page.compose(warnings);page.refresh();event("dh_ai","restored",page.buffer().syllables().size(),-1,0,true);return true;}
        if(!page.buffer().syllables().isEmpty()&&!page.finish())return true;
        Snapshot now=snapshot();if(now==null||now.start!=now.end||tracks==null)return true;
        String before=now.range(now.offset,now.start);int count=tracks.wordLength(before);if(count==0)return true;
        int units=before.length()-before.offsetByCodePoints(before.length(),-count);
        try{if(!now.connection.deleteSurroundingText(units,0))service.dualHandStatus("輸入框未接受刪除");}catch(RuntimeException unavailable){service.dualHandStatus("輸入框未接受刪除");}
        forgetPositions();witness=snapshot();return true;
    }
    @Override public void grid(DualHandKeyboard page,int index){Snapshot now=snapshot();if(now!=null&&compositionStart>=0&&page.buffer().draft().equals(now.range(compositionStart,compositionStart+page.buffer().draft().length())))openGrid(now,compositionStart,index,true);}
    @Override public void selection(DualHandKeyboard page,int start,int end,int composingLeft,int composingEnd){
        if(closed)return;
        if(selectionTask!=null)ui.removeCallbacks(selectionTask);
        boolean own=service.dualHandSelfUpdate();Snapshot now=snapshot();validate(now);
        if(own){
            String current=page.buffer().draft();
            if(now!=null&&composingLeft>=0&&composingEnd-composingLeft==current.length()&&current.equals(now.range(composingLeft,composingEnd))){compositionStart=composingLeft;this.compositionEnd=composingEnd;}
            witness=now;return;
        }
        cancelCall("stale");dismissGrid();
        if(now==null||now.start!=start||now.end!=end)return;
        if(start!=end)return;
        int left=composingLeft>=0?composingLeft:compositionStart;
        String current=page.buffer().draft();boolean inComposition=!current.isEmpty()&&left>=0&&current.equals(now.range(left,left+current.length()))&&start>=left&&start<=left+current.length();
        if(!current.isEmpty()&&!inComposition){service.dualHandFinishComposition();page.buffer().clearBuffer();compositionStart=compositionEnd=-1;page.refresh();}
        final int base=inComposition?left:now.offset;
        int utf=Math.max(base,start-(start>base?1:0));if(utf>now.offset&&Character.isLowSurrogate(now.text.charAt(utf-now.offset)))utf--;
        final int index=now.text.codePointCount(base-now.offset,utf-now.offset);final boolean composing=inComposition;
        selectionTask=()->{selectionTask=null;if(now.same(snapshot()))openGrid(now,base,index,composing);};ui.postDelayed(selectionTask,100);
        witness=now;
    }
    @Override public void cursorAnchor(CursorAnchorInfo info){anchor=info;}
    private void openGrid(Snapshot owner,int base,int index,boolean composing){
        if(tracks==null||!owner.same(snapshot()))return;cancelCall("cancelled");validate(owner);
        String text=composing?keyboard.buffer().draft():owner.text;int[] points=text.codePoints().toArray();
        if(index<0||index>=points.length)return;
        List<List<String>> sounds=new ArrayList<>();int at=base;
        for(int i=0;i<points.length;i++){
            String ch=new String(Character.toChars(points[i]));String known=composing?keyboard.buffer().syllables().get(i):ch.equals(glyphs.get(at))?positions.get(at):null;
            sounds.add(known==null?tracks.readings(ch):Collections.singletonList(known));at+=ch.length();
        }
        List<DualHandTracks.Choice> choices=tracks.grid(sounds,index);if(choices.isEmpty())return;
        String[] labels=choices.stream().map(c->c.text).toArray(String[]::new);long revision=keyboard.buffer().revision();dismissGrid();
        View content=service.buildChoiceGrid(labels,3,72,rank->{
            DualHandTracks.Choice choice=choices.get(rank);
            if(owner.same(snapshot())&&(!composing||revision==keyboard.buffer().revision())){
                if(composing){keyboard.buffer().replace(choice.start,choice.end,choice.text);boolean replaced=keyboard.compose(warnings(keyboard));keyboard.refresh();if(replaced){remember(keyboard.buffer().syllables().subList(choice.start,choice.end),choice.text,owner.field);keyboard.refresh();event("dh_grid","replaced",index,rank,0,false,choice.personal);}}
                else replaceCommitted(owner,base,text,choice,index,rank);
            }else {forgetPositions();service.dualHandStatus("文字已變動，請重新選字");}
            dismissGrid();
        });
        popup=new PopupWindow(content,-2,-2,false);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setClippingEnabled(true);popup.setElevation(8*service.getResources().getDisplayMetrics().density);
        try{showGridAtCursor(base+text.offsetByCodePoints(0,index));event("dh_grid","opened",index,-1,0,false);}
        catch(RuntimeException unavailable){dismissGrid();service.dualHandStatus("選字框未能開啟");}
    }
    private void showGridAtCursor(int position){
        if(anchor==null){popup.showAsDropDown(keyboard.candidateRow(),0,-Math.round(180*service.getResources().getDisplayMetrics().density));return;}
        RectF bounds=anchor.getCharacterBounds(position);
        if(bounds==null)bounds=new RectF(anchor.getInsertionMarkerHorizontal(),anchor.getInsertionMarkerTop(),anchor.getInsertionMarkerHorizontal(),anchor.getInsertionMarkerBottom());
        if(!Float.isFinite(bounds.left)||!Float.isFinite(bounds.top)){popup.showAsDropDown(keyboard.candidateRow());return;}
        anchor.getMatrix().mapRect(bounds);View content=popup.getContentView();content.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
        android.util.DisplayMetrics display=service.getResources().getDisplayMetrics();
        int x=Math.max(0,Math.min(Math.round(bounds.left),display.widthPixels-content.getMeasuredWidth()));
        int y=Math.max(0,Math.min(Math.round(bounds.bottom),display.heightPixels-content.getMeasuredHeight()));
        popup.showAtLocation(keyboard.view(),Gravity.TOP|Gravity.LEFT,x,y);
    }
    private void replaceCommitted(Snapshot owner,int base,String text,DualHandTracks.Choice choice,int index,int rank){
        int left=base+text.offsetByCodePoints(0,choice.start),right=base+text.offsetByCodePoints(0,choice.end);InputConnection connection=owner.connection;
        String old=owner.range(left,right);boolean selected=false;
        try{
            connection.beginBatchEdit();selected=connection.setSelection(left,right);Snapshot selection=snapshot();
            if(!selected||!owner.sameText(selection)||selection.start!=left||selection.end!=right)return;
            if(!connection.commitText(choice.text,1)){service.dualHandStatus("輸入框未接受校正");return;}
            Snapshot replaced=snapshot();String expected=owner.text.substring(0,left-owner.offset)+choice.text+owner.text.substring(right-owner.offset);
            if(replaced==null||!expected.equals(replaced.text)){forgetPositions();return;}
            List<String> retained=new ArrayList<>();int oldAt=left;
            for(int cp:old.codePoints().toArray()){retained.add(positions.get(oldAt));oldAt+=Character.charCount(cp);}
            rebase(owner,replaced,left,right,choice.text);int at=left,slot=0;
            for(int cp:choice.text.codePoints().toArray()){
                String ch=new String(Character.toChars(cp));String sound=retained.get(slot++);if(sound!=null){positions.put(at,sound);glyphs.put(at,ch);}at+=ch.length();
            }
            remember(choice.syllables,choice.text,owner.field);event("dh_grid","replaced",index,rank,0,false,choice.personal);witness=replaced;
        }catch(RuntimeException unavailable){service.dualHandStatus("輸入框未接受校正");}
        finally{try{if(selected){int delta=choice.text.length()-(right-left);int restoreStart=owner.start>=right?owner.start+delta:owner.start>left?left+choice.text.length():owner.start;int restoreEnd=owner.end>=right?owner.end+delta:owner.end>left?left+choice.text.length():owner.end;connection.setSelection(restoreStart,restoreEnd);}}finally{connection.endBatchEdit();witness=snapshot();}}
    }
    private void remember(List<String> sounds,String text,long field){
        if(memory==null||field!=service.dualHandFieldGeneration())return;
        try{memory.remember(sounds,text,service.dualHandProtected());}
        catch(IOException failure){service.dualHandStatus("個人記憶未儲存，文字已保留");}
    }
    private void dismissGrid(){if(popup!=null){popup.dismiss();popup=null;}}
    @Override public void cancel(DualHandKeyboard page,boolean clear){cancelCall("cancelled");dismissGrid();if(selectionTask!=null)ui.removeCallbacks(selectionTask);selectionTask=null;if(clear){forgetPositions();witness=null;compositionStart=compositionEnd=-1;anchor=null;}}
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
