package com.simon.voiceime;
import android.os.*;import java.util.*;import java.io.IOException;import java.util.concurrent.TimeUnit;import org.json.*;import okhttp3.*;
/** Same authenticated proxy route; every new key cancels and invalidates delivery. */
final class T9Pause {
 static final long T9_PAUSE_MS=600;static final int T9_ALT_MARGIN=5;
 private final T9Keyboard page;private final SimonIMEService service;private final Handler handler=new Handler(Looper.getMainLooper());
 private final OkHttpClient http=new OkHttpClient.Builder().callTimeout(8,TimeUnit.SECONDS).build();
 private Runnable pending;private Call call;private int lastSent=-1;
 T9Pause(T9Keyboard page,SimonIMEService service){this.page=page;this.service=service;}
 void cancel(){if(pending!=null)handler.removeCallbacks(pending);pending=null;if(call!=null)call.cancel();call=null;}
 void schedule(int epoch,String codes,List<String> top,String display){cancel();if(epoch==lastSent)return;pending=()->send(epoch,codes,top,display);handler.postDelayed(pending,T9_PAUSE_MS);}
 private void send(int epoch,String codes,List<String> top,String display){if(!service.t9Allowed()||top.isEmpty())return;lastSent=epoch;String draft=top.get(0);List<String> alt=page.alternatives(draft,top);long start=SystemClock.elapsedRealtime();
  try{android.content.SharedPreferences prefs=service.getSharedPreferences("simon_ime_prefs",0);JSONObject request=new JSONObject().put("kind","t9_sentence").put("epoch",epoch).put("codes",codes).put("context",service.t9Context()).put("top20",new JSONArray(top)).put("draft",draft).put("alt",new JSONArray(alt)).put("display",display);
   Request req=new Request.Builder().url(prefs.getString("server_url","http://100.84.86.128:8001").replaceAll("/+$","")+"/v1/ime/sentence-candidates").header("Authorization",AuthConfig.authorizationHeader(AuthConfig.password(prefs))).post(RequestBody.create(request.toString(),MediaType.parse("application/json; charset=utf-8"))).build();
   Call delivery=http.newCall(req);call=delivery;delivery.enqueue(new Callback(){public void onFailure(Call ignored,IOException error){if(!delivery.isCanceled())android.util.Log.d("T9","Suggestion unavailable; local candidates retained");}
    public void onResponse(Call ignored,Response response){try(Response r=response){if(!r.isSuccessful()||r.body()==null||delivery.isCanceled())return;java.io.InputStream in=r.body().byteStream();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>16384)throw new IOException("T9 response size");}JSONObject result=new JSONObject(bytes.toString("UTF-8"));if(!result.has("epoch")||result.getInt("epoch")!=epoch)return;String value=result.optString("sentence"),source=result.optString("source","local");double confidence=result.optDouble("confidence",0);if(!Arrays.asList("local","gemini","jev").contains(source)||!Double.isFinite(confidence)||confidence<0||confidence>1)return;int illegalPositions=result.optInt("illegal_positions",0);if(illegalPositions<0||illegalPositions>100)return;handler.post(()->page.received(epoch,value,source,confidence,illegalPositions,SystemClock.elapsedRealtime()-start,alt,draft,codes));}catch(Exception e){android.util.Log.d("T9","Suggestion response rejected; local candidates retained");}}
   });
  }catch(Exception error){android.util.Log.w("T9","Suggestion request unavailable",error);}
 }
}
