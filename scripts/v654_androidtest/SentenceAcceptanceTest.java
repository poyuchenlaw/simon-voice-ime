package com.ime.sandbox.testpad;
import android.app.*;
import android.os.*;
import android.graphics.Rect;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import org.junit.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Fake endpoint runs INSIDE the isolated Android guest; no provider or host relay. */
public class SentenceAcceptanceTest extends RegroupAcceptanceTest {
 ServerSocket endpoint;Thread server;volatile boolean stop;volatile String disposition="chip";volatile int status=200;volatile int delay;
 AtomicInteger requests=new AtomicInteger(),ready=new AtomicInteger();volatile String serverFailure;JSONObject lastRequest;
 @Override @Ignore @Test public void hintOnce(){}
 @Override @Ignore @Test public void realTapsDragRegroupCommitAndRetype(){}
 @Before public void init()throws Exception{
  inst=InstrumentationRegistry.getInstrumentation();ui=inst.getUiAutomation();device=UiDevice.getInstance(inst);
  AccessibilityServiceInfo flags=ui.getServiceInfo();flags.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(flags);
  out=new File(inst.getTargetContext().getFilesDir(),"v650");out.mkdirs();
  endpoint=new ServerSocket(8181,4,InetAddress.getByName("127.0.0.1"));
  server=new Thread(()->{while(!stop)try(Socket socket=endpoint.accept()){
   socket.setSoTimeout(4000);BufferedInputStream in=new BufferedInputStream(socket.getInputStream());String first=line(in);int length=0;
   for(String header;(header=line(in))!=null&&!header.isEmpty();)if(header.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(header.substring(15).trim());
   byte[] body=new byte[length];int n=0;while(n<length){int read=in.read(body,n,length-n);if(read<0)break;n+=read;}
   if(first==null||!first.startsWith("POST /v1/ime/sentence-candidates ")){write(socket,404,"{}");continue;}
   JSONObject req=new JSONObject(new String(body,0,n,StandardCharsets.UTF_8));lastRequest=req;requests.incrementAndGet();
   JSONArray keys=req.getJSONArray("key_slots");boolean complete=keys.length()==6&&keys.getString(0).equals("ㄉ")&&keys.getString(3).equals("ˋ");
   if(!complete){write(socket,404,"{}");continue;}ready.incrementAndGet();if(delay>0)Thread.sleep(delay);
   long now=SystemClock.elapsedRealtime();JSONObject decision=new JSONObject().put("display",disposition.equals("chip")?"chip":"bubble")
    .put("selected_id",disposition.equals("chip")?"c1":"none").put("jev_status",disposition.equals("chip")?"ok":"unavailable")
    .put("choice_confidence",disposition.equals("chip")?.95:JSONObject.NULL).put("meaning_probability",disposition.equals("chip")?.95:JSONObject.NULL)
    .put("reason",disposition.equals("chip")?"ready":"jev_unavailable");
   JSONObject response=new JSONObject().put("kind","response").put("schema_version",1).put("request_id",req.getString("request_id"))
    .put("editor_generation",req.getLong("editor_generation")).put("composition_generation",req.getLong("composition_generation")).put("mode","suggestions")
    .put("keep",new JSONObject().put("id","keep").put("text",req.getString("literal")).put("repairs",new JSONArray()))
    .put("candidates",new JSONArray().put(new JSONObject().put("id","c1").put("text","多餘").put("repairs",new JSONArray().put(new JSONObject()
     .put("operation","replace").put("key_slot",3).put("source_symbol","ˋ").put("target_symbol"," ")))))
    .put("decision",decision).put("gemini_model","sandbox-fixture").put("jev_model",disposition.equals("chip")?"sandbox-fixture":JSONObject.NULL).put("policy_version","sentence-r2-v1")
    .put("server_times",new JSONObject().put("server_receive_ms",now).put("gemini_done_ms",now).put("jev_start_ms",now).put("jev_done_ms",disposition.equals("chip")?now:JSONObject.NULL).put("response_send_ms",now).put("clock_domain","guest-fixture"));
   write(socket,status,response.toString());
  }catch(Exception e){if(!stop)serverFailure=e.toString();}},"GuestSentenceEndpoint");server.start();
 }
 String line(InputStream in)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(int c;(c=in.read())!=-1;){if(c=='\n')break;if(c!='\r')bytes.write(c);}return bytes.toString("US-ASCII");}
 void write(Socket socket,int code,String text)throws Exception{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+code+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();}
 @After public void finish()throws Exception{stop=true;if(endpoint!=null)endpoint.close();if(server!=null)server.join(5000);}
 void configure(String mode)throws Exception{
  String uid=device.executeShellCommand("su 0 stat -c %u /data/data/com.simon.voiceime").trim();assertTrue("guest root UID read",uid.matches("[0-9]+"));
  String xml="<?xml version=\"1.0\" encoding=\"utf-8\"?><map><string name=\"server_url\">http://127.0.0.1:8181</string><string name=\"auth_password\">sandbox</string><string name=\"ai_sentence_mode\">"+mode+"</string><boolean name=\"ime_auto_upload\" value=\"true\"/><boolean name=\"auto_vocab_enabled\" value=\"false\"/></map>";
  String encoded=android.util.Base64.encodeToString(xml.getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP),path="/data/data/com.simon.voiceime/shared_prefs/simon_ime_prefs.xml";
  File script=new File(inst.getTargetContext().getFilesDir(),"sentence-config.sh");
  String ime="com.simon.voiceime/.SimonIMEService";
  String commands="ime disable "+ime+"\nam force-stop com.simon.voiceime\nmkdir -p /data/data/com.simon.voiceime/shared_prefs\necho "+encoded+" | base64 -d > "+path+"\nchown "+uid+":"+uid+" "+path+"\nchmod 660 "+path+"\nime enable "+ime+"\nime set "+ime+"\n";
  try(FileOutputStream stream=new FileOutputStream(script)){stream.write(commands.getBytes(StandardCharsets.UTF_8));}
  device.executeShellCommand("su 0 sh "+script.getAbsolutePath());
  String readback=device.executeShellCommand("su 0 cat "+path);
  assertTrue("fake endpoint setting must be read back before typing",readback.contains("http://127.0.0.1:8181"));
  assertTrue("client mode must be read back before typing",readback.contains(mode));
  ui.waitForIdle(500,10000);launch();
 }
 @Override void launch()throws Exception{
  String ime="com.simon.voiceime/.SimonIMEService";
  for(int retry=0;retry<10;retry++){device.executeShellCommand("ime enable "+ime);device.executeShellCommand("ime set "+ime);Thread.sleep(500);if(ime.equals(device.executeShellCommand("settings get secure default_input_method").trim()))break;}
  assertEquals(ime,device.executeShellCommand("settings get secure default_input_method").trim());
  android.content.Intent intent=new android.content.Intent(inst.getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
  Activity activity=inst.startActivitySync(intent);inst.runOnMainSync(()->{android.view.View editor=activity.findViewById(0x1001);editor.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(editor,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});Thread.sleep(1000);ui.waitForIdle(500,10000);AccessibilityNodeInfo field=null;long deadline=SystemClock.uptimeMillis()+45000;
  while(field==null&&SystemClock.uptimeMillis()<deadline){for(AccessibilityWindowInfo w:ui.getWindows()){field=input(w.getRoot());if(field!=null)break;}if(field==null)Thread.sleep(100);}
  assertNotNull(field);field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);field.performAction(AccessibilityNodeInfo.ACTION_CLICK);ui.waitForIdle(500,10000);
  while(SystemClock.uptimeMillis()<deadline){if(node("ㄗ",null)!=null){ui.waitForIdle(100,10000);return;}if(node("注",null)!=null){tap("注");ui.waitForIdle(500,10000);}else {inst.runOnMainSync(()->{android.view.View editor=activity.findViewById(0x1001);editor.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(editor,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});Thread.sleep(500);}}
  throw new AssertionError("configured IME did not show the Zhuyin page");
 }
 void typeTone()throws Exception{for(String key:new String[]{"ㄉ","ㄨ","ㄛ","ˋ","ㄩ","ˊ"}){
  AccessibilityNodeInfo n=await(key,null);assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(70);
 }ui.waitForIdle(50,2000);}
 AccessibilityNodeInfo ai(AccessibilityNodeInfo node){if(node==null)return null;if(String.valueOf(node.getContentDescription()).equals("AI 選項 多餘")&&node.isVisibleToUser())return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo result=ai(node.getChild(i));if(result!=null)return result;}return null;}
 AccessibilityNodeInfo ai(){for(AccessibilityWindowInfo w:ui.getWindows()){AccessibilityNodeInfo n=ai(w.getRoot());if(n!=null)return n;}return null;}
 AccessibilityNodeInfo waitAi()throws Exception{long until=SystemClock.uptimeMillis()+1600;while(SystemClock.uptimeMillis()<until){AccessibilityNodeInfo n=ai();if(n!=null)return n;Thread.sleep(20);}screenshot("ai-missing");throw new AssertionError("AI chip/bubble absent; requests="+requests+" complete="+ready+" server="+serverFailure);}
 void click(AccessibilityNodeInfo node)throws Exception{assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(100);}
 @Override void tap(String text)throws Exception{click(await(text,null));}
 String preview()throws Exception{return await(null,"com.simon.voiceime:id/boStreamPreview").getText().toString();}
 void save(String file,JSONObject result)throws Exception{result.put("apk_sha256",InstrumentationRegistry.getArguments().getString("apk_sha256"));try(FileOutputStream stream=new FileOutputStream(new File(out,file+".json"))){stream.write(result.toString(2).getBytes(StandardCharsets.UTF_8));}}
 @Test public void shadowNeverChangesScreenOrField()throws Exception{
  configure("shadow");typeTone();String before=preview(),fieldBefore=readField();Thread.sleep(850);
  assertTrue("fake request dispatched",ready.get()>0);assertNull(ai());assertEquals(before,preview());assertEquals(fieldBefore,readField());screenshot("shadow-unchanged");save("shadow",new JSONObject().put("literal",before).put("field",readField()).put("requests",ready.get()).put("verdict","PASS"));
 }
 @Test public void suggestionsApplyUndoAndReadBack()throws Exception{
  configure("suggestions");typeTone();String before=preview(),fieldBefore=readField(),readingBefore=await(null,"com.simon.voiceime:id/boPhoneticPreview").getText().toString();
  AccessibilityNodeInfo option=waitAi();assertNotNull("healthy choice must occupy candidate row",ai(node(null,"com.simon.voiceime:id/boCandidateItems")));assertEquals(before,preview());click(option);assertEquals("多餘",preview());assertEquals("多餘",readField());screenshot("applied");
  tap("復原");assertEquals(before,preview());assertEquals(fieldBefore,readField());assertEquals(readingBefore,await(null,"com.simon.voiceime:id/boPhoneticPreview").getText().toString());screenshot("undone");
  tap("↵");assertEquals(before,readField());save("apply-undo",new JSONObject().put("restored",before).put("restored_reading",readingBefore).put("field",readField()).put("verdict","PASS"));
 }
 @Test public void degradedOnlyBubbleAndExplicitTap()throws Exception{
  disposition="bubble";configure("suggestions");typeTone();String before=preview(),fieldBefore=readField();AccessibilityNodeInfo option=waitAi();
  assertTrue(option.getText().toString().startsWith("AI・未排序"));assertNull("unranked candidates stay out of the row",ai(node(null,"com.simon.voiceime:id/boCandidateItems")));assertEquals(before,preview());click(option);assertEquals("多餘",readField());tap("復原");assertEquals(fieldBefore,readField());screenshot("degraded-restored");save("degraded",new JSONObject().put("verdict","PASS"));
 }
 @Test public void missingEndpointLeavesTypingUntouched()throws Exception{
  status=404;configure("suggestions");typeTone();String before=preview(),fieldBefore=readField();Thread.sleep(1100);assertTrue(ready.get()>0);assertNull(ai());assertEquals(fieldBefore,readField());tap("↵");assertEquals(before,readField());save("http404",new JSONObject().put("verdict","PASS").put("field",readField()));
 }
 @Test public void timeoutLeavesTypingUntouched()throws Exception{
  delay=1300;configure("suggestions");typeTone();String before=preview(),fieldBefore=readField();Thread.sleep(1600);assertTrue(ready.get()>0);assertNull(ai());assertEquals(fieldBefore,readField());tap("↵");assertEquals(before,readField());save("timeout",new JSONObject().put("verdict","PASS").put("field",readField()));
 }
}
