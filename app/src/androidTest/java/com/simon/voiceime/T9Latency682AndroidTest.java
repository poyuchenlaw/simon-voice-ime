package com.simon.voiceime;
import android.view.*;import android.widget.*;import android.graphics.Rect;import android.os.*;import org.json.*;import java.io.*;import java.util.*;
/** The same physical single-key-to-preview seam on the 6.81 baseline and T9. */
public class T9Latency682AndroidTest extends TapCursor677AndroidTest {
 void measure(boolean nine)throws Exception{
  ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();bind();text("");tap("test_input");
  final SimonIMEService[] service={null};inst.runOnMainSync(()->{android.content.Context c=words.getContext();while(c instanceof android.content.ContextWrapper&&!(c instanceof SimonIMEService))c=((android.content.ContextWrapper)c).getBaseContext();service[0]=(SimonIMEService)c;});
  Object[] page={null};TextView[] preview={row1};if(nine){tap("九");await("T9 5");inst.runOnMainSync(()->{try{var f=SimonIMEService.class.getDeclaredField("t9Page");f.setAccessible(true);page[0]=f.get(service[0]);View root=(View)page[0].getClass().getMethod("view").invoke(page[0]);preview[0]=(TextView)root.findViewWithTag("t9-preview");}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(1000);}
  List<Long> latencies=new ArrayList<>();String key=nine?"T9 5":"ㄓ";Rect rect=new Rect();await(key).getBoundsInScreen(rect);
  for(int i=0;i<105;i++){
   inst.runOnMainSync(()->{try{if(nine)page[0].getClass().getMethod("clear").invoke(page[0]);else {var m=SimonIMEService.class.getDeclaredMethod("clearBopomofoBuffer");m.setAccessible(true);m.invoke(service[0]);}}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(30);
   long[] end={0};long start=SystemClock.uptimeMillis();ViewTreeObserver.OnDrawListener listener=()->{if(end[0]==0&&!preview[0].getText().toString().isEmpty()&&!preview[0].getText().toString().contains("載入"))end[0]=SystemClock.uptimeMillis();};inst.runOnMainSync(()->preview[0].getViewTreeObserver().addOnDrawListener(listener));
   tap(rect);long deadline=start+5000;while(end[0]==0&&SystemClock.uptimeMillis()<deadline)Thread.sleep(5);inst.runOnMainSync(()->preview[0].getViewTreeObserver().removeOnDrawListener(listener));assertTrue("observed rendered local preview",end[0]>0);if(i>=5)latencies.add(end[0]-start);
  }
  Collections.sort(latencies);save("latency-"+(nine?"t9":"41")+".json",new JSONObject().put("samples",latencies.size()).put("p50_ms",latencies.get(50)).put("p95_ms",latencies.get(95)).put("values",new JSONArray(latencies)).put("scope","physical tap DOWN through first decoded preview draw; 5 warmups excluded").toString());screenshot("latency-preview");readback("latency-editor-text.json");
 }
 public void test41()throws Exception{measure(false);}public void testT9()throws Exception{measure(true);}
}
