package com.simon.voiceime;

import android.content.*;
import android.os.*;
import android.view.inputmethod.*;
import org.json.*;
import okhttp3.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Main-looper adapter: bounded field reads, cancellable delivery, metadata-only outcomes. */
final class AiSentencePhone {
    interface Host {
        ZhuyinInputController controller();
        InputConnection ownedConnection();
        boolean allowed();
        void replace(ZhuyinInputController controller);
        void render();
        void commitSuggestion();
    }
    private final Context context;
    private final Handler handler;
    private final Host host;
    private final AiSentence sentence;
    private final OkHttpClient http=new OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).connectTimeout(2800,TimeUnit.MILLISECONDS)
        .readTimeout(2800,TimeUnit.MILLISECONDS).callTimeout(2800,TimeUnit.MILLISECONDS).build();
    private Call call;
    private Runnable pause;
    private JSONObject request,localOption;
    private String appliedRequestId="";
    private String clausePrefix="",clauseKeyPrefix="",autoSource="ai";
    private int underlineStart=-1,underlineEnd=-1,revertKeyStart,revertKeyEnd;
    private AiComposition.RevertedSpan revertedSpan;
    private String ownedText="",leftWitness="",keyWitness="";
    private int compositionStart=-1,compositionEnd=-1;
    private boolean applying,expectedApplySelection;
    private AiComposition transaction;
    private long undoEditor=-1,undoComposition=-1;
    private final List<Span> installed=new ArrayList<>();
    private final TreeMap<Integer,JSONObject> touches=new TreeMap<>();
    private String touchKeys="";
    private long debounceEnd=-1,receive=-1,validation=-1,wouldRender=-1,phoneRender=-1;
    private int failureCount;
    private boolean correctionShown;
    private int revertedSpans;
    boolean correctionShown(){return correctionShown;}
    int revertedSpans(){return revertedSpans;}
    void resetFeedback(){correctionShown=false;revertedSpans=0;}
    void userTouched(){invalidatePending();}
    private String sentenceMode(){return prefs().getString("ai_sentence_mode","suggestions");}
    private int expectedCommitEnd=-1;
    private final Map<JSONObject,Integer> displayedRanks=new IdentityHashMap<>();
    private int localRank=-1;
    private final Map<JSONObject,List<JSONObject>> projections=new IdentityHashMap<>();
    private String projectionWitness="";

    void beginDisplay(){displayedRanks.clear();localRank=-1;}
    void displayed(JSONObject option,int rank){displayedRanks.put(option,rank);}
    void localDisplayed(int rank){localRank=rank;}
    void localRendered(int rank){if(rank>0&&rank==localRank&&localOption()!=null)suggestionEvent("shown","local",rank,localOption().optString("text").length());}
    private boolean focused(){ZhuyinInputController c=host.controller();return c!=null&&(c.wordFocused()||c.keyCaret()>=0||c.previewBoundary()>=0);}

    private static final class Span {
        final int start,end;final String text;
        Span(int start,int end,String text){this.start=start;this.end=end;this.text=text;}
    }
    AiSentencePhone(Context context,Handler handler,Host host)throws IOException {
        this.context=context;this.handler=handler;this.host=host;
        try(InputStream in=context.getAssets().open("sentence-contract.json")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];for(int n;(n=in.read(buffer))!=-1;)bytes.write(buffer,0,n);
            try{sentence=new AiSentence(new JSONObject(bytes.toString("UTF-8")));}catch(Exception invalid){throw new IOException("sentence contract",invalid);}
        }
    }
    // The same text that setComposingText writes must anchor the editor witness.
    // Native preedit may still be phonetic while the text-only editor shows glyphs.
    private String composingText(ZhuyinInputController controller){return controller.textLayout()?controller.textPreview():controller.state().composingText;}
    private long now(){return SystemClock.elapsedRealtime();}
    // Persist both switches and the marker atomically before any input can mutate.
    static synchronized void migrateAutoApply(Context context){
        SharedPreferences p=context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE);
        if(p.getInt("ai_auto_apply_migration_version",0)>=671)return;
        SharedPreferences.Editor edit=p.edit().putBoolean("auto_correction",false)
            .putBoolean("ai_sentence_auto_apply",false).putInt("ai_auto_apply_migration_version",671);
        if("shadow".equals(p.getString("ai_sentence_mode","suggestions")))edit.putString("ai_sentence_mode","suggestions");
        if(!edit.commit())throw new IllegalStateException("AI safety migration not persisted");
        try{ImeTelemetry.install(context).record("ai_sentence","bopomofo",new JSONObject()
            .put("outcome","mode_migrated").put("client_mode",p.getString("ai_sentence_mode","suggestions"))
            .put("migration_version",671).put("client_auto",false),false);}
        catch(Exception failure){android.util.Log.w("AiSentencePhone","Mode migration telemetry failed",failure);}
    }
    private SharedPreferences prefs(){return context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE);}
    // Text layout has no AI suggestion surface during Z2. Preserve the
    // independently enabled preview auto-apply path for the Z3 integration.
    private boolean predictionEnabled(){
        ZhuyinInputController c=host.controller();
        return c==null||!c.textLayout()||("live".equals(sentenceMode())&&prefs().getBoolean("ai_sentence_auto_apply",false));
    }
    void changed(boolean editorChange){
        if(applying)return;
        if(editorChange&&host.controller()!=null)host.controller().resetCommitTelemetry();
        if(editorChange)revertedSpan=null;else if(revertedSpan!=null&&host.controller()!=null)revertedSpan.edited(host.controller().sentenceKeys());
        if(transaction!=null&&!editorChange&&host.controller()!=null&&host.controller().sentenceKeys().equals(keyWitness)&&composingText(host.controller()).equals(ownedText))return;
        if(call!=null){call.cancel();call=null;event("cancelled");}
        if(pause!=null)handler.removeCallbacks(pause);
        pause=null;
        if(transaction!=null){transaction.discard();transaction=null;underlineStart=underlineEnd=-1;}
        sentence.mode(sentenceMode());
        sentence.edit(now(),editorChange,host.allowed());request=null;
        if(host.controller()!=null&&host.controller().previewText().isEmpty())sentence.begin(now(),"",false,()->null);
        debounceEnd=receive=validation=wouldRender=phoneRender=-1;
        expectedApplySelection=false;
        if(editorChange){compositionStart=compositionEnd=-1;installed.clear();touches.clear();touchKeys="";}
        localOption=null;projections.clear();
        displayedRanks.clear();localRank=-1;
        if(!predictionEnabled())return;
        if(host.allowed()&&host.controller()!=null&&!host.controller().wordFocused()){
            String[] repair=host.controller().localRepair();
            if(repair.length==2&&!repair[1].equals(host.controller().previewText()))try{
                localOption=new JSONObject().put("id","local").put("text",repair[1]).put("keys",repair[0]);

            }catch(JSONException invalid){event("local_invalid");}
        }
        pause=()->{if(localOption!=null&&autoEnabled())applyLocal(true);send();};handler.postDelayed(pause,450);
    }
    /** Called only after the IME has written this composition to its owned editor. */
    void written(){
        // Composition bounds arrive through onUpdateSelection; no synchronous editor
        // queries in the physical key callback. snapshot()/apply still verify the editor.
    }
    void selection(int oldStart,int oldEnd,int start,int end,int candidatesStart,int candidatesEnd){
        if(applying)return;
        // Delayed editor callbacks may describe the composition before written().
        // The live same-field witness distinguishes those from a real cursor move.
        if(candidatesStart>=0&&request!=null&&witness())return;
        ZhuyinInputController c=host.controller();String owned=c==null?"":composingText(c);
        boolean own=host.allowed()&&host.ownedConnection()!=null&&start==end&&start==candidatesEnd&&candidatesStart>=0
            &&candidatesEnd-candidatesStart==owned.length();
        if(expectedApplySelection&&own&&candidatesStart==compositionStart&&ownedText.equals(owned)) {
            compositionEnd=candidatesEnd;expectedApplySelection=false;return;
        }
        if(own&&(compositionStart<0||candidatesStart==compositionStart)){
            compositionStart=candidatesStart;compositionEnd=candidatesEnd;
            if(sentence.charsDue(c.previewText())&&c.sentenceBoundary())send(true);
            return;
        }
        if(own&&candidatesStart==compositionStart&&candidatesEnd==compositionEnd)return;
        if(oldStart==start&&oldEnd==end&&candidatesStart==compositionStart&&candidatesEnd==compositionEnd)return;
        boolean verifiedCommit=expectedCommitEnd>=0&&start==end&&end==expectedCommitEnd&&owned.isEmpty();
        expectedCommitEnd=-1;
        boolean localEdit=verifiedCommit||(own&&(compositionStart<0||compositionStart==candidatesStart));
        if(!localEdit){installed.clear();touches.clear();touchKeys="";}
        compositionStart=own?candidatesStart:-1;compositionEnd=own?candidatesEnd:-1;
        changed(false);host.render();
    }
    void touch(String key,JSONObject observation){
        if(!host.allowed())return;
        String keys=host.controller().sentenceKeys();int same=0;while(same<keys.length()&&same<touchKeys.length()&&keys.charAt(same)==touchKeys.charAt(same))same++;
        touches.tailMap(same).clear();touchKeys=keys;
        if(keys.isEmpty()||observation==null)return;
        String typed="space".equals(key)?" ":key;if(!keys.endsWith(typed))return;
        JSONArray all=observation.optJSONArray("touch_alternatives");if(all==null||all.length()<2)return;
        try {
            JSONArray top=new JSONArray();for(int i=0;i<all.length()&&top.length()<2;i++){
                JSONObject alternative=all.getJSONObject(i);String symbol=alternative.getString("key");if("space".equals(symbol))symbol=" ";
                if(symbol.length()!=1||"ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ ˊˇˋ˙".indexOf(symbol)<0)continue;
                top.put(new JSONObject().put("symbol",symbol).put("probability",alternative.getDouble("probability")));
            }
            if(top.length()==2&&top.getJSONObject(0).getDouble("probability")<0.85){
                int position=keys.length()-1;touches.put(position,new JSONObject().put("key_slot",position).put("alternatives",top));
            }
        }catch(Exception invalid){event("touch_invalid");}
    }
    void committed(String word,boolean installedWord){
        if(!host.allowed()||!installedWord||word.isEmpty())return;
        InputConnection ic=host.ownedConnection(); // caller commits before its ownership is cleared
        if(ic==null)return;
        CharSequence before=ic.getTextBeforeCursor(word.length(),0);
        if(before==null||!word.contentEquals(before)){installed.clear();return;}
        // Full editor coordinates come from the previous verified composition.
        int end=compositionStart>=0?compositionStart+word.length():-1;
        if(end>=word.length()){expectedCommitEnd=end;installed.add(new Span(end-word.length(),end,word));if(installed.size()>8)installed.remove(0);}
    }
    private boolean witness(){
        if(!host.allowed()||host.ownedConnection()==null||compositionStart<0||compositionEnd<compositionStart)return false;
        if(!editorSelectionMatches())return false;
        if(host.controller()==null||!composingText(host.controller()).equals(ownedText)||!host.controller().sentenceKeys().equals(keyWitness))return false;
        CharSequence value=host.ownedConnection().getTextBeforeCursor(ownedText.length()+leftWitness.length(),0);
        return value!=null&&value.toString().equals(leftWitness+ownedText);
    }
    private boolean editorSelectionMatches(){
        if(!host.allowed()||host.ownedConnection()==null)return false;
        ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=1;request.hintMaxLines=1;
        ExtractedText extracted=host.ownedConnection().getExtractedText(request,0);
        return extracted!=null&&extracted.selectionStart==extracted.selectionEnd
            &&extracted.selectionEnd+extracted.startOffset==compositionEnd;
    }
    private JSONObject snapshot(){
        try {
        if(!host.allowed()||host.ownedConnection()==null||compositionStart<0)return null;
        ZhuyinInputController c=host.controller();ownedText=composingText(c);keyWitness=c.sentenceKeys();
        if(ownedText.isEmpty()||keyWitness.isEmpty()||compositionEnd-compositionStart!=ownedText.length()||!editorSelectionMatches())return null;
        CharSequence before=host.ownedConnection().getTextBeforeCursor(ownedText.length()+64,0);
        if(before==null||!before.toString().endsWith(ownedText))return null;
        String left=before.toString().substring(0,before.length()-ownedText.length());
        // A leading low surrogate means the editor truncated through a scalar; drop it.
        if(!left.isEmpty()&&Character.isLowSurrogate(left.charAt(0)))left=left.substring(1);
        int windowStart=compositionStart-left.length();JSONArray spans=new JSONArray();
        for(Span span:installed)if(span.start>=windowStart&&span.end<=compositionStart){
            int a=span.start-windowStart,b=span.end-windowStart;
            if(!left.substring(a,b).equals(span.text)){installed.clear();spans=new JSONArray();break;}
            spans.put(new JSONObject().put("start",left.codePointCount(0,a)).put("end",left.codePointCount(0,b)).put("kind","installed_word"));
        }
        JSONArray alternatives=new JSONArray();if(keyWitness.equals(touchKeys))for(JSONObject t:touches.values())if(alternatives.length()<8)alternatives.put(t);
        String literal=c.previewText();int split=0;
        for(int i=0;i<literal.length();i++)if("，。！？；：、,!?;:\n".indexOf(literal.charAt(i))>=0)split=i+1;
        clausePrefix=literal.substring(0,split);clauseKeyPrefix="";
        if(split>0){int cp=literal.codePointCount(0,split),used=0;List<String> syllables=c.phoneticSyllables();
            if(syllables.size()<cp)return null;for(int i=0;i<cp;i++)used+=syllables.get(i).length();
            if(used>keyWitness.length())return null;clauseKeyPrefix=keyWitness.substring(0,used);}
        String clauseKeys=keyWitness.substring(clauseKeyPrefix.length());
        if(clauseKeys.isEmpty()||literal.substring(split).isEmpty())return null;
        if(!clausePrefix.isEmpty()){spans=new JSONArray();alternatives=new JSONArray();}
        JSONObject req=AiSentence.request(sentence.schema,UUID.randomUUID().toString(),sentence.editorGeneration,sentence.compositionGeneration,clauseKeys,literal.substring(split),left+clausePrefix,spans,alternatives);
        leftWitness=left;req.put("client_auto",prefs().getBoolean("ai_sentence_auto_apply",false));return req;

        } catch(Exception invalid){throw new IllegalArgumentException("sentence snapshot",invalid);}
    }
    private void send(){send(false);}
    private void send(boolean eager){
        if(!predictionEnabled()||!host.allowed()||transaction!=null&&transaction.automatic())return;
        try {
            sentence.mode(sentenceMode());
            ZhuyinInputController controller=host.controller();String preview=controller==null?"":controller.previewText();
            boolean boundary=eager&&sentence.charsDue(preview)&&controller!=null&&controller.sentenceBoundary();
            JSONObject req=sentence.begin(now(),preview,boundary,this::snapshot);if(req==null){if(!eager)event("ineligible");return;}
            request=req;debounceEnd=now();
            String base=prefs().getString("server_url","http://100.84.86.128:8001");
            Request httpRequest=new Request.Builder().url(base.replaceAll("/+$","")+"/v1/ime/sentence-candidates")
                .header("Authorization",AuthConfig.authorizationHeader(AuthConfig.password(prefs())))
                .post(RequestBody.create(req.toString(),MediaType.parse("application/json; charset=utf-8"))).build();
            Call delivery=http.newCall(httpRequest);call=delivery;event("sent");
            delivery.enqueue(new Callback(){
                public void onFailure(Call ignored,IOException failure){handler.post(()->failed(req,delivery,failure instanceof java.io.InterruptedIOException?"timeout":"transport"));}
                public void onResponse(Call ignored,Response response){
                    String body=null,reason=null;
                    try(Response r=response){
                        if(!r.isSuccessful())reason="http_"+r.code();
                        else if(r.body()==null)reason="empty_body";
                        else {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=r.body().byteStream()){
                            byte[] buffer=new byte[1024];for(int n;(n=in.read(buffer,0,Math.min(buffer.length,4097-bytes.size())))!=-1;){bytes.write(buffer,0,n);if(bytes.size()>4096)throw new IOException("size");}}
                            body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();}
                    }catch(Exception invalid){reason="wire_invalid";}
                    final String payload=body,outcome=reason;handler.post(()->{
                        if(outcome!=null){failed(req,delivery,outcome);return;}
                        if(call!=delivery||!sentence.fresh(req,now())||!witness()){if(call==delivery)call=null;event("stale");return;}
                        call=null;receive=now();
                        try{
                            if(!sentence.receive(req,payload,now(),(keys,text)->{
                                ZhuyinInputController mapped=host.controller().preparedSentence(clauseKeyPrefix+keys,clausePrefix+text);if(mapped==null)return false;mapped.close();return true;
                            })){event("stale");return;}
                            validation=now();if(!sentence.fresh(req,now())||!witness()){sentence.result=null;event("stale");return;}
                            JSONObject decision=sentence.result.getJSONObject("decision");
                            if(!"none".equals(decision.getString("display"))&&sentence.result.getJSONArray("candidates").length()>0)wouldRender=now();
                            event("validated");
                            if(autoEnabled()&&"auto".equals(decision.optString("display"))){
                                for(JSONObject option:sentence.visible(request,now(),false))if(option.optString("id").equals(decision.optString("selected_id"))){applyCandidate(option,true);break;}
                            }
                            if(!options(false).isEmpty())host.render();
                            long delay=Math.max(0,4001-(now()-sentence.lastEdit));
                            handler.postDelayed(()->{if(request==req){sentence.result=null;host.render();event("expired");}},delay);
                        }catch(Exception invalid){failureCount++;sentence.failed(req);sentence.result=null;event("invalid");}
                    });
                }
            });
        }catch(Exception invalid){failureCount++;event("request_invalid");}
    }
    private void failed(JSONObject req,Call delivery,String outcome){
        if(call!=delivery)return;call=null;sentence.failed(req);failureCount++;event(outcome);
    }
    void rendered(JSONObject candidate,int rank){
        if(rank<=0||!rowOptions().contains(candidate)||!Integer.valueOf(rank).equals(displayedRanks.get(candidate)))return;
        if("ai".equals(candidate.optString("source","ai")))host.controller().shownAiSuggestion(candidate.optString("text"));
        suggestionEvent("shown",candidate.optString("source","ai"),rank,candidate.optString("text").codePointCount(0,candidate.optString("text").length()));
        if(phoneRender<0){phoneRender=now();event("rendered");}
    }
    private List<JSONObject> project(JSONObject source,boolean local){
        ZhuyinInputController c=host.controller();if(c==null)return Collections.emptyList();
        ZhuyinInputController.State state=c.state();String preview=c.previewText();
        String witness=preview+":"+state.targetStart+":"+state.targetEnd+":"+focused();
        if(!witness.equals(projectionWitness)){projections.clear();projectionWitness=witness;}
        if(projections.containsKey(source))return projections.get(source);
        List<JSONObject> result=new ArrayList<>();String full=local?source.optString("text"):clausePrefix+source.optString("text");
        List<String> syllables=c.phoneticSyllables();
        if(syllables.size()!=preview.codePointCount(0,preview.length()))return result;
        try {
            for(JSONObject span:AiSentence.changedSpans(preview,full)){
                int start=span.getInt("start"),end=span.getInt("end");
                // Expand a changed character to its live lexical focus word, while
                // retaining the original sentence outside that word.
                if(focused()){
                    if(start>=state.targetEnd||end<=state.targetStart)continue;
                    if(preview.codePointCount(0,preview.length())==full.codePointCount(0,full.length())){
                        start=Math.min(start,state.targetStart);end=Math.max(end,state.targetEnd);
                        span.put("start",start).put("end",end).put("text",full.substring(full.offsetByCodePoints(0,start),full.offsetByCodePoints(0,end)));
                    }
                }
                // Resolve the corrected word on an independent session. A response
                // may cover the whole preview and still be one dictionary word.
                if(!focused()&&preview.codePointCount(0,preview.length())==full.codePointCount(0,full.length())){
                    String correctedKeys=local?source.getString("keys"):clauseKeyPrefix+AiSentence.repairedKeys(request.getJSONArray("key_slots"),source.getJSONArray("repairs"));
                    ZhuyinInputController mapped=c.preparedSentence(correctedKeys,full);
                    if(mapped!=null)try{
                        ZhuyinInputController.State word=mapped.moveCursorToPreviewCharacter(start);
                        if(word.targetStart<=start&&word.targetEnd>=end){
                            start=word.targetStart;end=word.targetEnd;
                            span.put("start",start).put("end",end).put("text",full.substring(full.offsetByCodePoints(0,start),full.offsetByCodePoints(0,end)));
                        }
                    }finally{mapped.close();}
                }
                String label=span.getString("text");
                if(!AiSentence.rowWord(label,preview,Integer.MAX_VALUE))continue;
                
                int keyStart=0,keyEnd=0;for(int i=0;i<syllables.size();i++){if(i<start)keyStart+=syllables.get(i).length();if(i<end)keyEnd+=syllables.get(i).length();}
                String keys;
                if(local){
                    List<JSONObject> keyDiff=AiSentence.changedSpans(c.sentenceKeys(),source.getString("keys"));keys=c.sentenceKeys();
                    for(int i=keyDiff.size()-1;i>=0;i--){JSONObject change=keyDiff.get(i);if(change.getInt("start")>=keyStart&&change.getInt("end")<=keyEnd)keys=AiSentence.replaceSpan(keys,change);}
                }else{
                    JSONArray repairs=new JSONArray(),slots=request.getJSONArray("key_slots");
                    for(int i=0;i<source.getJSONArray("repairs").length();i++){JSONObject repair=source.getJSONArray("repairs").getJSONObject(i);int slot=clauseKeyPrefix.length()+repair.getInt("key_slot");if(slot>=keyStart&&slot<keyEnd)repairs.put(repair);}
                    keys=clauseKeyPrefix+AiSentence.repairedKeys(slots,repairs);
                }
                if(focused()){
                    // The current dictionary word is the row unit, including a
                    // same-reading spelling that is not itself a dictionary word.
                    if(start<state.targetStart||end>state.targetEnd||label.codePointCount(0,label.length())>state.targetEnd-state.targetStart)continue;
                }else{
                    String wordKeys=keys.substring(keyStart,keys.length()-(c.sentenceKeys().length()-keyEnd));
                    ZhuyinInputController word=c.preparedSentence(wordKeys,label);
                    if(word==null)continue;
                    boolean lexical;try{ZhuyinInputController.State unit=word.moveCursorToPreviewCharacter(0);lexical=unit.targetStart==0&&unit.targetEnd==label.codePointCount(0,label.length());}finally{word.close();}
                    if(!lexical)continue;
                }
                span.put("id",source.optString("id")).put("source",local?"local":"ai").put("keys",keys).put("preview",preview).put("protected",source.optBoolean("protected",true));result.add(span);
            }
        }catch(Exception invalid){event("span_invalid");result.clear();}
        projections.put(source,result);return result;
    }
    List<JSONObject> options(boolean chip){
        List<JSONObject> out=new ArrayList<>();for(JSONObject source:sentence.visible(request,now(),false))out.addAll(project(source,false));return out;
    }
    List<JSONObject> rowOptions(){
        List<JSONObject> out=new ArrayList<>();JSONObject local=localOption();if(local!=null)out.addAll(project(local,true));out.addAll(options(false));return out;
    }
    boolean unranked(){
        try {return sentence.result!=null&&!"ok".equals(sentence.result.getJSONObject("decision").getString("jev_status"));
        } catch(Exception invalid){return false;}
    }
    boolean autoEnabled(){return "live".equals(sentenceMode())&&prefs().getBoolean("ai_sentence_auto_apply",false)
        &&sentence.result!=null&&Boolean.TRUE.equals(sentence.result.opt("name_protection_ready"));}
    JSONObject localOption(){return host.controller()!=null&&!focused()&&localOption!=null&&!localOption.optString("text").equals(host.controller().previewText())?localOption:null;}
    String optionText(JSONObject candidate){return candidate.optString("text");}
    private boolean protectedChange(String text){
        return AiComposition.protectedChange(host.controller().previewText(),text,
            RimeVocabularyInstaller.words(new File(context.getFilesDir(),"rime/user")));
    }
    void applyLocal(boolean automatic){
        JSONObject option=localOption;if(option==null||!host.allowed()||focused())return;
        try{
            int rank=localRank;if(!automatic&&rank<=0)return;String text=option.getString("text");if(automatic&&(!autoEnabled()||protectedChange(text)||revertedSpan!=null&&revertedSpan.blocks(host.controller().sentenceKeys())))return;
            AiComposition tx=new AiComposition(host.controller());ZhuyinInputController after=automatic?
                tx.applyAuto(option.getString("keys"),text,now(),true,false):tx.apply(option.getString("keys"),text);
            if(after==null)return;autoSource="local";
            install(tx,after,automatic);
            if(!automatic){suggestionEvent("accepted","local",rank,text.codePointCount(0,text.length()));commitSuggestion();}
        }catch(Exception invalid){event("local_invalid");}
    }
    void apply(JSONObject candidate){applyCandidate(candidate,false);}
    private void applyCandidate(JSONObject candidate,boolean automatic){
        try {
        boolean local="local".equals(candidate.optString("source"));
        if(!host.allowed()||!automatic&&!rowOptions().contains(candidate)||!local&&(!sentence.fresh(request,now())||!witness())){event("tap_stale");return;}
        int rank=displayedRanks.getOrDefault(candidate,-1);if(!automatic&&rank<=0)return;
        String keys=automatic?clauseKeyPrefix+AiSentence.repairedKeys(request.getJSONArray("key_slots"),candidate.getJSONArray("repairs")):candidate.getString("keys");
        String text=automatic?clausePrefix+candidate.getString("text"):AiSentence.replaceSpan(host.controller().previewText(),candidate);
        if(!automatic&&!host.controller().previewText().equals(candidate.getString("preview"))){event("tap_stale");return;}
        if(automatic&&(now()-sentence.lastEdit>2500||focused()||!autoEnabled()||candidate.optBoolean("protected",true)||protectedChange(text)||revertedSpan!=null&&revertedSpan.blocks(host.controller().sentenceKeys())))return;
        AiComposition tx=new AiComposition(host.controller());ZhuyinInputController after=automatic?tx.applyAuto(keys,text,now(),true,false):tx.apply(keys,text);
        if(after==null){event("tap_unmapped");return;}
        if(!local&&(!sentence.fresh(request,now())||!witness())){after.close();event("tap_stale");return;}
        autoSource=local?"local":"ai";install(tx,after,automatic);
        if(!automatic)suggestionEvent("accepted",autoSource,rank,candidate.optString("text").codePointCount(0,candidate.optString("text").length()));
        } catch(Exception invalid){event("tap_invalid");}
    }
    private void install(AiComposition tx,ZhuyinInputController after,boolean automatic){
        String old=host.controller().previewText(),text=after.previewText();
        int prefix=0,suffix=0;while(prefix<old.length()&&prefix<text.length()&&old.charAt(prefix)==text.charAt(prefix))prefix++;
        while(suffix<old.length()-prefix&&suffix<text.length()-prefix&&old.charAt(old.length()-1-suffix)==text.charAt(text.length()-1-suffix))suffix++;
        List<String> reading=host.controller().phoneticSyllables();
        revertKeyStart=0;revertKeyEnd=host.controller().sentenceKeys().length();
        if(reading.size()==old.codePointCount(0,old.length())){
            int from=old.codePointCount(0,prefix),to=old.codePointCount(0,old.length()-suffix),used=0;
            for(int i=0;i<reading.size();i++){if(i==from)revertKeyStart=used;used+=reading.get(i).length();if(i+1==to)revertKeyEnd=used;}
        }
        appliedRequestId=request==null?"":request.optString("request_id","");
        projections.clear();transaction=tx;undoEditor=sentence.editorGeneration;undoComposition=sentence.compositionGeneration;
        underlineStart=automatic?prefix:-1;underlineEnd=automatic?text.length()-suffix:-1;
        if(automatic&&underlineStart==underlineEnd&&!text.isEmpty()){
            underlineStart=Math.min(underlineStart,text.offsetByCodePoints(text.length(),-1));
            underlineEnd=text.offsetByCodePoints(underlineStart,1);
        }
        applying=true;try{ownedText=composingText(after);keyWitness=after.sentenceKeys();compositionEnd=compositionStart+ownedText.length();expectedApplySelection=true;sentence.result=null;localOption=null;host.replace(after);host.render();}finally{applying=false;}
        if(automatic){correctionShown=true;correctionEvent("auto_applied",autoSource,"");}
    }
    private void commitSuggestion(){
        invalidatePending();applying=true;try{host.commitSuggestion();}finally{applying=false;}
        if(transaction!=null){transaction.discard();transaction=null;}underlineStart=underlineEnd=-1;
    }
    android.text.SpannableString mark(String value){
        android.text.SpannableString marked=new android.text.SpannableString(value);
        if(canUndo()&&underlineStart>=0&&underlineEnd>underlineStart&&underlineEnd<=value.length()){marked.setSpan(new android.text.style.UnderlineSpan(),underlineStart,underlineEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);if(host.controller()!=null&&host.controller().textLayout())marked.setSpan(new android.text.style.BackgroundColorSpan(0x5577bbff),underlineStart,underlineEnd,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
        return marked;
    }
    boolean tapRevert(int utf){if(canUndo()&&transaction.automatic()&&utf>=underlineStart&&utf<underlineEnd){undo("tap");return true;}return false;}
    boolean beforeKey(String key){
        if(!host.controller().textLayout()&&"backspace".equals(key)&&host.controller().keyCaret()<0&&canUndo()&&transaction.automatic()){undo("backspace");return true;}
        if("enter".equals(key)){
            if(!host.controller().textLayout()&&canUndo()&&transaction.automatic()&&transaction.tooRecent(now()))undo("enter_dwell");
            invalidatePending();
        }
        return false;
    }
    private void invalidatePending(){if(call!=null){call.cancel();call=null;}if(pause!=null)handler.removeCallbacks(pause);sentence.edit(now(),false,false);request=null;localOption=null;}
    boolean canUndo(){return transaction!=null&&undoEditor==sentence.editorGeneration&&undoComposition==sentence.compositionGeneration;}
    void undo(){undo("tap");}
    private void undo(String how){
        if(!canUndo()){event("undo_stale");return;}
        ZhuyinInputController before=transaction.undo(host.controller());if(before==null)return;transaction=null;revertedSpan=new AiComposition.RevertedSpan(before.sentenceKeys(),revertKeyStart,revertKeyEnd);underlineStart=underlineEnd=-1;
        applying=true;try{host.replace(before);ownedText=composingText(before);keyWitness=before.sentenceKeys();compositionEnd=compositionStart+ownedText.length();expectedApplySelection=true;host.render();}finally{applying=false;}
        revertedSpans++;correctionEvent("auto_reverted",autoSource,how);event("undone");
    }
    void suggestionEvent(String outcome,String source,int rank,int spanChars){
        ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry==null||!host.allowed())return;
        try{telemetry.record("suggestion","bopomofo",new JSONObject().put("action",outcome).put("source",source).put("rank",rank).put("span_chars",spanChars).put("request_id",request==null?"":request.optString("request_id","")),false);}
        catch(Exception invalid){android.util.Log.w("AiSentence","suggestion metadata unavailable");}
    }
    private void correctionEvent(String outcome,String source,String how){
        ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry==null||!host.allowed())return;
        try{telemetry.record(outcome,"bopomofo",new JSONObject().put("source",source).put("how",how).put("request_id",appliedRequestId),false);}
        catch(Exception invalid){android.util.Log.w("AiSentence","correction metadata unavailable");}
    }
    private void event(String outcome){
        ImeTelemetry t=ImeTelemetry.get();if(t==null)return;
        try {
            JSONObject fields=new JSONObject().put("outcome",outcome).put("client_mode",sentence.mode()).put("failure_count",failureCount)
                .put("trigger",sentence.trigger()).put("editor_generation",sentence.editorGeneration).put("composition_generation",sentence.compositionGeneration)
                .put("last_edit_ms",sentence.lastEdit).put("debounce_end_ms",nullable(debounceEnd)).put("response_receive_ms",nullable(receive))
                .put("rime_validation_ms",nullable(validation)).put("would_render_ms",nullable(wouldRender)).put("phone_render_ms",nullable(phoneRender));
            if(request!=null)fields.put("request_id",request.getString("request_id"));
            if(sentence.result!=null)fields.put("server_times",sentence.result.getJSONObject("server_times")).put("decision_reason",sentence.result.getJSONObject("decision").getString("reason"))
                .put("candidate_count",sentence.result.getJSONArray("candidates").length());
            t.record("ai_sentence","bopomofo",fields,false);
        }catch(Exception ignored){android.util.Log.w("AiSentence","metadata event unavailable");}
    }
    private Object nullable(long time){return time<0?JSONObject.NULL:time;}
    void close(){if(call!=null)call.cancel();if(pause!=null)handler.removeCallbacks(pause);if(transaction!=null)transaction.discard();}
}
