package com.simon.voiceime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class TouchModelShadowTest {
    private TouchModel model(){return new TouchModel(Arrays.asList(
        new TouchModel.Key("outer","ㄅ",0,0,90,90),new TouchModel.Key("outer","ㄆ",90,0,90,90),
        new TouchModel.Key("outer","ㄇ",180,0,90,90),new TouchModel.Key("inner","ㄅ",0,0,180,90)));}
    @Test public void wrongPosteriorNeverBecomesLabelAndConfirmationWaitsTenSeconds() {
        TouchModel m=model();TouchModelShadow s=new TouchModelShadow(m);
        List<TouchModel.Alternative> a=s.press("outer","ㄅ",90,0);
        assertEquals(2,a.size());assertEquals("ㄆ",a.get(0).key);assertTrue(a.get(0).probability>.99);
        assertEquals(0,m.parameters("outer","ㄅ").count);
        long id=s.awaitConfirmation(1000);
        assertEquals(0,s.confirm(id,10999,true));assertEquals(1,s.confirm(id,11000,true));
        assertEquals(1,m.parameters("outer","ㄅ").count);assertEquals(0,m.parameters("outer","ㄆ").count);
        assertEquals(0,m.parameters("inner","ㄅ").count);assertEquals(0,s.confirm(id,12000,true));
    }
    @Test public void rewriteFieldChangeAndResetCannotTrainPendingLabels() {
        TouchModel m=model();TouchModelShadow s=new TouchModelShadow(m);byte[] defaults=m.snapshot();
        s.press("outer","ㄅ",20,0);long id=s.awaitConfirmation(0);
        assertEquals(0,s.confirm(id,10000,false));assertEquals(0,s.confirm(id,11000,true));
        s.press("outer","ㄅ",20,0);id=s.awaitConfirmation(0);s.invalidate();
        assertEquals(0,s.confirm(id,10000,true));assertArrayEquals(defaults,s.snapshot());
        s.press("outer","ㄅ",20,0);id=s.awaitConfirmation(0);s.confirm(id,10000,true);
        s.press("outer","ㄅ",20,0);id=s.awaitConfirmation(0);s.reset();
        assertArrayEquals(defaults,s.snapshot());assertEquals(0,s.confirm(id,10000,true));
    }
    @Test public void telemetryCarriesOnlyTwoAlternativeProbabilitiesAndProtectedFieldsOmitThem() throws Exception {
        TouchModelShadow shadow=new TouchModelShadow(model());
        org.json.JSONObject fields=TouchShadowLearning.fields(shadow.press("outer","ㄅ",90,0),"outer");
        org.json.JSONArray alternatives=fields.getJSONArray("touch_alternatives");
        assertEquals("shadow",fields.getString("touch_model_mode"));assertEquals(2,alternatives.length());
        assertEquals("ㄆ",alternatives.getJSONObject(0).getString("key"));
        assertTrue(alternatives.getJSONObject(0).getDouble("probability")>.99);
        org.json.JSONObject protectedEvent=ImeTelemetry.makeEvent(1,"s","6.47","key","bopomofo",fields,true);
        assertFalse(protectedEvent.has("touch_alternatives"));
    }
    @Test public void protectedProgrammaticKeyOutcomeNeverContainsKeyName() throws Exception {
        org.json.JSONObject fields=new org.json.JSONObject().put("key","ㄅ").put("key_to_candidate_ms",7);
        org.json.JSONObject event=ImeTelemetry.makeEvent(1,"s","6.47","key_outcome","bopomofo",fields,true);
        assertFalse("password key outcomes must redact key content",event.has("key"));
    }
    @Test public void explicitCandidateChoiceConfirmsLabelsBeforeFurtherTyping() throws Exception {
        TouchModel m=model();TouchModelShadow s=new TouchModelShadow(m);s.press("outer","ㄅ",20,0);
        int learned=s.confirmChoice();
        assertEquals("explicit acceptance must not wait for an unchanged whole composition",1,learned);
        s.press("outer","ㄆ",90,0);assertEquals(1,m.parameters("outer","ㄅ").count);
        assertEquals(0,m.parameters("outer","ㄆ").count);
    }
    @Test public void learnedSnapshotSurvivesRestartButUnconfirmedTraceDoesNot() throws Exception {
        TouchModel m=model();TouchModelShadow s=new TouchModelShadow(m);
        s.press("outer","ㄅ",20,0);s.confirm(s.awaitConfirmation(0),10000,true);
        s.press("outer","ㄅ",25,0);
        TouchModel restored=model();restored.rollback(s.snapshot());
        assertEquals(1,restored.parameters("outer","ㄅ").count);
        assertEquals(0,restored.parameters("inner","ㄅ").count);
    }
}
