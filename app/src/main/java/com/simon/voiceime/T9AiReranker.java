package com.simon.voiceime;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

/** Optional whole-sentence reranker. Failures are intentionally silent and never block typing. */
final class T9AiReranker {
    interface Callback { void onSuggestion(String text); }
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(1500,TimeUnit.MILLISECONDS).readTimeout(1500,TimeUnit.MILLISECONDS).writeTimeout(1500,TimeUnit.MILLISECONDS).callTimeout(1500,TimeUnit.MILLISECONDS).build();
    void request(Context context,JSONArray segments,String before,String after,String mode,Callback callback) {
        SharedPreferences p=context.getSharedPreferences("simon_ime_prefs",Context.MODE_PRIVATE);
        String base=p.getString("server_url","http://100.84.86.128:8001");if(base.endsWith("/"))base=base.substring(0,base.length()-1);
        if(base.isEmpty())return;
        try {
            JSONObject body=new JSONObject().put("layout","t9_simon").put("mode",mode).put("segments",segments)
                    .put("context_before",before==null?"":before).put("context_after",after==null?"":after);
            Request.Builder rb=new Request.Builder().url(base+"/v1/zhuyin/rerank").post(RequestBody.create(body.toString(),MediaType.parse("application/json; charset=utf-8")));
            String auth=p.getString("auth_password","guangxin_voice_2026");if(!auth.isEmpty())rb.header("Authorization","Bearer "+auth);
            client.newCall(rb.build()).enqueue(new okhttp3.Callback(){
                @Override public void onFailure(Call c,java.io.IOException e) { }
                @Override public void onResponse(Call c,Response r) { try(Response response=r){if(response.code()!=503&&response.isSuccessful()&&response.body()!=null){String text=new JSONObject(response.body().string()).optString("text","");if(!text.isEmpty())callback.onSuggestion(text);}}catch(Exception ignored){} }
            });
        } catch(Exception ignored) { }
    }
}
