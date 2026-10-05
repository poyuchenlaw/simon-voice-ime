package com.simon.voiceime;
import android.graphics.Rect;import android.widget.TextView;import org.json.*;
public class CursorDiagnostics670AndroidTest extends TextRows670AndroidTest{
 Object service(){android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();return c;}
 public void testRank10Diagnostic()throws Exception{
  begin();inst.runOnMainSync(()->{try{ZhuyinInputController c=actualController();ZhuyinInputController.State state=null;for(int n=0;n<45;n++)for(char k:(n%2==0?"ㄐㄧㄣ ":"ㄊㄧㄢ ").toCharArray())state=c.press(k==' '?"space":String.valueOf(k));java.lang.reflect.Method m=SimonIMEService.class.getDeclaredMethod("applyZhuyinState",ZhuyinInputController.State.class);m.setAccessible(true);m.invoke(service(),state);}catch(Exception e){throw new RuntimeException(e);}});
  Thread.sleep(300);tapBoundary(0);revealTenth(words,wordScroll);revealTenth(characters,charScroll);TextView choice=(TextView)characters.getChildAt(9);Rect r=new Rect();JSONObject d=new JSONObject();
  inst.runOnMainSync(()->{int[] a=new int[2];choice.getLocationOnScreen(a);r.set(a[0],a[1],a[0]+choice.getWidth(),a[1]+choice.getHeight());});
  d.put("fixture","controller-seeded diagnostic only; not physical typing acceptance").put("before",shown()).put("label",choice.getText()).put("bounds",r.toString()).put("parent_scroll",charScroll.getScrollX());screenshot("rank10-before");
  java.lang.reflect.Field taps=SimonIMEService.class.getDeclaredField("textCandidateTaps");taps.setAccessible(true);d.put("taps_before",taps.get(service()));tap(r);Thread.sleep(300);d.put("after_physical",shown()).put("taps_after_physical",taps.get(service()));
  inst.runOnMainSync(()->choice.performClick());Thread.sleep(100);d.put("after_performClick",shown()).put("taps_after_performClick",taps.get(service()));
  save("rank10-diagnostic.json",d.toString());save("tree.xml",shell("uiautomator dump /data/local/tmp/cursor670.xml >/dev/null; cat /data/local/tmp/cursor670.xml"));
 } public void testRank10PhysicalDiagnostic()throws Exception{
  begin();for(int n=0;n<22;n++)typeToday();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白"})tap(k);
  Thread.sleep(300);tapBoundary(0);revealTenth(words,wordScroll);revealTenth(characters,charScroll);TextView choice=(TextView)characters.getChildAt(9);Rect r=new Rect();JSONObject d=new JSONObject();
  inst.runOnMainSync(()->{int[] a=new int[2];choice.getLocationOnScreen(a);r.set(a[0],a[1],a[0]+choice.getWidth(),a[1]+choice.getHeight());});
  java.lang.reflect.Method get=android.view.View.class.getDeclaredMethod("getListenerInfo");get.setAccessible(true);Object li=get.invoke(choice);java.lang.reflect.Field click=li.getClass().getDeclaredField("mOnClickListener");click.setAccessible(true);Object listener=click.get(li);
  for(java.lang.reflect.Field f:listener.getClass().getDeclaredFields()){f.setAccessible(true);Object v=f.get(listener);if(v instanceof ZhuyinInputController.TextChoice){ZhuyinInputController.TextChoice tc=(ZhuyinInputController.TextChoice)v;d.put("closure_label",tc.label).put("closure_index",tc.index).put("closure_boundary",tc.boundary).put("closure_cached",actualController().textChoices().contains(tc)).put("closure_witness",tc.witness.equals(actualController().previewText())).put("closure_keys",tc.keys.equals(actualController().sentenceKeys()));}}
  d.put("fixture","physical 45-character diagnostic").put("before",shown()).put("label",choice.getText()).put("bounds",r.toString()).put("parent_scroll",charScroll.getScrollX());screenshot("rank10-before");
  java.lang.reflect.Field taps=SimonIMEService.class.getDeclaredField("textCandidateTaps");taps.setAccessible(true);d.put("taps_before",taps.get(service()));tap(r);Thread.sleep(300);d.put("after_physical",shown()).put("taps_after_physical",taps.get(service()));
  inst.runOnMainSync(()->choice.performClick());Thread.sleep(100);d.put("after_performClick",shown()).put("taps_after_performClick",taps.get(service()));
  save("rank10-physical-diagnostic.json",d.toString());save("tree.xml",shell("uiautomator dump /data/local/tmp/cursor670.xml >/dev/null; cat /data/local/tmp/cursor670.xml"));
 }
}
