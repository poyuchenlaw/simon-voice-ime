package com.simon.voiceime;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;import android.os.SystemClock;import android.graphics.Rect;import org.json.*;
/** Official auto-v2 client seam: physical keys + native map; server authority is scripted. */
public class TrustedChip673AndroidTest extends Phone673AndroidTest {
 volatile boolean legacyClientAuto;boolean legal,serverOff;String prefix="今天".repeat(4);
 @Override void startEndpoint()throws Exception{
  endpoint=new ServerSocket(8181,4,InetAddress.getByName("127.0.0.1"));
  server=new Thread(()->{while(!stop)try(Socket socket=endpoint.accept()){
   socket.setSoTimeout(4000);InputStream in=new BufferedInputStream(socket.getInputStream());String first=line(in);int size=0;for(String h;!(h=line(in)).isEmpty();)if(h.toLowerCase().startsWith("content-length:"))size=Integer.parseInt(h.substring(15).trim());
   JSONObject req=new JSONObject(new String(in.readNBytes(size),StandardCharsets.UTF_8));assertTrue(first.startsWith("POST /v1/ime/sentence-candidates "));legacyClientAuto=req.getBoolean("client_auto");
   save("trusted-chip-request-"+(++requestCount)+".json",req.toString());
   JSONArray repairs=new JSONArray();repairs.put(new JSONObject().put("operation","replace").put("key_slot",legal?37:35).put("source_symbol","ˋ").put("target_symbol",legal?"ˇ":" "));
   String target=prefix+(legal?"原稿":"多餘");long now=SystemClock.elapsedRealtime();
   JSONObject response=new JSONObject().put("kind","response").put("schema_version",1).put("request_id",req.getString("request_id")).put("editor_generation",req.getLong("editor_generation")).put("composition_generation",req.getLong("composition_generation")).put("mode","suggestions")
     .put("keep",new JSONObject().put("id","keep").put("text",req.getString("literal")).put("repairs",new JSONArray()))
     .put("candidates",new JSONArray().put(new JSONObject().put("id","c1").put("text",target).put("repairs",repairs).put("protected",false).put("protected_categories",new JSONArray())))
     .put("decision",new JSONObject().put("display",serverOff?"bubble":"auto").put("selected_id","c1").put("jev_status","not_called").put("choice_confidence",.99).put("meaning_probability",JSONObject.NULL).put("reason","ready"))
     .put("gemini_model","sandbox-fixture").put("jev_model",JSONObject.NULL).put("policy_version","sentence-auto-v2").put("digest",AiSentence.digest(req))
     .put("server_times",new JSONObject().put("server_receive_ms",now).put("gemini_done_ms",now).put("jev_start_ms",now).put("jev_done_ms",now).put("response_send_ms",now).put("clock_domain","guest-fixture"));
   save("trusted-chip-response.json",response.toString());Thread.sleep(delay);byte[] body=response.toString().getBytes(StandardCharsets.UTF_8);
   socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);socket.getOutputStream().flush();
  }catch(SocketException cancelled){}catch(Exception failure){if(!stop)error=failure;}},"TrustedChip673Fixture");server.start();
 }
 private String wrongLong()throws Exception{for(int n=0;n<4;n++)typeToday();typeWrong();String original=shown();assertEquals("literal known from independent native baseline",prefix+"墮於",original);return original;}
 private void awaitCorrected()throws Exception{long end=SystemClock.uptimeMillis()+3000;while(SystemClock.uptimeMillis()<end&&!highlighted())Thread.sleep(20);save("trusted-chip-state.json",diagnostic());assertEquals(prefix+"多餘",shown());assertTrue("actual changed range marked",highlighted());assertTrue("opt-in requests official auto-v2",legacyClientAuto);assertNull(error);}
 public void testTrustedChipActuallyChangesThenEnterStoresText()throws Exception{begin();wrongLong();awaitCorrected();tap("↵");assertEquals(prefix+"多餘",String.valueOf(await("test_input").getText()));save("trusted-chip-commit.json",new JSONObject().put("scripted_judge",true).put("real_native_map",true).put("actual_enter_commit",true).toString());}
 public void testTrustedChipSingleTapRestoresOriginalKeys()throws Exception{
  begin();String original=wrongLong();final String[] originalKeys={null};inst.runOnMainSync(()->originalKeys[0]=actualController().sentenceKeys());awaitCorrected();Rect r=new Rect();
  inst.runOnMainSync(()->{android.text.Layout layout=row1.getLayout();int[] at=new int[2];row1.getLocationOnScreen(at);int offset=prefix.length(),line=layout.getLineForOffset(offset);int x=Math.round(at[0]+row1.getTotalPaddingLeft()+(layout.getPrimaryHorizontal(offset)+layout.getPrimaryHorizontal(offset+1))/2-row1.getScrollX());int y=at[1]+row1.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2-row1.getScrollY();r.set(x-1,y-1,x+1,y+1);});
  tap(r);assertEquals(original,shown());assertFalse(highlighted());inst.runOnMainSync(()->assertEquals(originalKeys[0],actualController().sentenceKeys()));tap("↵");assertEquals(original,String.valueOf(await("test_input").getText()));save("trusted-chip-undo.json",new JSONObject().put("one_physical_tap",true).put("original_keys_restored",true).put("original_committed",true).toString());
 }
 public void testTrustedChipCannotChangeProtectedLegalTerm()throws Exception{
  legal=true;begin();for(int n=0;n<4;n++)typeToday();for(String key:new String[]{"ㄩ","ㄢ","ˊ","ㄍ","ㄠ","ˋ"})tap(key);String original=shown();assertEquals(prefix+"原告",original);Thread.sleep(2600);
  JSONObject state=new JSONObject(diagnostic());save("trusted-chip-legal.json",state.toString());assertTrue("legal challenge actually submitted",requestCount>0);assertNotNull("valid response/native map reached guard",state.optJSONObject("result"));assertEquals("trusted response validated","auto",state.getJSONObject("result").getJSONObject("decision").getString("display"));assertEquals(1,state.getJSONObject("result").getJSONArray("candidates").length());assertEquals(original,shown());assertFalse(highlighted());tap("↵");assertEquals(original,String.valueOf(await("test_input").getText()));assertNull(error);
 }
 public void testTrustedChipLateReplyCannotOverwriteNewKeys()throws Exception{
  delay=900;begin();wrongLong();long deadline=SystemClock.uptimeMillis()+2000;while(requestCount==0&&SystemClock.uptimeMillis()<deadline)Thread.sleep(10);assertTrue(requestCount>0);
  for(String key:new String[]{"ㄨ","ㄛ","ˇ"})tap(key);String latest=shown();Thread.sleep(2800);assertEquals("obsolete trusted response keeps latest physical keys",latest,shown());assertFalse(highlighted());tap("↵");assertEquals(latest,String.valueOf(await("test_input").getText()));save("trusted-chip-obsolete.json",new JSONObject().put("scripted_judge",true).put("old_response_can_fit_original_deadline",true).put("new_keys_preserved",true).toString());
 }
 public void testServerOffRetainsLiteralAndClickableSuggestion()throws Exception{
  serverOff=true;begin();String original=wrongLong();Thread.sleep(2400);
  JSONObject state=new JSONObject(diagnostic());save("auto-v2-server-off-state.json",state.toString());
  assertTrue(legacyClientAuto);assertNull(error);assertEquals(original,shown());assertFalse(highlighted());
  assertEquals("bubble",state.getJSONObject("result").getJSONObject("decision").getString("display"));
  android.widget.TextView suggestion=null;
  for(int i=0;i<words.getChildCount();i++){
   android.widget.TextView item=(android.widget.TextView)words.getChildAt(i);
   if("多餘".equals(item.getText().toString())){suggestion=item;break;}
  }
  assertNotNull("server-off correction stays available as a visible suggestion",suggestion);
  assertEquals("actual server candidate identity","sentence-option",suggestion.getTag());
  assertEquals("actual server candidate content description","AI 選項 多餘",String.valueOf(suggestion.getContentDescription()));
  revealIndex(words,wordScroll,words.indexOfChild(suggestion));Rect bounds=new Rect();final android.widget.TextView selected=suggestion;
  inst.runOnMainSync(()->{selected.getGlobalVisibleRect(bounds);int[] root=new int[2];selected.getRootView().getLocationOnScreen(root);bounds.offset(root[0],root[1]);});tap(bounds);
  assertEquals("explicit user tap may accept the suggestion",prefix+"多餘",String.valueOf(await("test_input").getText()));
 }
 public void testCanonicalDigestMatchesOfficialPublicUtf8()throws Exception{
  inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation();
  out=new File(inst.getTargetContext().getFilesDir(),"v666");assertTrue("canonical evidence directory available",out.isDirectory()||out.mkdirs());
  JSONObject req=AiSentence.request(new JSONObject(new String(inst.getTargetContext().getAssets().open("sentence-contract.json").readAllBytes(),StandardCharsets.UTF_8)),"public-canonical",1,7,"ㄉㄨㄛˋㄩˊ","舵餘","前\u2028後</段",new JSONArray(),new JSONArray());
  String actual=AiSentence.digest(req);
  assertEquals("233c417a24f038f036df10ce4c200aa01a2e1c08163e6a43b1f33439afaae041",actual);
  save("canonical-public-digest.json",new JSONObject().put("request",req).put("digest",actual).put("runtime","android-org-json").toString());
 }

}
