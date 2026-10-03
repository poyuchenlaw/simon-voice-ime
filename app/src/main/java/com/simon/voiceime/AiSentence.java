package com.simon.voiceime;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Pure contract and lifecycle boundary. Never commits text or selects a local candidate. */
final class AiSentence {
    final JSONObject schema;
    long editorGeneration,compositionGeneration,lastEdit;
    private long attempted=-1;
    private boolean eligible;
    private String lastLiteral="",trigger="pause";
    String trigger(){return trigger;}
    boolean charsDue(String preview){return "suggestions".equals(mode)&&preview!=null
        &&preview.codePointCount(0,preview.length())-lastLiteral.codePointCount(0,lastLiteral.length())>=3;}
    private String mode="shadow";
    JSONObject pending,result;
    private String recordedId,recordedDigest;
    private long recordedGeneration;
    AiSentence(JSONObject schema){this.schema=schema;}
    void mode(String value){mode="off".equals(value)||"suggestions".equals(value)?value:"shadow";}
    String mode(){return mode;}
    void edit(long now,boolean editorChange,boolean allowed){
        if(editorChange){editorGeneration++;lastLiteral="";}compositionGeneration++;lastEdit=now;eligible=allowed;pending=null;result=null;
    }
    JSONObject begin(long now,java.util.function.Supplier<JSONObject> snapshot){
        return begin(now,null,false,snapshot);
    }
    JSONObject begin(long now,String preview,boolean boundary,java.util.function.Supplier<JSONObject> snapshot){
        try {
        if(preview!=null&&preview.isEmpty()){lastLiteral="";return null;}
        boolean pause=now-lastEdit>=450&&now-lastEdit<=1500;
        boolean chars=boundary&&charsDue(preview);
        if(!eligible||"off".equals(mode)||(!pause&&!chars)||pending!=null||attempted==compositionGeneration)return null;
        if(preview!=null&&preview.equals(lastLiteral))return null;
        JSONObject req=snapshot.get();if(req==null)return null;
        validateRequest(schema,req);SentenceContract.require(req.getLong("editor_generation")==editorGeneration&&req.getLong("composition_generation")==compositionGeneration);
        if(preview==null&&req.getString("literal").equals(lastLiteral))return null;
        attempted=compositionGeneration;lastLiteral=preview==null?req.getString("literal"):preview;trigger=chars?"chars":"pause";
        recordedId=req.getString("request_id");recordedGeneration=compositionGeneration;recordedDigest=digest(req);
        pending=req;return req;
        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    boolean fresh(JSONObject req,long now){
        try {return eligible&&!"off".equals(mode)&&req!=null&&req.getLong("editor_generation")==editorGeneration&&req.getLong("composition_generation")==compositionGeneration&&recordedGeneration==compositionGeneration&&req.getString("request_id").equals(recordedId)&&digest(req).equals(recordedDigest)&&now>=lastEdit&&now-lastEdit<=4000;
        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    boolean receive(JSONObject req,String body,long now,java.util.function.BiPredicate<String,String> reading){
        if(pending!=req||!fresh(req,now))return false;pending=null;result=response(schema,req,body,reading);return true;
    }
    List<JSONObject> visible(JSONObject req,long now,boolean chip){
        try {
        if(!"suggestions".equals(mode)||!fresh(req,now)||result==null||!"suggestions".equals(result.getString("mode")))return Collections.emptyList();
        JSONObject d=result.getJSONObject("decision");String display=d.getString("display");
        if("none".equals(display)||(chip&&!"chip".equals(display)))return Collections.emptyList();
        JSONArray a=result.getJSONArray("candidates");List<JSONObject> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONObject c=a.getJSONObject(i);if(!"ok".equals(d.getString("jev_status"))||c.getString("id").equals(d.getString("selected_id")))out.add(c);}
        out.sort((first,second)->Boolean.compare(!first.optString("id").equals(d.optString("selected_id")),!second.optString("id").equals(d.optString("selected_id"))));
        return Collections.unmodifiableList(out);

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    /** Row-3 source indices: engine >=0, changed-span suggestion = -1-index. */
    static List<Integer> rowOrder(String preview,boolean focused,List<String> engine,List<String> groups,List<String> suggestions){
        int limit=Integer.MAX_VALUE;
        for(int i=0;i<engine.size();i++)if((i>=groups.size()||!"char".equals(groups.get(i)))&&rowWord(engine.get(i),preview,Integer.MAX_VALUE))limit=Math.min(limit,engine.get(i).codePointCount(0,engine.get(i).length()));
        if(limit==Integer.MAX_VALUE)limit=1;
        return rowOrder(preview,focused,engine,groups,suggestions,limit);
    }
    static boolean rowWord(String text,String preview,int limit){
        if(text==null||text.isEmpty()||text.codePointCount(0,text.length())>limit)return false;
        return text.codePoints().noneMatch(cp->Character.isWhitespace(cp)||"｜，。！？；：、,.!?;:\n".indexOf(cp)>=0);
    }
    static List<Integer> rowOrder(String preview,boolean focused,List<String> engine,List<String> groups,List<String> suggestions,int limit){
        List<Integer> indices=new ArrayList<>(),order=new ArrayList<>();boolean hasWord=false;
        if(focused)for(int i=0;i<engine.size();i++)if((i>=groups.size()||!"char".equals(groups.get(i)))&&rowWord(engine.get(i),preview,limit)){hasWord=true;break;}
        if(focused)for(int i=0;i<engine.size();i++)if(!hasWord||i>=groups.size()||!"char".equals(groups.get(i)))indices.add(i);
        for(int i=0;i<suggestions.size();i++)indices.add(-1-i);
        for(int i=0;i<engine.size();i++)if(!focused||hasWord&&i<groups.size()&&"char".equals(groups.get(i)))indices.add(i);
        Set<String> seen=new HashSet<>();
        for(int index:indices){String text=index>=0?engine.get(index):suggestions.get(-1-index);if((index>=0||!text.equals(preview))&&rowWord(text,preview,index>=0||focused?limit:Integer.MAX_VALUE)&&seen.add(text))order.add(index);}
        return order;
    }
    /** Scalar-safe minimal edit blocks; equal anchors separate independent corrections. */
    static List<JSONObject> changedSpans(String before,String after){
        int[] a=before.codePoints().toArray(),b=after.codePoints().toArray();
        int[][] lcs=new int[a.length+1][b.length+1];
        for(int i=a.length-1;i>=0;i--)for(int j=b.length-1;j>=0;j--)lcs[i][j]=a[i]==b[j]?1+lcs[i+1][j+1]:Math.max(lcs[i+1][j],lcs[i][j+1]);
        List<JSONObject> out=new ArrayList<>();int i=0,j=0;
        while(i<a.length||j<b.length){
            if(i<a.length&&j<b.length&&a[i]==b[j]){i++;j++;continue;}
            int start=i,newStart=j;
            while(i<a.length||j<b.length){
                if(i<a.length&&j<b.length&&a[i]==b[j])break;
                if(j<b.length&&(i==a.length||lcs[i][j+1]>=lcs[i+1][j]))j++;else i++;
            }
            try {String text=new String(b,newStart,j-newStart);
                out.add(new JSONObject().put("start",start).put("end",i).put("text",text));
            }catch(JSONException invalid){throw new IllegalArgumentException("span projection",invalid);}
        }
        return out;
    }
    static String replaceSpan(String before,JSONObject span){
        int start=span.optInt("start",-1),end=span.optInt("end",-1),count=before.codePointCount(0,before.length());
        if(start<0||end<start||end>count)throw new IllegalArgumentException("span bounds");
        return before.substring(0,before.offsetByCodePoints(0,start))+span.optString("text")+before.substring(before.offsetByCodePoints(0,end));
    }
    void failed(JSONObject req){if(pending==req){pending=null;result=null;}}
    static JSONObject request(JSONObject schema,String id,long editor,long composition,String keys,String literal,String left,JSONArray spans,JSONArray touches) {
        try {
        SentenceContract.scalars(keys);int count=SentenceContract.scalars(left),start=Math.max(0,count-32);
        // The caller supplies verified complete spans in its same-field left window.
        for(int i=0;i<spans.length();i++){JSONObject span=spans.getJSONObject(i);if(span.getInt("start")<start&&span.getInt("end")>start)start=span.getInt("end");}
        JSONArray kept=new JSONArray();
        for(int i=0;i<spans.length();i++){JSONObject span=spans.getJSONObject(i);if(span.getInt("start")>=start&&kept.length()<8)kept.put(new JSONObject().put("start",span.getInt("start")-start).put("end",span.getInt("end")-start).put("kind","installed_word"));}
        JSONArray slots=new JSONArray();keys.codePoints().forEach(cp->slots.put(new String(Character.toChars(cp))));
        JSONObject req=new JSONObject().put("kind","request").put("schema_version",1).put("op","sentence_candidates").put("request_id",id)
            .put("editor_generation",editor).put("composition_generation",composition).put("key_slots",slots).put("literal",literal)
            .put("touch_alternatives",touches).put("left_context",new JSONObject().put("text",left.substring(left.offsetByCodePoints(0,start))).put("installed_word_spans",kept));
        validateRequest(schema,req);return req;

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    static void validateRequest(JSONObject schema, JSONObject request) {
        try {
        SentenceContract.check(schema,"request",request);
        SentenceContract.require(request.toString().getBytes(StandardCharsets.UTF_8).length<=6144);
        JSONArray keys=request.getJSONArray("key_slots"),touch=request.getJSONArray("touch_alternatives");Set<Integer> positions=new HashSet<>();
        for(int i=0;i<touch.length();i++) {
            JSONObject t=touch.getJSONObject(i);int index=t.getInt("key_slot");SentenceContract.require(index<keys.length()&&positions.add(index));
            JSONArray pair=t.getJSONArray("alternatives");SentenceContract.require(!pair.getJSONObject(0).getString("symbol").equals(pair.getJSONObject(1).getString("symbol")));
            SentenceContract.require(pair.getJSONObject(0).getDouble("probability")+pair.getJSONObject(1).getDouble("probability")<=1.0);
        }
        JSONObject left=request.getJSONObject("left_context");int length=SentenceContract.scalars(left.getString("text")),end=0;
        JSONArray spans=left.getJSONArray("installed_word_spans");for(int i=0;i<spans.length();i++){
            JSONObject span=spans.getJSONObject(i);int a=span.getInt("start"),b=span.getInt("end");SentenceContract.require(a>=end&&a<b&&b<=length);end=b;
        }

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    static String repairedKeys(JSONArray keys,JSONArray repairs) {
        try {
        Map<Integer,JSONObject> byIndex=new HashMap<>();int prior=-1;
        for(int i=0;i<repairs.length();i++) {
            JSONObject r=repairs.getJSONObject(i);int index=r.getInt("key_slot");String op=r.getString("operation");
            SentenceContract.require(index>prior&&index<=keys.length());prior=index;
            if("insert".equals(op))SentenceContract.require(r.isNull("source_symbol")&&!r.isNull("target_symbol"));
            else {SentenceContract.require(index<keys.length()&&keys.getString(index).equals(r.getString("source_symbol")));
                if("replace".equals(op))SentenceContract.require(!r.isNull("target_symbol")&&!r.getString("target_symbol").equals(r.getString("source_symbol")));
                else SentenceContract.require("delete".equals(op)&&r.isNull("target_symbol"));}
            byIndex.put(index,r);
        }
        StringBuilder out=new StringBuilder();for(int index=0;index<=keys.length();index++) {
            JSONObject r=byIndex.get(index);String op=r==null?"":r.getString("operation");
            if("insert".equals(op)||"replace".equals(op))out.append(r.getString("target_symbol"));
            if(index<keys.length()&&!"replace".equals(op)&&!"delete".equals(op))out.append(keys.getString(index));
        }
        SentenceContract.require(out.length()>0&&out.length()<=128);return out.toString();

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
    static String digest(JSONObject req) {
        try {
            JSONObject left=req.getJSONObject("left_context");JSONArray spans=left.getJSONArray("installed_word_spans");
            StringBuilder canonical=new StringBuilder("{\"key_slots\":").append(req.getJSONArray("key_slots").toString())
                .append(",\"literal\":").append(JSONObject.quote(req.getString("literal")))
                .append(",\"left_context\":{\"text\":").append(JSONObject.quote(left.getString("text")))
                .append(",\"installed_word_spans\":[");
            for(int i=0;i<spans.length();i++){if(i>0)canonical.append(',');JSONObject span=spans.getJSONObject(i);
                canonical.append("{\"start\":").append(span.getInt("start")).append(",\"end\":").append(span.getInt("end"))
                    .append(",\"kind\":").append(JSONObject.quote(span.getString("kind"))).append('}');}
            canonical.append("]}}");
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
        }catch(Exception invalid){throw new IllegalArgumentException("sentence digest",invalid);}
    }
    static JSONObject response(JSONObject schema,JSONObject request,String body,java.util.function.BiPredicate<String,String> reading) {
        try {
        JSONObject result=SentenceContract.parse(body,4096);SentenceContract.check(schema,"response",result);
        if(result.has("digest"))SentenceContract.require(result.getString("digest").equals(digest(request)));
        for(String key:new String[]{"request_id","editor_generation","composition_generation"})SentenceContract.require(result.get(key).toString().equals(request.get(key).toString()));
        SentenceContract.require(result.getJSONObject("keep").getString("text").equals(request.getString("literal")));
        JSONObject times=result.getJSONObject("server_times");long previous=-1;
        for(String key:new String[]{"server_receive_ms","gemini_done_ms","jev_start_ms","jev_done_ms","response_send_ms"})if(!times.isNull(key)){long value=times.getLong(key);SentenceContract.require(value>=previous);previous=value;}
        JSONArray candidates=result.getJSONArray("candidates"),valid=new JSONArray();Set<String> ids=new HashSet<>(),texts=new HashSet<>();
        JSONObject decision=result.getJSONObject("decision");String selected=decision.getString("selected_id");
        for(int i=0;i<candidates.length();i++) {
            JSONObject candidate=candidates.getJSONObject(i);String id=candidate.getString("id"),text=candidate.getString("text");
            SentenceContract.require(ids.add(id)&&texts.add(text)&&!text.equals(request.getString("literal")));
            String keys=repairedKeys(request.getJSONArray("key_slots"),candidate.getJSONArray("repairs"));
            if(reading.test(keys,text))valid.put(candidate);
        }
        SentenceContract.require("keep".equals(selected)||"none".equals(selected)||ids.contains(selected));
        result.put("candidates",valid);
        return result;

        } catch(Exception invalid){throw new IllegalArgumentException("sentence contract",invalid);}
    }
}
