package com.simon.voiceime;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import android.os.SystemClock;import android.graphics.Rect;import android.text.Spanned;import android.text.style.BackgroundColorSpan;import org.json.*;
public class ProtectedRouteAndroidTest extends TextRows670AndroidTest {
 String target=""; long lastPhysicalInjection;
 @Override void tap(Rect r){long injected=SystemClock.uptimeMillis();super.tap(r);lastPhysicalInjection=injected;}
 volatile int requestCount;int delay=500;
 @Override void ready()throws Exception{super.ready();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("ai_sentence_mode","live").putBoolean("auto_correction",true).putBoolean("ai_sentence_auto_apply",true).commit();}
 @Override void startEndpoint()throws Exception{
  endpoint=new ServerSocket(8181,4,InetAddress.getByName("127.0.0.1"));
  server=new Thread(()->{while(!stop)try(Socket s=endpoint.accept()){
   s.setSoTimeout(4000);InputStream in=new BufferedInputStream(s.getInputStream());String first=line(in);int size=0;for(String h;!(h=line(in)).isEmpty();)if(h.toLowerCase().startsWith("content-length:"))size=Integer.parseInt(h.substring(15).trim());
   byte[] body=in.readNBytes(size);String response="{}";int code=404;
   if(first.startsWith("POST /v1/ime/sentence-candidates ")){
    JSONObject req=new JSONObject(new String(body,StandardCharsets.UTF_8));save("live-request-"+(++requestCount)+".json",req.toString());long now=SystemClock.elapsedRealtime();
    String literal=req.getString("literal");boolean match=!target.isEmpty()||literal.contains("曉明")||literal.contains("小明");if(target.isEmpty()&&match)target=literal.contains("曉明")?literal.replace("曉明","小明"):literal.replace("小明","曉明");
    JSONArray candidates=new JSONArray();if(match)candidates.put(new JSONObject().put("id","c1").put("protected",false).put("text",target).put("repairs",new JSONArray()));
    response=new JSONObject().put("name_protection_ready",true).put("kind","response").put("schema_version",1).put("request_id",req.getString("request_id")).put("editor_generation",req.getLong("editor_generation")).put("composition_generation",req.getLong("composition_generation")).put("mode","suggestions")
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
  java.lang.reflect.Field f=AiSentencePhone.class.getDeclaredField("sentence");f.setAccessible(true);AiSentence sentence=(AiSentence)f.get(phone);state[0].put("mode",sentence.mode()).put("lastEdit",sentence.lastEdit).put("result",sentence.result).put("requests",requestCount);
 }catch(Exception e){throw new RuntimeException(e);}});return state[0].toString();}

 void assertRejected(String label,String phonetic,String replacement)throws Exception{
  target=replacement;begin();if(label.equals("english_in_same_field")){shell("input text ABC");Thread.sleep(150);assertTrue("existing English in editor",String.valueOf(await("test_input").getText()).contains("ABC"));}for(int cp:phonetic.codePoints().toArray())tap(cp==' '?"空白":new String(Character.toChars(cp)));String before=shown();String editorBefore=String.valueOf(await("test_input").getText());Thread.sleep(2000);String after=shown();String editorAfter=String.valueOf(await("test_input").getText());save("protected-result.json",new JSONObject().put("kind",label).put("before",before).put("proposed",target).put("after",after).put("editor_before",editorBefore).put("editor_after",editorAfter).put("requests",requestCount).put("highlight",highlighted()).put("diagnostic",new JSONObject(diagnostic())).toString());
  assertTrue("AI response actually requested",requestCount>0);assertTrue("valid response reached gate",new JSONObject(diagnostic()).getLong("validation")>=0);assertFalse("fixture changes text",before.equals(target));assertEquals("phone rejects protected "+label,before,after);assertEquals("editor contents unchanged",editorBefore,editorAfter);assertFalse("rejected result has no highlight",highlighted());assertNull(error);
 }
 public void testUnregisteredName()throws Exception{assertRejected("unregistered_name","ㄨㄤˊㄒㄧㄠˇㄇㄧㄥˊ","");}
 public void testNumber()throws Exception{assertRejected("chinese_number","ㄧ ","衣");}
 public void testNegation()throws Exception{assertRejected("negation","ㄅㄨˋ", "部");}
 public void testEnglish()throws Exception{assertRejected("english_in_same_field","ㄐㄧㄣ ㄊㄧㄢ ","ABD今天");}
}
