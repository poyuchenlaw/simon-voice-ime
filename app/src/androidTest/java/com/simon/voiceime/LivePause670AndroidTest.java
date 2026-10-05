package com.simon.voiceime;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import android.os.SystemClock;import android.graphics.Rect;import android.text.Spanned;import android.text.style.BackgroundColorSpan;import org.json.*;
public class LivePause670AndroidTest extends TextRows670AndroidTest {
 long lastPhysicalInjection;
 @Override void tap(Rect r){long injected=SystemClock.uptimeMillis();super.tap(r);lastPhysicalInjection=injected;}
 volatile int requestCount;int delay=500; Boolean protectionReady=true; Boolean candidateProtected=false;
 void readyWithoutOptIn()throws Exception{super.ready();}
 @Override void ready()throws Exception{readyWithoutOptIn();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("ai_sentence_mode","live").putBoolean("auto_correction",true).putBoolean("ai_sentence_auto_apply",true).commit();}
 @Override void startEndpoint()throws Exception{
  endpoint=new ServerSocket(8181,4,InetAddress.getByName("127.0.0.1"));
  server=new Thread(()->{while(!stop)try(Socket s=endpoint.accept()){
   s.setSoTimeout(4000);InputStream in=new BufferedInputStream(s.getInputStream());String first=line(in);int size=0;for(String h;!(h=line(in)).isEmpty();)if(h.toLowerCase().startsWith("content-length:"))size=Integer.parseInt(h.substring(15).trim());
   byte[] body=in.readNBytes(size);String response="{}";int code=404;
   if(first.startsWith("POST /v1/ime/sentence-candidates ")){
    JSONObject req=new JSONObject(new String(body,StandardCharsets.UTF_8));save("live-request-"+(++requestCount)+".json",req.toString());long now=SystemClock.elapsedRealtime();
    boolean match=req.getJSONArray("key_slots").join("").replace("\"","").equals("ㄉㄨㄛˋㄩˊ");
    JSONArray candidates=new JSONArray();if(match)candidates.put(new JSONObject().put("id","c1").put("text","多餘").put("repairs",new JSONArray().put(new JSONObject().put("operation","replace").put("key_slot",3).put("source_symbol","ˋ").put("target_symbol"," "))));
    if(match && candidateProtected!=null)candidates.getJSONObject(0).put("protected",candidateProtected);
    response=new JSONObject().put("name_protection_ready",protectionReady).put("kind","response").put("schema_version",1).put("request_id",req.getString("request_id")).put("editor_generation",req.getLong("editor_generation")).put("composition_generation",req.getLong("composition_generation")).put("mode","suggestions")
     .put("keep",new JSONObject().put("id","keep").put("text",req.getString("literal")).put("repairs",new JSONArray())).put("candidates",candidates)
     .put("decision",new JSONObject().put("display",match?"auto":"none").put("selected_id",match?"c1":"keep").put("jev_status","not_called").put("choice_confidence",.95).put("meaning_probability",JSONObject.NULL).put("reason","ready"))
     .put("gemini_model","sandbox-fixture").put("jev_model","sandbox-fixture").put("policy_version","sentence-r2-v1")
     .put("server_times",new JSONObject().put("server_receive_ms",now).put("gemini_done_ms",now).put("jev_start_ms",now).put("jev_done_ms",now).put("response_send_ms",now).put("clock_domain","guest-fixture")).toString();code=200;Thread.sleep(delay);
   }
   byte[] bytes=response.getBytes(StandardCharsets.UTF_8);s.getOutputStream().write(("HTTP/1.1 "+code+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));s.getOutputStream().write(bytes);s.getOutputStream().flush();
  }catch(SocketException cancelled){}catch(Exception e){if(!stop)error=e;}},"Guest670Live");server.start();
 }
 long typeWrong()throws Exception{for(String k:new String[]{"ㄉ","ㄨ","ㄛ","ˋ","ㄩ","ˊ"})tap(k);return lastPhysicalInjection;}
 boolean highlighted(){final boolean[] yes={false};inst.runOnMainSync(()->{CharSequence t=row1.getText();yes[0]=t instanceof Spanned&&((Spanned)t).getSpans(0,t.length(),BackgroundColorSpan.class).length>0;});return yes[0];}
 String diagnostic()throws Exception{final JSONObject[] state={new JSONObject()};inst.runOnMainSync(()->{try{
  android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();java.lang.reflect.Field sf=SimonIMEService.class.getDeclaredField("sentencePhone");sf.setAccessible(true);Object phone=sf.get(c);
  for(String name:new String[]{"compositionStart","compositionEnd","ownedText","keyWitness","request","failureCount","debounceEnd","receive","validation","phoneRender","underlineStart","underlineEnd"}){java.lang.reflect.Field f=AiSentencePhone.class.getDeclaredField(name);f.setAccessible(true);state[0].put(name,f.get(phone));}
  java.lang.reflect.Field ranks=AiSentencePhone.class.getDeclaredField("displayedRanks");ranks.setAccessible(true);java.util.Map<JSONObject,Integer> map=(java.util.Map<JSONObject,Integer>)ranks.get(phone);org.json.JSONArray mapState=new org.json.JSONArray();for(java.util.Map.Entry<JSONObject,Integer> en:map.entrySet())mapState.put(new JSONObject().put("text",en.getKey().optString("text")).put("rank",en.getValue()).put("still_visible_option",((AiSentencePhone)phone).rowOptions().contains(en.getKey())));state[0].put("displayed_rank_state",mapState);
  java.lang.reflect.Field f=AiSentencePhone.class.getDeclaredField("sentence");f.setAccessible(true);AiSentence sentence=(AiSentence)f.get(phone);state[0].put("mode",sentence.mode()).put("lastEdit",sentence.lastEdit).put("result",sentence.result).put("requests",requestCount);
 }catch(Exception e){throw new RuntimeException(e);}});return state[0].toString();}
 public void testLiveDiagnostic()throws Exception{begin();typeWrong();Thread.sleep(2600);save("diagnostic.json",diagnostic());}
 public void testLiveCorrection()throws Exception{
  begin();JSONArray times=new JSONArray();long[] ms=new long[3];String accumulated="";
  for(int trial=0;trial<3;trial++){
   long last=typeWrong();String original=shown();assertEquals("known original fixture","墮於",original);long until=last+2500;
   while(SystemClock.uptimeMillis()<until&&!highlighted())Thread.sleep(20);
   save("live-state-"+trial+".json",diagnostic());assertEquals("500ms server correction appears live","多餘",shown());assertTrue("changed glyph highlighted",highlighted());ms[trial]=SystemClock.uptimeMillis()-last;times.put(ms[trial]);
   String commit="多餘";
   if(trial==0){final Rect r=new Rect();inst.runOnMainSync(()->{android.text.Layout l=row1.getLayout();int[] at=new int[2];row1.getLocationOnScreen(at);int x=Math.round(at[0]+row1.getPaddingLeft()+(l.getPrimaryHorizontal(0)+l.getPrimaryHorizontal(1))/2),y=at[1]+row1.getTotalPaddingTop()+l.getLineBottom(0)/2;r.set(x-1,y-1,x+1,y+1);});tap(r);assertEquals("tap revert exact original",original,shown());commit=original;}
   tap("↵");accumulated+=commit;assertEquals("Enter commits shown",accumulated,String.valueOf(await("test_input").getText()));
  }
  java.util.Arrays.sort(ms);save("live-result.json",new JSONObject().put("last_key_to_highlight_ms",times).put("p50_ms",ms[1]).put("server_delay_ms",500).put("tap_revert",true).put("enter_shown",true).toString());assertTrue("p50 <=1500ms",ms[1]<=1500);assertNull(error);
 }
 public void testTimeoutUnchanged()throws Exception{delay=3500;begin();typeWrong();String original=shown();Thread.sleep(4200);assertEquals("timeout preserves shown text",original,shown());assertFalse("timeout no highlight",highlighted());save("timeout-result.json",new JSONObject().put("unchanged",true).toString());}
 public void testCaretDiscardsPending()throws Exception{begin();typeWrong();String original=shown();long until=SystemClock.uptimeMillis()+1800;while(requestCount==0&&SystemClock.uptimeMillis()<until)Thread.sleep(10);assertTrue("request actually sent",requestCount>0);tapBoundary(1);Thread.sleep(1200);assertEquals("caret touch invalidates response",original,shown());assertFalse(highlighted());save("stale-result.json",new JSONObject().put("caret_discard",true).toString());}
}
