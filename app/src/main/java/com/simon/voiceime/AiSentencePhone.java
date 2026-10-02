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
    }
    private final Context context;
    private final Handler handler;
    private final Host host;
    private final AiSentence sentence;
    private final OkHttpClient http=new OkHttpClient.Builder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).connectTimeout(1050,TimeUnit.MILLISECONDS)
        .readTimeout(1050,TimeUnit.MILLISECONDS).callTimeout(1050,TimeUnit.MILLISECONDS).build();
    private Call call;
    private Runnable pause;
    private JSONObject request;
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
    private int expectedCommitEnd=-1;
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
    private long now(){return SystemClock.elapsedRealtime();}
    private SharedPreferences prefs(){return context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE);}
    void changed(boolean editorChange){
        if(applying)return;
        if(call!=null){call.cancel();call=null;event("cancelled");}
        if(pause!=null)handler.removeCallbacks(pause);
        if(transaction!=null){transaction.discard();transaction=null;}
        sentence.mode(prefs().getString("ai_sentence_mode","shadow"));
        sentence.edit(now(),editorChange,host.allowed());request=null;
        debounceEnd=receive=validation=wouldRender=phoneRender=-1;
        expectedApplySelection=false;
        if(editorChange){compositionStart=compositionEnd=-1;installed.clear();touches.clear();touchKeys="";}
        pause=()->send();handler.postDelayed(pause,450);
    }
    void selection(int oldStart,int oldEnd,int start,int end,int candidatesStart,int candidatesEnd){
        if(applying)return;
        ZhuyinInputController c=host.controller();String owned=c==null?"":c.state().composingText;
        boolean own=host.allowed()&&host.ownedConnection()!=null&&start==end&&start==candidatesEnd&&candidatesStart>=0
            &&candidatesEnd-candidatesStart==owned.length();
        if(expectedApplySelection&&own&&candidatesStart==compositionStart&&ownedText.equals(owned)) {
            compositionEnd=candidatesEnd;expectedApplySelection=false;return;
        }
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
        if(host.controller()==null||!host.controller().state().composingText.equals(ownedText)||!host.controller().sentenceKeys().equals(keyWitness))return false;
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
        ZhuyinInputController c=host.controller();ownedText=c.state().composingText;keyWitness=c.sentenceKeys();
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
        JSONObject req=AiSentence.request(sentence.schema,UUID.randomUUID().toString(),sentence.editorGeneration,sentence.compositionGeneration,keyWitness,c.previewText(),left,spans,alternatives);
        leftWitness=req.getJSONObject("left_context").getString("text");return req;

        } catch(Exception invalid){throw new IllegalArgumentException("sentence snapshot",invalid);}
    }
    private void send(){
        if(!host.allowed())return;
        try {
            sentence.mode(prefs().getString("ai_sentence_mode","shadow"));
            JSONObject req=sentence.begin(now(),this::snapshot);if(req==null){event("ineligible");return;}
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
                                ZhuyinInputController mapped=host.controller().preparedSentence(keys,text);if(mapped==null)return false;mapped.close();return true;
                            })){event("stale");return;}
                            validation=now();if(!sentence.fresh(req,now())||!witness()){sentence.result=null;event("stale");return;}
                            JSONObject decision=sentence.result.getJSONObject("decision");
                            if(!"none".equals(decision.getString("display"))&&sentence.result.getJSONArray("candidates").length()>0)wouldRender=now();
                            event("validated");
                            if(!options(false).isEmpty())host.render();
                            long delay=Math.max(0,1501-(now()-sentence.lastEdit));
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
    void rendered(){
        if(phoneRender<0&&!options(false).isEmpty()){phoneRender=now();event("rendered");}
    }
    List<JSONObject> options(boolean chip){
        List<JSONObject> visible=sentence.visible(request,now(),chip);
        ZhuyinInputController controller=host.controller();
        if(controller==null||!controller.wordFocused())return visible;
        ZhuyinInputController.State focus=controller.state();String literal=controller.previewText();
        int count=literal.codePointCount(0,literal.length());
        if(focus.targetStart<0||focus.targetEnd>count||focus.targetEnd<=focus.targetStart)return java.util.Collections.emptyList();
        String prefix=literal.substring(0,literal.offsetByCodePoints(0,focus.targetStart));
        String suffix=literal.substring(literal.offsetByCodePoints(0,focus.targetEnd));
        List<String> reading=controller.phoneticSyllables();int keyStart=0,keyEnd=0;
        for(int i=0;i<reading.size();i++){if(i<focus.targetStart)keyStart+=reading.get(i).length();if(i<focus.targetEnd)keyEnd+=reading.get(i).length();}
        List<JSONObject> scoped=new java.util.ArrayList<>();
        for(JSONObject option:visible){
            String text=option.optString("text");boolean safe=text.startsWith(prefix)&&text.endsWith(suffix)&&text.length()>=prefix.length()+suffix.length();
            JSONArray repairs=option.optJSONArray("repairs");
            if(repairs!=null)for(int i=0;i<repairs.length();i++){JSONObject repair=repairs.optJSONObject(i);int slot=repair==null?-1:repair.optInt("key_slot",-1);if(slot<keyStart||slot>=keyEnd)safe=false;}
            if(safe)scoped.add(option);
        }
        return scoped;
    }
    boolean unranked(){
        try {return sentence.result!=null&&!"ok".equals(sentence.result.getJSONObject("decision").getString("jev_status"));
        } catch(Exception invalid){return false;}
    }
    void apply(JSONObject candidate){
        try {
        if(!sentence.fresh(request,now())||!witness()||!options(false).contains(candidate)){event("tap_stale");return;}
        String keys=AiSentence.repairedKeys(request.getJSONArray("key_slots"),candidate.getJSONArray("repairs"));
        AiComposition tx=new AiComposition(host.controller());ZhuyinInputController after=tx.apply(keys,candidate.getString("text"));
        // Native validation can consume the remaining visible budget; recheck after it.
        if(after==null){event("tap_unmapped");return;}
        if(!sentence.fresh(request,now())||!witness()){after.close();event("tap_stale");return;}
        transaction=tx;undoEditor=sentence.editorGeneration;undoComposition=sentence.compositionGeneration;
        applying=true;try{host.replace(after);ownedText=after.state().composingText;keyWitness=after.sentenceKeys();compositionEnd=compositionStart+ownedText.length();expectedApplySelection=true;sentence.result=null;host.render();}finally{applying=false;}
        event("applied");

        } catch(Exception invalid){event("tap_invalid");}
    }
    boolean canUndo(){return transaction!=null&&undoEditor==sentence.editorGeneration&&undoComposition==sentence.compositionGeneration;}
    void undo(){
        if(!canUndo()||!witness()){event("undo_stale");return;}
        ZhuyinInputController before=transaction.undo(host.controller());if(before==null)return;transaction=null;
        applying=true;try{host.replace(before);ownedText=before.state().composingText;keyWitness=before.sentenceKeys();compositionEnd=compositionStart+ownedText.length();expectedApplySelection=true;host.render();}finally{applying=false;}
        event("undone");
    }
    private void event(String outcome){
        ImeTelemetry t=ImeTelemetry.get();if(t==null)return;
        try {
            JSONObject fields=new JSONObject().put("outcome",outcome).put("client_mode",sentence.mode()).put("failure_count",failureCount)
                .put("editor_generation",sentence.editorGeneration).put("composition_generation",sentence.compositionGeneration)
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
