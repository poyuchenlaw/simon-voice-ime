package com.simon.voiceime;

import android.widget.TextView;
import org.json.*;
import java.io.File;
import java.util.*;

/** Actual APK/window, physical taps only; reflection is limited to state readback. */
public class Z12AndroidTest extends TextRows670AndroidTest {
    boolean legacy;
    @Override void preparePreferences(){
        super.preparePreferences();
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit()
            .putInt("ai_auto_apply_migration_version",671).putBoolean("ai_sentence_auto_apply",false)
            .putString("layout_mode",legacy?"legacy_zhuyin":"text_word_char").commit();
    }
    void start()throws Exception {ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();}
    void keys(String keys)throws Exception {for(char ch:keys.toCharArray())tap(ch==' '?"空白":String.valueOf(ch));}
    JSONObject stateReadback()throws Exception {
        JSONObject[] result={null};inst.runOnMainSync(()->{try{
            ZhuyinInputController c=actualController();
            result[0]=new JSONObject().put("shown",row1.getText().toString()).put("raw",c.previewText())
                .put("keys",c.sentenceKeys()).put("readings",new JSONArray(c.phoneticSyllables()))
                .put("text_layout",c.textLayout()).put("reading_row_shown",row2.isShown());
        }catch(Exception e){throw new RuntimeException(e);}});return result[0];
    }
    public void testPhysicalWholeCharacterDeletion()throws Exception {
        start();JSONArray receipts=new JSONArray();
        for(String input:new String[]{"ㄍㄨㄥ","ㄍㄨㄥ ","ㄨㄛˇ","ㄒㄧㄢˋㄗㄞˋ"}){
            keys(input);JSONObject before=stateReadback();assertFalse(before.getBoolean("reading_row_shown"));
            if(input.equals("ㄍㄨㄥ "))assertEquals("工",before.getString("shown"));
            if(input.equals("ㄨㄛˇ"))assertEquals("我",before.getString("shown"));
            if(input.startsWith("ㄒ"))assertEquals("現在",before.getString("shown"));
            tap("⌫");JSONObject after=stateReadback();
            assertEquals(input.startsWith("ㄒ")?"現":"",after.getString("shown"));
            assertEquals(input.startsWith("ㄒ")?"ㄒㄧㄢˋ":"",after.getString("keys"));
            receipts.put(new JSONObject().put("input",input).put("before",before).put("after",after));
            if(input.startsWith("ㄒ")){tap("⌫");assertEquals("",shown());}
        }
        keys("ㄐㄧㄣ ㄊㄧㄢ ");tapBoundary(1);keys("ㄍㄨㄥ");JSONObject before=stateReadback();
        tap("⌫");JSONObject after=stateReadback();assertEquals("今天",shown());assertEquals(2,after.getJSONArray("readings").length());
        receipts.put(new JSONObject().put("case","caret_insert_delete").put("before",before).put("after",after));
        save("z1-deletions.json",receipts.toString());screenshot("z1-after-insertion-delete");
    }
    public void testCompositionEmptyPreservesCommittedText()throws Exception {
        start();keys("ㄐㄧㄣ ㄊㄧㄢ ");tap("↵");assertEquals("今天",String.valueOf(await("test_input").getText()));
        tap("⌫");assertEquals("今",String.valueOf(await("test_input").getText()));
        keys("ㄍㄨㄥ ");tap("⌫");assertEquals("",shown());
        assertEquals("empty composition must not consume preceding committed character","今",String.valueOf(await("test_input").getText()));
        keys("ㄨㄛˇ");String display=shown();tap("↵");assertEquals("今"+display,String.valueOf(await("test_input").getText()));
        save("z1-committed-text.json",new JSONObject().put("preserved","今").put("final",String.valueOf(await("test_input").getText())).put("no_phonetic_commit",true).toString());
    }
    public void testLegacyStillDeletesOnePhoneticKey()throws Exception {
        legacy=true;start();keys("ㄍㄨㄥ");JSONObject before=stateReadback();assertFalse(before.getBoolean("text_layout"));
        tap("⌫");JSONObject after=stateReadback();assertEquals("ㄍㄨ",after.getString("keys"));
        save("z1-legacy.json",new JSONObject().put("before",before).put("after",after).toString());
    }
    JSONArray readRows()throws Exception {return readRows(true);}
    JSONArray readRows(boolean wholeSentence)throws Exception {
        JSONArray[] rows={null};inst.runOnMainSync(()->{try{
            rows[0]=new JSONArray();for(int i=0;i<words.getChildCount();i++){
                TextView item=(TextView)words.getChildAt(i);Object tag=item.getTag();assertTrue("word row must only contain engine word choices",tag instanceof ZhuyinInputController.TextChoice);
                ZhuyinInputController.TextChoice c=(ZhuyinInputController.TextChoice)tag;assertEquals("word",c.kind);
                assertTrue("single characters belong in row three",c.label.codePointCount(0,c.label.length())>1);
                if(wholeSentence)assertFalse("whole original sentence must not occupy word row",c.label.equals(row1.getText().toString()));
                rows[0].put(new JSONObject().put("label",c.label).put("start",c.start).put("end",c.end).put("index",c.index));
            }
            assertTrue("third row remains available",characters.getChildCount()>0);
            for(int i=0;i<characters.getChildCount();i++)assertEquals("char",((ZhuyinInputController.TextChoice)characters.getChildAt(i).getTag()).kind);
        }catch(Exception e){throw new RuntimeException(e);}});return rows[0];
    }
    public void testSimonLongReplayWordRow()throws Exception {
        start();JSONArray events;
        try(java.io.InputStream in=inst.getContext().getAssets().open("z12-long-keys.json")){events=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        for(int i=0;i<events.length();i++){String key=events.getJSONObject(i).getString("key");tap("space".equals(key)?"空白":key);}
        Thread.sleep(500);JSONArray row=readRows();
        save("z2-long-replay.json",new JSONObject().put("key_count",events.length()).put("state",stateReadback()).put("words",row).put("chars",characters.getChildCount()).toString());
        screenshot("z2-long-replay-screen");captureStructure("z2-structure.json");
        assertTrue("five word alternatives required",row.length()>=5);
        String preview=shown();tap("↵");assertEquals("commit exactly matches text preview",preview,String.valueOf(await("test_input").getText()));
        assertFalse("no phonetic in commit",preview.codePoints().anyMatch(x->x>=0x3105&&x<=0x312f||"ˊˇˋ˙ˉ".indexOf(x)>=0));
    }
    AiSentencePhone phone(){
        try{android.content.Context ctx=row1.getContext();while(!(ctx instanceof SimonIMEService)&&ctx instanceof android.content.ContextWrapper)ctx=((android.content.ContextWrapper)ctx).getBaseContext();
            java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField("sentencePhone");f.setAccessible(true);return (AiSentencePhone)f.get(ctx);
        }catch(Exception e){throw new RuntimeException(e);}
    }
    public void testAiSuggestionOffWordRow()throws Exception {
        start();endpoint=new java.net.ServerSocket(8181,4,java.net.InetAddress.getByName("127.0.0.1"));
        inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("ai_sentence_mode","suggestions").commit();
        final JSONObject contract;try(java.io.InputStream asset=inst.getTargetContext().getAssets().open("sentence-contract.json")){contract=new JSONObject(new String(asset.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        java.util.List<String> cancelledSockets=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        server=new Thread(()->{while(!stop)try(java.net.Socket socket=endpoint.accept()){
            socket.setSoTimeout(4000);java.io.InputStream input=new java.io.BufferedInputStream(socket.getInputStream());String first=line(input);int size=0;
            for(String h;!(h=line(input)).isEmpty();)if(h.toLowerCase().startsWith("content-length:"))size=Integer.parseInt(h.substring(15).trim());
            String body=new String(input.readNBytes(size),java.nio.charset.StandardCharsets.UTF_8),response="{}";int code=404;
            if(first.startsWith("POST /v1/ime/sentence-candidates ")){
                JSONObject req=new JSONObject(body);String literal=req.getString("literal"),candidate=literal.endsWith("計畫")?literal.substring(0,literal.length()-2)+"計劃":literal.endsWith("計劃")?literal.substring(0,literal.length()-2)+"計畫":null;
                long now=android.os.SystemClock.elapsedRealtime();JSONArray options=new JSONArray();if(candidate!=null)options.put(new JSONObject().put("id","c1").put("text",candidate).put("repairs",new JSONArray()));
                JSONObject result=new JSONObject().put("kind","response").put("schema_version",1).put("request_id",req.getString("request_id"))
                    .put("editor_generation",req.getLong("editor_generation")).put("composition_generation",req.getLong("composition_generation")).put("mode","suggestions")
                    .put("keep",new JSONObject().put("id","keep").put("text",literal).put("repairs",new JSONArray())).put("candidates",options)
                    .put("decision",new JSONObject().put("display",candidate==null?"none":"chip").put("selected_id",candidate==null?"keep":"c1").put("jev_status","ok").put("choice_confidence",.95).put("meaning_probability",.95).put("reason","ready"))
                    .put("gemini_model","sandbox-fixture").put("jev_model","sandbox-fixture").put("policy_version","sentence-r2-v1")
                    .put("server_times",new JSONObject().put("server_receive_ms",now).put("gemini_done_ms",now).put("jev_start_ms",now).put("jev_done_ms",now).put("response_send_ms",now).put("clock_domain","guest-fixture"));
                AiSentence.response(contract,req,result.toString(),(reading,text)->true);
                if(candidate!=null){save("z2-ai-request.json",req.toString());save("z2-ai-response.json",result.toString());}response=result.toString();code=200;
            }
            byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);socket.getOutputStream().write(("HTTP/1.1 "+code+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
        }catch(java.net.SocketException e){if(!stop)cancelledSockets.add(e.toString());}catch(Exception e){if(!stop)error=e;}},"Z12GuestAiFixture");server.start();
        try{
            keys("ㄐㄧˋㄏㄨㄚˋ");String original=shown();
            final int[] options={0};long end=android.os.SystemClock.uptimeMillis()+3500;
            while(android.os.SystemClock.uptimeMillis()<end){inst.runOnMainSync(()->{options[0]=0;for(JSONObject option:phone().rowOptions())if("ai".equals(option.optString("source")))options[0]++;});if(options[0]>0)break;Thread.sleep(50);}
            final JSONObject[] phoneState={null};inst.runOnMainSync(()->{try{
                java.lang.reflect.Field field=AiSentencePhone.class.getDeclaredField("sentence");field.setAccessible(true);AiSentence actual=(AiSentence)field.get(phone());
                phoneState[0]=new JSONObject().put("result",actual.result==null?JSONObject.NULL:actual.result).put("row_options",new JSONArray(phone().rowOptions()));
            }catch(Exception e){throw new RuntimeException(e);}});
            save("z2-ai-phone-state.json",phoneState[0].put("state",stateReadback()).toString());
            assertTrue("fixture must produce a real validated AI row option",options[0]>0);
            // The two-character fixture is itself a dictionary word, unlike the long-sentence literal.
            JSONArray rows=readRows(false);assertEquals("preview stays original until Z3",original,shown());
            save("z2-ai-hidden.json",new JSONObject().put("validated_row_options",options[0]).put("words",rows).put("original_preview",original).put("shown",shown()).toString());screenshot("z2-ai-hidden-screen");
        }finally{stop=true;endpoint.close();server.join(5000);save("z2-fixture-cancelled-sockets.json",new JSONArray(cancelledSockets).toString());if(error!=null)throw new AssertionError(error);}
    }

}
