package com.simon.voiceime;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class AiSentenceTest {
 @Test public void unchangedPreviewReceivesSuccessfulReplyAfterOldClockWindow() throws Exception {
  JSONObject req=contract().getJSONArray("examples").getJSONObject(0);
  JSONObject response=contract().getJSONArray("examples").getJSONObject(1);
  AiSentence c=session(contract());c.mode("suggestions");assertNotNull(c.begin(550,()->req));
  assertTrue("same current input must retain a successful correction while request is technically bounded",c.receive(req,response.toString(),5100,(k,t)->true));
 }
 @Test public void changedPreviewRejectsLateSuccessfulReplyIndependentlyOfClock() throws Exception {
  JSONObject req=contract().getJSONArray("examples").getJSONObject(0);
  JSONObject response=contract().getJSONArray("examples").getJSONObject(1);
  AiSentence c=session(contract());c.mode("suggestions");assertNotNull(c.begin(550,()->req));
  c.edit(800,false,true);assertFalse(c.receive(req,response.toString(),5100,(k,t)->true));
 }

 static JSONObject contract() throws Exception {
  return new JSONObject(new String(AiSentenceTest.class.getResourceAsStream("/sentence-contract.json").readAllBytes(),StandardCharsets.UTF_8));
 }
 @Test public void firstToneWorkedExamplePayload() throws Exception {
  Class<?> type;
  try { type=Class.forName("com.simon.voiceime.AiSentence"); }
  catch(ClassNotFoundException missing){ fail("AI sentence client is absent: cannot shape the first-tone request");return; }
  JSONObject request=contract().getJSONArray("examples").getJSONObject(0);
  type.getDeclaredMethod("validateRequest",JSONObject.class,JSONObject.class).invoke(null,contract(),request);
  assertEquals("ˋ",request.getJSONArray("key_slots").getString(3));
  assertEquals(" ",request.getJSONArray("touch_alternatives").getJSONObject(0).getJSONArray("alternatives").getJSONObject(1).getString("symbol"));
 }
 @Test public void boundedPayloadAndProvenance() throws Exception {
  Class<?> type=Class.forName("com.simon.voiceime.AiSentence");
  java.lang.reflect.Method build;
  try { build=type.getDeclaredMethod("request",JSONObject.class,String.class,long.class,long.class,String.class,String.class,String.class,JSONArray.class,JSONArray.class); }
  catch(NoSuchMethodException missing){fail("Missing bounded request builder");return;}
  JSONObject req=(JSONObject)build.invoke(null,contract(),"test",1L,7L,"ㄑㄧㄦˊ","妻兒","接續之",new JSONArray(),contract().getJSONArray("examples").getJSONObject(2).getJSONArray("touch_alternatives"));
  assertEquals("接續之",req.getJSONObject("left_context").getString("text"));
  assertEquals("ㄦ",req.getJSONArray("key_slots").getString(2));
 }

 @Test public void fullReadingValidationRejectsUnmappedAndStaleIdentity() throws Exception {
  java.lang.reflect.Method method;
  try { method=Class.forName("com.simon.voiceime.AiSentence").getDeclaredMethod("response",JSONObject.class,JSONObject.class,String.class,java.util.function.BiPredicate.class); }
  catch(NoSuchMethodException missing){fail("Missing response/Rime validation boundary");return;}
  JSONArray fixtures=contract().getJSONArray("examples");
  java.util.function.BiPredicate<String,String> reading=(keys,text)->keys.equals("ㄉㄨㄛ ㄩˊ")&&text.equals("多餘");
  JSONObject result=(JSONObject)method.invoke(null,contract(),fixtures.getJSONObject(0),fixtures.getJSONObject(1).toString(),reading);
  assertEquals("多餘",result.getJSONArray("candidates").getJSONObject(0).getString("text"));
  JSONObject rejected=(JSONObject)method.invoke(null,contract(),fixtures.getJSONObject(0),fixtures.getJSONObject(1).toString(),(java.util.function.BiPredicate<String,String>)((keys,text)->false));
  assertEquals(0,rejected.getJSONArray("candidates").length());
  JSONObject stale=new JSONObject(fixtures.getJSONObject(1).toString()).put("composition_generation",8);
  assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,contract(),fixtures.getJSONObject(0),stale.toString(),reading));
 }

 @Test public void guardedDebounceOnePerGenerationAndStaleTap() throws Exception {
  java.lang.reflect.Constructor<?> ctor;
  try {ctor=Class.forName("com.simon.voiceime.AiSentence").getDeclaredConstructor(JSONObject.class);}
  catch(NoSuchMethodException missing){fail("Missing cancellation and freshness lifecycle");return;}
  Object session=ctor.newInstance(contract());Class<?> type=session.getClass();
  java.lang.reflect.Method edit=type.getDeclaredMethod("edit",long.class,boolean.class,boolean.class);
  java.lang.reflect.Method begin=type.getDeclaredMethod("begin",long.class,java.util.function.Supplier.class);
  edit.invoke(session,0L,true,false);
  java.util.function.Supplier<JSONObject> forbidden=()->{throw new AssertionError("protected/unknown context read");};
  assertNull(begin.invoke(session,450L,forbidden));
  edit.invoke(session,100L,false,true);
  java.util.function.Supplier<JSONObject> snapshot=()->{try{return AiSentence.request(contract(),"test",1,2,"ㄉㄨㄛˋㄩˊ","舵餘","這是",new JSONArray(),new JSONArray());}catch(Exception e){throw new RuntimeException(e);}};
  assertNull(begin.invoke(session,549L,snapshot));assertNotNull(begin.invoke(session,550L,snapshot));assertNull(begin.invoke(session,600L,snapshot));
  edit.invoke(session,601L,false,true);
  assertEquals(false,type.getDeclaredMethod("fresh",JSONObject.class,long.class).invoke(session,contract().getJSONArray("examples").getJSONObject(0),602L));
 }
 @Test public void strictWireRejectsDuplicatesUnknownsAndBadTypes() throws Exception {
  JSONObject schema=contract();JSONArray examples=schema.getJSONArray("examples");JSONObject req=examples.getJSONObject(0);
  String body=examples.getJSONObject(1).toString();java.util.function.BiPredicate<String,String> mapped=(k,t)->true;
  assertThrows(RuntimeException.class,()->AiSentence.response(schema,req,body.substring(0,body.length()-1)+",\"mode\":\"suggestions\"}",mapped));
  assertThrows(RuntimeException.class,()->AiSentence.response(schema,req,new JSONObject(body).put("extra",1).toString(),mapped));
  assertThrows(RuntimeException.class,()->AiSentence.response(schema,req,new JSONObject(body).put("editor_generation",1.5).toString(),mapped));
 }

 static AiSentence session(JSONObject schema) {AiSentence c=new AiSentence(schema);for(int i=0;i<7;i++)c.edit(100,i==0,true);return c;}
 @Test public void shadowReceivesAndSuggestionsRequireBothPolicies() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0),res=schema.getJSONArray("examples").getJSONObject(1);
  AiSentence c=session(schema);assertNotNull(c.begin(550,()->req));assertTrue(c.receive(req,res.toString(),600,(k,t)->true));assertTrue(c.visible(req,600,false).isEmpty());
  c.mode("suggestions");assertEquals(1,c.visible(req,600,false).size());assertTrue(c.visible(req,600,true).isEmpty());assertEquals("manual suggestion remains current after auto deadline",1,c.visible(req,4101,false).size());assertTrue("same current input retains successful correction while HTTP/server resource lifetime is bounded",c.fresh(req,4101));
  c.result.put("mode","shadow");assertTrue(c.visible(req,600,false).isEmpty());
 }
 @Test public void failuresLeaveTypingUntouchedNoRetry() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0);AiSentence c=session(schema);
  assertNotNull(c.begin(550,()->req));c.failed(req);assertEquals("舵餘",req.getString("literal"));assertEquals("ˋ",req.getJSONArray("key_slots").getString(3));assertNull(c.begin(600,()->req));
  c.edit(601,false,true);assertFalse(c.receive(req,"{}",602,(k,t)->true));
 }
 @Test public void requestCroppingKeepsWholeInstalledSpansWithUnicodeScalars() throws Exception {
  String left="😀"+"甲".repeat(31)+"乙丙";
  JSONArray spans=new JSONArray().put(new JSONObject().put("start",1).put("end",3).put("kind","installed_word"));
  JSONObject req=AiSentence.request(contract(),"crop",1,1,"ㄑㄧㄢˊ","前",left,spans,new JSONArray());
  assertEquals(31,SentenceContract.scalars(req.getJSONObject("left_context").getString("text")));assertEquals(0,req.getJSONObject("left_context").getJSONArray("installed_word_spans").length());
 }
 @Test public void originalArrayInsertDeleteBoundsAndConflict() throws Exception {
  JSONArray keys=new JSONArray().put("ㄑ").put("ㄧ").put("ㄦ").put("ˊ");
  JSONArray repairs=new JSONArray().put(new JSONObject().put("operation","insert").put("key_slot",2).put("source_symbol",JSONObject.NULL).put("target_symbol","ㄢ"))
   .put(new JSONObject().put("operation","delete").put("key_slot",3).put("source_symbol","ˊ").put("target_symbol",JSONObject.NULL));
  assertEquals("ㄑㄧㄢㄦ",AiSentence.repairedKeys(keys,repairs));
  repairs.getJSONObject(1).put("key_slot",2);assertThrows(RuntimeException.class,()->AiSentence.repairedKeys(keys,repairs));
 }
 @Test public void wireAndRelationalFailuresSuppress() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0);String original=schema.getJSONArray("examples").getJSONObject(1).toString();
  for(String body:new String[]{"{'kind':'response'}",original+" trailing",original.replace("1500","999"),original.replace("sentence-r2-v1","future"),original.replace("舵餘","錯"),original.replace("多餘","\\ud800"),original.replace("\"bubble\"","\"chip\"")})
   assertThrows(body,RuntimeException.class,()->AiSentence.response(schema,req,body,(k,t)->true));
 }
 @Test public void protectedAndOffNeverEvaluateContext() throws Exception {
  AiSentence c=new AiSentence(contract());java.util.function.Supplier<JSONObject> read=()->{throw new AssertionError("forbidden read");};
  c.edit(0,true,false);assertNull(c.begin(450,read));c.edit(0,false,true);c.mode("off");assertNull(c.begin(450,read));
 }

 @Test public void bothWorkedPayloadsAreShapedFromOwnedKeys() throws Exception {
  JSONObject schema=contract();JSONArray fixtures=schema.getJSONArray("examples");
  for(int index:new int[]{0,2}){
   JSONObject expected=fixtures.getJSONObject(index);JSONArray slots=expected.getJSONArray("key_slots");StringBuilder keys=new StringBuilder();for(int i=0;i<slots.length();i++)keys.append(slots.getString(i));
   JSONObject actual=AiSentence.request(schema,expected.getString("request_id"),1,7,keys.toString(),expected.getString("literal"),expected.getJSONObject("left_context").getString("text"),new JSONArray(),expected.getJSONArray("touch_alternatives"));
   assertEquals(expected.getJSONArray("key_slots").toString(),actual.getJSONArray("key_slots").toString());
   assertEquals(expected.getString("literal"),actual.getString("literal"));assertEquals(expected.getJSONObject("left_context").toString(),actual.getJSONObject("left_context").toString());
  }
 }
 @Test public void probabilitySpanAndRepairSourceRelationalGuards() throws Exception {
  JSONObject schema=contract(),req=new JSONObject(schema.getJSONArray("examples").getJSONObject(0).toString());
  req.getJSONArray("touch_alternatives").getJSONObject(0).getJSONArray("alternatives").getJSONObject(0).put("probability",.9);
  assertThrows(RuntimeException.class,()->AiSentence.validateRequest(schema,req));
  JSONObject res=new JSONObject(schema.getJSONArray("examples").getJSONObject(1).toString());res.getJSONArray("candidates").getJSONObject(0).getJSONArray("repairs").getJSONObject(0).put("source_symbol","ˊ");
  assertThrows(RuntimeException.class,()->AiSentence.response(schema,schema.getJSONArray("examples").getJSONObject(0),res.toString(),(k,t)->true));
 }
 @Test public void semanticRejectionNeverFallsBack() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0),res=new JSONObject(schema.getJSONArray("examples").getJSONObject(1).toString());
  res.put("decision",new JSONObject().put("display","none").put("selected_id","none").put("jev_status","rejected").put("choice_confidence",.8).put("meaning_probability",.2).put("reason","jev_rejected"));
  AiSentence c=session(schema);c.mode("suggestions");c.begin(550,()->req);c.receive(req,res.toString(),600,(k,t)->true);assertTrue(c.visible(req,600,false).isEmpty());
 }

 @Test public void clientDigestAcceptsLegacyRejectsEchoMismatchAndMutation() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0),res=schema.getJSONArray("examples").getJSONObject(1);
  java.lang.reflect.Method digest;
  try{digest=AiSentence.class.getDeclaredMethod("digest",JSONObject.class);}catch(NoSuchMethodException e){throw new AssertionError("phone digest missing",e);}
  String expected=(String)digest.invoke(null,req);
  AiSentence c=session(schema);c.begin(550,()->req);
  assertTrue(c.receive(req,res.toString(),600,(k,t)->true));
  c=session(schema);c.begin(550,()->req);JSONObject bad=new JSONObject(res.toString()).put("digest","0".repeat(64));
  AiSentence mismatch=c;assertThrows(RuntimeException.class,()->mismatch.receive(req,bad.toString(),600,(k,t)->true));
  c=session(schema);c.begin(550,()->req);req.put("literal","變更");assertFalse(c.fresh(req,600));
  assertEquals(64,expected.length());
 }

 @Test public void keepAndEngineIdenticalPreviewAreHidden() throws Exception {
  // r6: multi-word clauses are hidden; a lexical word may equal the preview.
  java.lang.reflect.Method row;
  try {row=AiSentence.class.getDeclaredMethod("rowOrder",String.class,boolean.class,java.util.List.class,java.util.List.class,java.util.List.class);}
  catch(NoSuchMethodException absent){throw new AssertionError("No shared row-3 assembly guard: KEEP/engine preview must be hidden",absent);}
  assertEquals(java.util.List.of(1),row.invoke(null,"不要冤枉你",true,java.util.List.of("不要冤枉你","冤枉"),java.util.List.of("word","word"),java.util.List.of("不要冤枉你")));
 }
 @Test public void focusPlacesDifferentAiAfterWordsBeforeCharacters() {
  assertEquals(java.util.List.of(0,-1,1),AiSentence.rowOrder("不要冤枉你",true,java.util.List.of("冤枉","冤"),java.util.List.of("word","char"),java.util.List.of("往")));
 }
 @Test public void outsideFocusDifferentAiRemainsFirst() {
  assertEquals(java.util.List.of(-1,0,1),AiSentence.rowOrder("不要冤枉你",false,java.util.List.of("冤枉","冤"),java.util.List.of("word","char"),java.util.List.of("往")));
 }
 @Test public void lateAiUsesCurrentFocusWithoutMutatingPreview() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0),res=schema.getJSONArray("examples").getJSONObject(1);
  AiSentence client=session(schema);client.mode("suggestions");client.begin(550,()->req);
  String preview=req.getString("literal");
  // Focus changed after begin; rows must be assembled from NOW's state after delivery.
  assertTrue(client.receive(req,res.toString(),600,(k,t)->true));
  java.util.List<String> ai=java.util.List.of("朵");
  assertEquals(java.util.List.of(0,-1,1),AiSentence.rowOrder(preview,true,java.util.List.of("舵","餘"),java.util.List.of("word","char"),ai));
  assertEquals("舵餘",req.getString("literal"));
 }
 @Test public void keepDecisionCannotOfferIdenticalPreview() throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0),res=new JSONObject(schema.getJSONArray("examples").getJSONObject(1).toString());
  res.getJSONObject("decision").put("selected_id","keep").put("display","none");
  AiSentence client=session(schema);client.mode("suggestions");client.begin(550,()->req);client.receive(req,res.toString(),600,(k,t)->true);
  assertTrue(client.visible(req,600,false).isEmpty());
  assertEquals(java.util.List.of(1),AiSentence.rowOrder(req.getString("literal"),false,java.util.List.of("舵餘","舵"),java.util.List.of("word","char"),java.util.List.of(req.getString("literal")),1));
 }

 @Test public void charOnlyFocusStillKeepsAiBehindFocusedCandidates() {
  assertEquals(java.util.List.of(0,1,-1),AiSentence.rowOrder("舵餘",true,java.util.List.of("舵","多"),java.util.List.of("char","char"),java.util.List.of("朵")));
 }
 @Test public void hiddenWordCannotMakeAiPrecedeCharOnlyFocus() {
  assertEquals(java.util.List.of(1,-1),AiSentence.rowOrder("舵餘",true,java.util.List.of("舵餘","多"),java.util.List.of("word","char"),java.util.List.of("朵"),1));
 }

 @Test public void focusedRowRejectsLateClauseFromOldFocus() {
  assertEquals(java.util.List.of(0),AiSentence.rowOrder("不要冤枉你",true,
   java.util.List.of("冤枉"),java.util.List.of("word"),java.util.List.of("不要冤往你"),2));
 }

 @Test public void rowThreeRejectsWholeSentenceFromAnySource() {
  assertEquals(java.util.List.of(1,2),AiSentence.rowOrder("不要冤枉你",true,
   java.util.List.of("不要冤往你","冤枉","冤"),java.util.List.of("word","word","char"),java.util.List.of("")));
 }

 @Test public void longSentenceDiffOffersOnlyChangedSpanAndAppliesOnlyThatSpan() throws Exception {
  java.lang.reflect.Method diff;
  try {diff=AiSentence.class.getDeclaredMethod("changedSpans",String.class,String.class);}
  catch(NoSuchMethodException e){throw new AssertionError("AI full response has no changed-span projection",e);}
  java.util.List<JSONObject> spans=(java.util.List<JSONObject>)diff.invoke(null,"今天髓以先送出文件明天再核對","今天所以先送出文件明天再核對");
  assertEquals(1,spans.size());assertEquals("所",spans.get(0).getString("text"));
  assertEquals(2,spans.get(0).getInt("start"));assertEquals(3,spans.get(0).getInt("end"));
  assertEquals("今天所以先送出文件明天再核對",AiSentence.replaceSpan("今天髓以先送出文件明天再核對",spans.get(0)));
  assertTrue(((java.util.List<?>)diff.invoke(null,"今天所以先送出文件","今天所以先送出文件")).isEmpty());
 }

 @Test public void independentAiChangesCanBePickedSeparatelyAndKeepUnicodeAnchors(){
  java.util.List<JSONObject> spans=AiSentence.changedSpans("😀甲乙丙丁戊己","😀佳乙丙丁午己");
  assertEquals(2,spans.size());assertEquals("😀佳乙丙丁戊己",AiSentence.replaceSpan("😀甲乙丙丁戊己",spans.get(0)));
  assertEquals("😀甲乙丙丁午己",AiSentence.replaceSpan("😀甲乙丙丁戊己",spans.get(1)));
 }

 @Test public void nineCharactersWithoutPauseRequestThreeWholePreviews() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");int sent=0;
  java.lang.reflect.Method begin;
  try {begin=AiSentence.class.getDeclaredMethod("begin",long.class,String.class,boolean.class,java.util.function.Supplier.class);}
  catch(NoSuchMethodException missing){throw new AssertionError("three-character trigger missing",missing);}
  for(int i=1;i<=9;i++){
   c.edit(i*10,false,true);final int n=i;
   java.util.function.Supplier<JSONObject> snapshot=()->AiSentence.request(c.schema,"c"+n,c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(n),"多".repeat(n),"",new JSONArray(),new JSONArray());
   JSONObject r=(JSONObject)begin.invoke(c,i*10L,"多".repeat(i),true,snapshot);
   if(i%3==0){assertNotNull(r);assertEquals("多".repeat(i),r.getString("literal"));sent++;}else assertNull(r);
  }
  assertEquals(3,sent);
 }

 @Test public void incompleteSyllableCannotSendBeforePause() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(10,false,true);
  assertNull(c.begin(100,"多多多ㄉ",false,()->{throw new AssertionError("mid-syllable snapshot read");}));
 }
 @Test public void unchangedTextAndSupersededCharsRemainGuarded() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(10,false,true);
  JSONObject first=c.begin(10,"多多多",true,()->AiSentence.request(c.schema,"first",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray()));
  assertNotNull(first);c.edit(20,false,true);assertFalse(c.fresh(first,20));
  assertFalse(c.charsDue("多多多")); // Same preview has zero net new characters.
  assertNull(c.begin(100,"多多多",true,()->{throw new AssertionError("unchanged chars snapshot read");}));
  c.edit(480,false,true);
  JSONObject second=c.begin(930,"多多多多",true,()->AiSentence.request(c.schema,"second",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(4),"多多多多","",new JSONArray(),new JSONArray()));
  assertNotNull(second);assertEquals("pause",c.trigger());assertFalse(c.receive(first,"{}",940,(k,t)->true));
 }

 @Test public void clearedCompositionStartsFreshThreeCharacterBudget() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(1,false,true);
  assertNotNull(c.begin(1,"多多多",true,()->AiSentence.request(c.schema,"one",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray())));
  c.edit(2,false,true);assertNull(c.begin(2,"",false,()->{throw new AssertionError();}));
  c.edit(3,false,true);
  assertNotNull(c.begin(3,"多多多",true,()->AiSentence.request(c.schema,"two",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray())));
 }

 @Test public void engineRevisingPriorWordDoesNotLoseNewCharacterCadence() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(1,false,true);
  assertNotNull(c.begin(1,"多多多",true,()->AiSentence.request(c.schema,"one",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray())));
  c.edit(2,false,true);
  assertNotNull(c.begin(2,"朵多多多多多",true,()->AiSentence.request(c.schema,"two",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(6),"朵多多多多多","",new JSONArray(),new JSONArray())));
 }

 @Test public void existingPausePathStillAcceptsAbbreviatedReading() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(1,false,true);
  assertNotNull(c.begin(451,"多多多",false,()->AiSentence.request(c.schema,"pause",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray())));
  assertEquals("pause",c.trigger());
 }

 @Test public void pauseResendsSameTextAfterEditOncePerGeneration() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(10,false,true);
  JSONObject first=c.begin(10,"多多多",true,()->AiSentence.request(c.schema,"first",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray()));
  assertNotNull(first);
  c.edit(20,false,true);c.edit(30,false,true);
  assertFalse(c.fresh(first,30));
  assertNull(c.begin(479,"多多多",true,()->{throw new AssertionError("before pause");}));
  JSONObject second=c.begin(480,"多多多",true,()->AiSentence.request(c.schema,"second",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(3),"多多多","",new JSONArray(),new JSONArray()));
  assertNotNull(second);assertEquals("pause",c.trigger());
  assertNull(c.begin(481,"多多多",true,()->{throw new AssertionError("duplicate generation");}));
  assertFalse(c.receive(first,"{}",481,(k,t)->true));
 }

 @Test public void legacyPauseOverloadResendsSameLiteralAfterEdit() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("suggestions");c.edit(10,false,true);
  assertNotNull(c.begin(460,()->AiSentence.request(c.schema,"one",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ","多","",new JSONArray(),new JSONArray())));
  c.edit(470,false,true);
  assertNotNull(c.begin(920,()->AiSentence.request(c.schema,"two",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ","多","",new JSONArray(),new JSONArray())));
  assertEquals("pause",c.trigger());
 }

 @Test public void liveWaitsTenCharactersAndOneSecondBeforeContextRead() throws Exception {
  AiSentence c=new AiSentence(contract());c.mode("live");c.edit(100,true,true);
  java.util.function.Supplier<JSONObject> forbidden=()->{throw new AssertionError("auto context read before ten characters/one second");};
  assertFalse(c.charsDue("多".repeat(10)));
  assertNull(c.begin(550,"多".repeat(10),true,forbidden));
  assertNull(c.begin(1099,"多".repeat(10),true,forbidden));
  assertNull(c.begin(1100,"多".repeat(9),true,forbidden));
  JSONObject req=c.begin(1100,"多".repeat(10),true,()->AiSentence.request(c.schema,"live-ten",c.editorGeneration,c.compositionGeneration,"ㄉㄨㄛ ".repeat(10),"多".repeat(10),"",new JSONArray(),new JSONArray()));
  assertNotNull(req);assertEquals("pause",c.trigger());assertNull(c.begin(1101,"多".repeat(10),true,forbidden));
  c.edit(1102,false,true);assertFalse(c.fresh(req,1103));
 }
 @Test public void capturedWrongKeysRespectLiveDeadlineWithoutInventingKeys() throws Exception {
  JSONObject fixture=new JSONObject(new String(getClass().getResourceAsStream("/real-key-673.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
  JSONObject input=fixture.getJSONObject("input");String literal=input.getString("literal");JSONArray keys=input.getJSONArray("keys");StringBuilder reading=new StringBuilder();for(int i=0;i<keys.length();i++)reading.append(keys.getString(i));
  assertEquals("committed_text_proxy",fixture.getString("truth_status"));
  AiSentence c=new AiSentence(contract());c.mode("live");c.edit(0,true,true);
  assertNull(c.begin(450,literal,true,()->{throw new AssertionError("captured wrong-key clause queried before one second");}));
  JSONObject req=c.begin(1000,literal,true,()->AiSentence.request(c.schema,"captured-673",c.editorGeneration,c.compositionGeneration,reading.toString(),literal,"",new JSONArray(),new JSONArray()));
  assertNotNull(req);assertEquals(keys.toString(),req.getJSONArray("key_slots").toString());assertEquals(literal,req.getString("literal"));
 }
 static JSONObject publicAutoFixture() throws Exception {
  return new JSONObject(new String(AiSentenceTest.class.getResourceAsStream("/auto-v2-public.json").readAllBytes(),StandardCharsets.UTF_8));
 }
 @Test public void officialAutoV2AndServerOffBubbleUseSamePublicContract() throws Exception {
  JSONObject fixture=publicAutoFixture(),req=fixture.getJSONObject("request"),res=fixture.getJSONObject("response");
  AiSentence.validateRequest(contract(),req);
  JSONObject ready=AiSentence.response(contract(),req,res.toString(),(k,t)->true);
  assertEquals("auto",ready.getJSONObject("decision").getString("display"));
  res.put("decision",fixture.getJSONObject("actual_policy"));
  AiSentence client=session(contract());client.mode("suggestions");
  assertNotNull(client.begin(550,()->req));assertTrue(client.receive(req,res.toString(),600,(k,t)->true));
  assertEquals(1,client.visible(req,600,false).size());
  assertEquals("bubble",client.result.getJSONObject("decision").getString("display"));
 }
 @Test public void officialLegacyRejectsFakeAutoReadinessMetadata() throws Exception {
  JSONObject req=contract().getJSONArray("examples").getJSONObject(0),res=new JSONObject(contract().getJSONArray("examples").getJSONObject(1).toString());
  req.put("client_auto",false);res.put("name_protection_ready",true);
  res.getJSONArray("candidates").getJSONObject(0).put("protected",false);
  assertThrows(RuntimeException.class,()->AiSentence.response(contract(),req,res.toString(),(k,t)->true));
 }
 @Test public void autoV2RequiresDigestPolicyAndConsistentProtection() throws Exception {
  JSONObject fixture=publicAutoFixture(),req=fixture.getJSONObject("request"),res=fixture.getJSONObject("response");
  for(String mutation:java.util.List.of("missing_digest","legacy_policy","protection_mismatch","legacy_request")){
   JSONObject r=new JSONObject(res.toString()),q=new JSONObject(req.toString());
   if(mutation.equals("missing_digest"))r.remove("digest");
   if(mutation.equals("legacy_policy"))r.put("policy_version","sentence-r2-v1");
   if(mutation.equals("protection_mismatch"))r.getJSONArray("candidates").getJSONObject(0).getJSONArray("protected_categories").put("name");
   if(mutation.equals("legacy_request"))q.put("client_auto",false);
   assertThrows(mutation,RuntimeException.class,()->AiSentence.response(contract(),q,r.toString(),(k,t)->true));
  }
 }
 @Test public void automaticAuthorizationRequiresActualAutoNotChipOrBubble() throws Exception {
  java.lang.reflect.Method authorize=AiSentence.class.getDeclaredMethod("autoAuthorized",JSONObject.class,JSONObject.class);
  JSONObject fixture=publicAutoFixture(),req=fixture.getJSONObject("request"),res=fixture.getJSONObject("response");
  assertEquals(true,authorize.invoke(null,req,AiSentence.response(contract(),req,res.toString(),(k,t)->true)));
  for(String display:java.util.List.of("chip","bubble","none")){
   JSONObject changed=new JSONObject(res.toString());changed.getJSONObject("decision").put("display",display);
   assertEquals(display,false,authorize.invoke(null,req,changed));
  }
  // User direct-success authority supersedes the former .98 quality veto; display/digest/protection/unmapped controls remain.
  for(double confidence:new double[]{0,.92,.98,.99,1}){
   res.getJSONObject("decision").put("choice_confidence",confidence);
   assertEquals("selected successful correction must not be vetoed by an extra score",true,authorize.invoke(null,req,AiSentence.response(contract(),req,res.toString(),(k,t)->true)));
  }
  for(Object invalid:new Object[]{-.01,1.01,JSONObject.NULL,"NaN","Infinity","0.92"}){
   JSONObject malformed=new JSONObject(res.toString());malformed.getJSONObject("decision").put("choice_confidence",invalid);
   assertThrows("finite numeric 0..1 protocol shape: "+String.valueOf(invalid),RuntimeException.class,()->AiSentence.response(contract(),req,malformed.toString(),(k,t)->true));
  }
  res.getJSONObject("decision").put("choice_confidence",.99);
  assertEquals(false,authorize.invoke(null,req,AiSentence.response(contract(),req,res.toString(),(k,t)->false)));
 }
 @Test public void canonicalDigestMatchesOfficialUtf8WithoutNonAsciiEscapes() throws Exception {
  JSONObject req=publicAutoFixture().getJSONObject("request");req.getJSONObject("left_context").put("text","前\u2028後</段");
  assertEquals("233c417a24f038f036df10ce4c200aa01a2e1c08163e6a43b1f33439afaae041",AiSentence.digest(req));
 }
 @Test public void digestUsesSlotValuesIndependentlyOfJsonSerializer() throws Exception {
  JSONObject req=publicAutoFixture().getJSONObject("request");req.getJSONObject("left_context").put("text","前\u2028後</段");
  JSONArray equivalent=new JSONArray(req.getJSONArray("key_slots").toString()) {
   @Override public String toString(){return super.toString().replace("ㄉ","\\u3109");}
  };
  req.put("key_slots",equivalent);
  assertEquals("233c417a24f038f036df10ce4c200aa01a2e1c08163e6a43b1f33439afaae041",AiSentence.digest(req));
 }


 @Test public void manualSuggestionLifetimeUsesCurrentInputTokenAndPreservesOriginal()throws Exception {
  JSONObject schema=contract(),req=schema.getJSONArray("examples").getJSONObject(0),res=schema.getJSONArray("examples").getJSONObject(1);AiSentence c=session(schema);c.mode("suggestions");assertNotNull(c.begin(550,()->req));assertTrue(c.receive(req,res.toString(),600,(k,t)->true));
  assertEquals("舵餘",req.getString("literal"));assertEquals(1,c.visible(req,6000,false).size());assertTrue("same current input retains correction; new input below invalidates it",c.fresh(req,6000));
  c.edit(6001,false,true);assertTrue("new physical key rejects old manual result",c.visible(req,6002,false).isEmpty());
 }
 @Test public void manualSuggestionOldFieldPrivacyAndDigestNeverRemainSelectable()throws Exception {
  for(String invalid:new String[]{"field","password","off","digest"}){
   JSONObject schema=contract(),req=new JSONObject(schema.getJSONArray("examples").getJSONObject(0).toString()),res=schema.getJSONArray("examples").getJSONObject(1);AiSentence c=session(schema);c.mode("suggestions");assertNotNull(c.begin(550,()->req));assertTrue(c.receive(req,res.toString(),600,(k,t)->true));
   assertEquals(1,c.visible(req,6000,false).size());
   if(invalid.equals("field"))c.edit(6001,true,true);else if(invalid.equals("password"))c.edit(6001,true,false);else if(invalid.equals("off"))c.mode("off");else req.put("literal","different context");
   assertTrue(invalid+" invalidates manual authority",c.visible(req,6002,false).isEmpty());
  }
 }
}
