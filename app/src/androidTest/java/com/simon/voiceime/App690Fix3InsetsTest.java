package com.simon.voiceime;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
/** Measure the real input root and page, including the clipping ancestors. */
public class App690Fix3InsetsTest extends App690FSupport {
 private JSONObject view(View v)throws Exception {
  Rect visible=new Rect();boolean shown=v.getGlobalVisibleRect(visible);int[] origin=new int[2];v.getRootView().getLocationOnScreen(origin);visible.offset(origin[0],origin[1]);
  JSONObject o=new JSONObject().put("class",v.getClass().getSimpleName()).put("bounds",rect(v).toShortString()).put("visible",visible.toShortString()).put("shown",shown&&v.isShown()).put("height",v.getHeight()).put("measured_height",v.getMeasuredHeight()).put("padding",new JSONArray(new int[]{v.getPaddingLeft(),v.getPaddingTop(),v.getPaddingRight(),v.getPaddingBottom()}));
  WindowInsets in=v.getRootWindowInsets();return o.put("gesture_inset",in==null?-1:in.getInsets(WindowInsets.Type.systemGestures()).bottom).put("system_window_bottom",in==null?-1:in.getSystemWindowInsetBottom()).put("navigation_inset",in==null?-1:in.getInsets(WindowInsets.Type.navigationBars()).bottom);
 }
 private JSONObject observe(String name,View p)throws Exception {
  return main(()->{JSONArray ancestors=new JSONArray(),caps=new JSONArray();for(View v=p;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null)ancestors.put(view(v));
   for(View k:keys(p))caps.put(view(k).put("tag",String.valueOf(k.getTag())).put("text",k instanceof TextView?((TextView)k).getText():JSONObject.NULL));
   return new JSONObject().put("page",name).put("orientation",root.getResources().getConfiguration().orientation).put("screen_height_dp",root.getResources().getConfiguration().screenHeightDp).put("root",view(root)).put("page_view",view(p)).put("ancestors",ancestors).put("keys",caps).put("dual_safe_inset",field(dual(),"safeInset")).put("split",call(dual(),"landscapeSplit")).put("host",hostCall("state",new Bundle()));});
 }
 private void shot(String name)throws Exception {Bitmap b=inst.getUiAutomation().takeScreenshot();try(java.io.OutputStream o=new java.io.FileOutputStream(new java.io.File(output,name))){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,o));}finally{b.recycle();}}
 private void reacquire()throws Exception {service=main(()->((java.lang.ref.WeakReference<?>)field(api("SimonIMEService"),"notificationVoiceService")).get());assertNotNull(service);root=main(()->(View)field(service,"rootView"));}
 public void testLandscapeRootReadback()throws Exception {
  page("dual_hand");text("");JSONArray result=new JSONArray();
  try{assertTrue(inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90));Thread.sleep(1800);inst.waitForIdleSync();reacquire();
   hostCall("reopen",new Bundle());Thread.sleep(500);reacquire();page("dual_hand");
   JSONObject state=hostCall("state",new Bundle());JSONArray b=state.getJSONArray("editor_bounds");shell("input tap "+((b.getInt(0)+b.getInt(2))/2)+" "+((b.getInt(1)+b.getInt(3))/2));Thread.sleep(800);reacquire();
   for(String name:new String[]{"dual_hand","bopomofo","voice","english","numbers"}){View p=page(name);Thread.sleep(200);result.put(observe(name,p));shot("fix3-landscape-"+name+".png");}
  }finally{save("fix3-landscape-insets.json",result);inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);}
 }
 public void testPortraitRootReadback()throws Exception {
  assertTrue(inst.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0));Thread.sleep(500);reacquire();JSONArray result=new JSONArray();
  for(String name:new String[]{"dual_hand","bopomofo","voice","english","numbers"}){
   View p=page(name);Thread.sleep(250);result.put(observe(name,p).put("stage","initial"));shot("fix3-portrait-"+name+".png");
   main(()->{root.requestApplyInsets();return null;});Thread.sleep(150);result.put(observe(name,p).put("stage","redispatch"));
   for(String theme:new String[]{"light","dark"}){main(()->{inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("keyboard_theme",theme).commit();call(service,"onStartInputView",((android.inputmethodservice.InputMethodService)service).getCurrentInputEditorInfo(),false);return null;});Thread.sleep(150);result.put(observe(name,p).put("stage",theme));}
   hostCall("reopen",new Bundle());Thread.sleep(300);reacquire();p=page(name);Thread.sleep(150);result.put(observe(name,p).put("stage","reopen"));
  }
  save("fix3-portrait-insets.json",result);
 }
}
