package com.simon.voiceime;

import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real native session + exact production AiSentencePhone.project method body. */
public class AiWordSpanCompositionTest {
    @Test public void projectedLongClauseTapPreviewsOnlyChangedWordThenEnterCommits() throws Exception {
        String assets=System.getProperty("r5.assets"), user=System.getProperty("r5.user");
        try (RimeZhuyinEngine engine=new RimeZhuyinEngine(assets,user)) {
            ZhuyinInputController c=new ZhuyinInputController(engine);
            // RED deliberately reproduces r4's missing service initialization.
            if(Boolean.getBoolean("r5.factory"))
                c.setRetypeEngineFactory(()->new RimeZhuyinEngine(assets,user));
            String keys="ㄇㄧㄥˊㄒㄧㄢˇㄙㄨㄟˇㄧˇㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ";
            for(int i=0;i<keys.length();i++)c.press(keys.charAt(i)==' '?"space":keys.substring(i,i+1));
            assertEquals("明顯髓已不要冤枉你",c.previewText());
            JSONObject schema=new JSONObject(Files.readString(Path.of(System.getProperty("r5.contract"))));
            JSONObject req=AiSentence.request(schema,"r5-fixture",0,0,keys,c.previewText(),"",new JSONArray(),new JSONArray());
            JSONObject replacement=new JSONObject().put("id","r5-word").put("text","明顯所以不要冤枉你").put("protected",false)
                .put("repairs",new JSONArray().put(new JSONObject().put("key_slot",10).put("operation","replace").put("source_symbol","ㄟ").put("target_symbol","ㄛ")));
            AiSentencePhoneProjectionHarness phone=new AiSentencePhoneProjectionHarness(c,req);
            List<JSONObject> row=phone.project(replacement,false);
            assertEquals(1,row.size());JSONObject wrapper=row.get(0);
            assertEquals("所以",wrapper.getString("text"));
            assertEquals("ai",wrapper.getString("source"));
            assertEquals(c.previewText(),wrapper.getString("preview"));
            assertEquals(2,wrapper.getInt("start"));assertEquals(4,wrapper.getInt("end"));
            assertNotEquals(c.previewText(),wrapper.getString("text"));
            assertTrue(phone.project(new JSONObject().put("id","keep").put("text",c.previewText()).put("repairs",new JSONArray()),false).isEmpty());
            AiComposition tx=new AiComposition(c);
            ZhuyinInputController fixed=tx.apply(wrapper.getString("keys"),AiSentence.replaceSpan(c.previewText(),wrapper));
            assertNotNull("production word-span transaction must have a retype factory",fixed);
            assertEquals("original preview untouched until install","明顯髓已不要冤枉你",c.previewText());
            assertEquals("明顯所以不要冤枉你",fixed.previewText());
            assertEquals("tap updates composing preview, no commit","",fixed.state().commitText);
            fixed.state();assertEquals("明顯所以不要冤枉你",fixed.press("enter").commitText);
            fixed.close();tx.discard();
        }
    }

    private ZhuyinInputController typed(String keys) throws Exception {
        String assets=System.getProperty("r5.assets"),user=System.getProperty("r5.user");
        ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(assets,user));
        c.setRetypeEngineFactory(()->new RimeZhuyinEngine(assets,user));c.setLearningEnabled(false);
        for(int i=0;i<keys.length();i++)c.press(keys.charAt(i)==' '?"space":keys.substring(i,i+1));
        return c;
    }
    private List<JSONObject> local(ZhuyinInputController c,String keys,String text) throws Exception {
        return new AiSentencePhoneProjectionHarness(c,null).project(new JSONObject().put("id","local").put("keys",keys).put("text",text),true);
    }
    @Test public void singleWordCoveringWholePreviewIsFirst() throws Exception {
        ZhuyinInputController c=typed("ㄒㄧㄦˇ");try {
            ZhuyinInputController restored=c.preparedSentence("ㄒㄧㄦˇ","洗耳");c.close();c=restored;assertNotNull(c);
            String[] repair={"ㄒㄧㄢˇ","顯"};
            List<JSONObject> row=local(c,repair[0],repair[1]);assertEquals(1,row.size());assertEquals("顯",row.get(0).getString("text"));
            assertEquals(-1,(int)AiSentence.rowOrder(c.previewText(),false,c.state().candidates,Collections.emptyList(),List.of("顯"),c.rowWordLimit()).get(0));
        } finally { c.close(); }
    }
    @Test public void correctedSingleWordSuoYiIsOffered() throws Exception {
        ZhuyinInputController c=typed("ㄙㄨㄟˇㄧˇ");try {
            ZhuyinInputController restored=c.preparedSentence("ㄙㄨㄟˇㄧˇ","髓以");c.close();c=restored;assertNotNull(c);List<JSONObject> row=local(c,"ㄙㄨㄛˇㄧˇ","所以");
            assertEquals(1,row.size());assertEquals("所以",row.get(0).getString("text"));
        } finally { c.close(); }
    }
    @Test public void nineCharacterMultiWordReplacementIsHidden() throws Exception {
        ZhuyinInputController c=typed("ㄇㄧㄥˊㄒㄧㄢˇㄙㄨㄟˇㄧˇㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ");try {
            assertTrue(local(c,"ㄐㄧㄣ ㄊㄧㄢ ㄗㄞˋㄘˋㄊㄧˊㄐㄧㄠ ㄨㄣˊㄐㄧㄢˋㄅㄚ ","今天再次提交文件吧").isEmpty());
        } finally { c.close(); }
    }
    @Test public void explicitlyTaughtNameMatchingWholePreviewRemainsWord() throws Exception {
        String user=System.getProperty("r5.user");
        RimeVocabularyInstaller.install(Path.of(user).toFile(),List.<String[]>of(new String[]{"李明魁","ㄌㄧˇㄇㄧㄥˊㄎㄨㄟˊ"}));
        Files.writeString(Path.of(user,"taught_vocab.tsv"),"李明魁\tㄌㄧˇㄇㄧㄥˊㄎㄨㄟˊ\n");
        ZhuyinInputController c=typed("ㄌㄧˇㄇㄧㄥˊㄎㄨㄟˊ");try {
            assertEquals("李明魁",c.previewText());
            assertTrue(AiSentence.rowOrder(c.previewText(),false,c.state().candidates,Collections.emptyList(),Collections.emptyList(),c.rowWordLimit()).contains(0));
        } finally { c.close(); }
    }

    @Test public void focusedCorrectionUsesTheCurrentDictionaryWordSegment() throws Exception {
        ZhuyinInputController c=typed("ㄅㄨˊㄧㄠˋㄩㄢ ㄨㄤˇㄋㄧˇ");
        try {
            c.moveCursorToPreviewCharacter(2);assertEquals(9,c.keyCaret());
            String keys=c.sentenceKeys(),preview=c.previewText();
            JSONObject schema=new JSONObject(Files.readString(Path.of(System.getProperty("r5.contract"))));
            JSONObject req=AiSentence.request(schema,"focus-word",0,0,keys,preview,"",new JSONArray(),new JSONArray());
            JSONObject response=new JSONObject().put("id","word").put("text","不要冤往你").put("repairs",new JSONArray());
            List<JSONObject> row=new AiSentencePhoneProjectionHarness(c,req).project(response,false);
            assertEquals(1,row.size());assertEquals("冤往",row.get(0).getString("text"));assertEquals(2,row.get(0).getInt("start"));assertEquals(4,row.get(0).getInt("end"));
            assertEquals("OFF projection keeps preview",preview,c.previewText());assertEquals("OFF projection keeps caret",9,c.keyCaret());
        } finally {c.close();}
    }
}
