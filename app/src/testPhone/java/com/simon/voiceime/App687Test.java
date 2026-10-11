package com.simon.voiceime;
import org.junit.Test;import static org.junit.Assert.*;import java.nio.file.*;import java.io.*;import java.util.*;import java.lang.reflect.*;
public class App687Test {
 @Test public void all412SkeletonsAndExactThreeGestureGaps()throws Exception {
  Path a=Path.of("src/phone/assets/dual_hand");DualHandTable t=new DualHandTable(Files.newBufferedReader(a.resolve("dual_hand_map.tsv")),Files.newBufferedReader(a.resolve("left_hand.tsv")),Files.newBufferedReader(a.resolve("draft_chars.tsv")),Files.newBufferedReader(a.resolve("draft_chars_ai.tsv")));
  Map<String,String> expected=new LinkedHashMap<>();try(BufferedReader in=new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/w1f3b_projection.tsv"),java.nio.charset.StandardCharsets.UTF_8))){assertEquals("syllable\tchar",in.readLine());String row;while((row=in.readLine())!=null){String[] v=row.split("\t",-1);expected.put(v[0],v[1]);}}
  Map<String,String> fallback=new LinkedHashMap<>();for(String row:Files.readAllLines(Path.of("src/testPhone/resources/w1d_fallback.tsv")).subList(1,416)){String[] v=row.split("\\t",-1);fallback.put(v[0],v[1]);}Set<String> fallbackSounds=new HashSet<>();int fallbackCount=0;for(Map.Entry<String,String> e:expected.entrySet())if(e.getValue().isEmpty()){fallbackSounds.add(e.getKey());e.setValue(fallback.get(e.getKey()));fallbackCount++;}assertEquals(34,fallbackCount);assertEquals(415,expected.size());for(Map.Entry<String,String> e:expected.entrySet())assertEquals(e.getKey(),e.getValue(),t.aiDraft(e.getKey()));
  List<String> provenance=Files.readAllLines(a.resolve("draft_chars_ai.provenance.tsv"));assertEquals(416,provenance.size());assertEquals("syllable\tchar\tsource",provenance.get(0));
  for(String row:provenance.subList(1,416)){String[] v=row.split("\t",-1);assertEquals(expected.get(v[0]),v[1]);assertEquals(fallbackSounds.contains(v[0])?"W1D_FALLBACK":"W1F3b",v[2]);}
  Set<String> seen=new HashSet<>();List<String> mismatches=new ArrayList<>();
  for(String state:DualHandTable.STATES)for(String key:DualHandTable.RIGHT_KEYS)for(String direction:DualHandTable.DIRECTIONS){String sound=t.right(state,key,direction);if(sound.isEmpty()||!seen.add(sound))continue;DualHandState engine=new DualHandState(t);
   if(!state.equals("Ø")){boolean found=false;for(int i=1;i<=6&&!found;i++)for(String d:DualHandTable.DIRECTIONS)if(state.equals(t.left(""+i,d))){DualHandState.Stroke l=engine.begin(""+i,0);engine.end(l,d,false);engine.drain(100);found=true;break;}assertTrue(found);}
   DualHandState.Stroke r=engine.begin(key,200);engine.end(r,direction,false);engine.drain(400);if(!Objects.equals(expected.get(sound),engine.aiDraft()))mismatches.add(sound+":"+engine.aiDraft()+"!="+expected.get(sound));
  }Set<String> missing=new HashSet<>(expected.keySet());missing.removeAll(seen);assertEquals(new HashSet<>(Arrays.asList("ㄋㄨㄣ","ㄌㄩㄢ","ㄝ")),missing);assertEquals(412,seen.size());assertTrue(mismatches.size()+" mismatches: "+mismatches,mismatches.isEmpty());
 }
 @Test public void lifecycleStallKeepsOwnerAndName()throws Exception {
  List<String> events=new ArrayList<>();final MainDispatchMonitor[] ref=new MainDispatchMonitor[1];
  ref[0]=new MainDispatchMonitor((ms,target,callback,what,input,candidates)->{try{events.add(ms+":"+MainDispatchMonitor.class.getDeclaredMethod("service").invoke(ref[0])+":"+MainDispatchMonitor.class.getDeclaredMethod("lifecycle").invoke(ref[0]));}catch(Exception e){throw new RuntimeException(e);}});
  MainDispatchMonitor m=ref[0];MainDispatchMonitor.class.getDeclaredMethod("service",String.class).invoke(m,"com.simon.voiceime.SimonIMEService");
  MainDispatchMonitor.class.getDeclaredMethod("beginLifecycle",String.class,long.class).invoke(m,"onDestroy",10L);MainDispatchMonitor.class.getDeclaredMethod("endLifecycle",long.class).invoke(m,211L);
  assertEquals(Collections.singletonList("201:com.simon.voiceime.SimonIMEService:onDestroy"),events);
  m.accept(">>>>> Dispatching to Handler (android.os.Handler) {abc} null: 0",300,false);m.accept("<<<<< Finished",501,false);
  assertEquals("201:com.simon.voiceime.SimonIMEService:",events.get(1));
 }
 @Test public void pendingFieldSurvivesRestartAndReachesDelivery()throws Exception {
  File root=Files.createTempDirectory(Path.of("build"),"app687-queue-").toFile();VoicePendingQueue q=new VoicePendingQueue(root,16000);
  Method begin=VoicePendingQueue.class.getDeclaredMethod("beginAsync",String.class,int.class,Runnable.class);String id=(String)begin.invoke(q,"synthetic:field",17,null);q.runIO(()->null);q.appendAsync(id,new byte[]{1,2,3,4},4);q.finishRecording(id,null);q.runIO(()->null);
  q=new VoicePendingQueue(root,16000);q.persistResult(id,"synthetic result");List<String> got=new ArrayList<>();q.uploadOne(id,(file,sid,rate)->new org.json.JSONObject().put("text","synthetic result").put("receipt",new org.json.JSONObject().put("client_session_id",sid).put("byte_count",4).put("sha256","9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a")).toString(),new VoicePendingQueue.Delivery(){public void deliver(String text,String start){fail("field identity lost");}public void deliver(String text,String start,String field,int generation){got.add(field+":"+generation+":"+text);}},null);
  assertEquals(Collections.singletonList("synthetic:field:17:synthetic result"),got);
 }
}
