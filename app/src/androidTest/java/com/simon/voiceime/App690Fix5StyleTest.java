package com.simon.voiceime;
import android.graphics.*;import android.view.*;import android.widget.*;import org.json.*;import java.util.*;
/** Requirements come from FIX5 and the retained 6.89.1 probe, not product helpers. */
public class App690Fix5StyleTest extends App690FSupport {
 private final JSONArray observations=new JSONArray();
 private void collect(View v,String panel)throws Exception {
  if(!v.isShown())return;
  if(v instanceof Button){TextView t=(TextView)v;String text=t.getText().toString();if(text.equals("詞彙")||text.equals("＋ 資料夾")||text.equals("✕")){
   Rect box=rect(v),visible=new Rect();v.getGlobalVisibleRect(visible);int[] screenRoot=new int[2],windowRoot=new int[2];v.getRootView().getLocationOnScreen(screenRoot);v.getRootView().getLocationInWindow(windowRoot);visible.offset(screenRoot[0]-windowRoot[0],screenRoot[1]-windowRoot[1]);box.inset(Math.round(density()),Math.round(density()));visible.intersect(box);
   android.text.Layout l=t.getLayout();Rect b=new Rect();float[] ink={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};int[] at=new int[2];v.getLocationOnScreen(at);
   for(int i=0;i<l.getLineCount();i++){String line=text.substring(l.getLineStart(i),Math.min(text.length(),l.getLineEnd(i))).replace("\n","");t.getPaint().getTextBounds(line,0,line.length(),b);float x=at[0]+t.getCompoundPaddingLeft()-t.getScrollX()+l.getLineLeft(i),y=at[1]+t.getBaseline()-t.getScrollY()+l.getLineBaseline(i)-l.getLineBaseline(0);ink[0]=Math.min(ink[0],x+b.left);ink[1]=Math.min(ink[1],y+b.top);ink[2]=Math.max(ink[2],x+b.right);ink[3]=Math.max(ink[3],y+b.bottom);}
   boolean fits=ink[0]>=visible.left&&ink[1]>=visible.top&&ink[2]<=visible.right&&ink[3]<=visible.bottom;
   observations.put(new JSONObject().put("panel",panel).put("text",text).put("ink",new JSONArray(ink)).put("visible",new JSONArray(new int[]{visible.left,visible.top,visible.right,visible.bottom})).put("padding",new JSONArray(new int[]{t.getPaddingLeft(),t.getPaddingTop(),t.getPaddingRight(),t.getPaddingBottom()})).put("layout_height",l.getHeight()).put("view_height",t.getHeight()).put("baseline",t.getBaseline()).put("text_sp",sizeSp(t)).put("fits",fits));
  }}
  if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),panel);
 }
 public void testSpecifiedStyleDefects()throws Exception {
  for(String theme:new String[]{"dark","light"}){
   inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putString("keyboard_theme",theme).commit();main(()->{call(service,"onStartInputView",call(service,"getCurrentInputEditorInfo"),true);return null;});page("dual_hand");
   for(String panel:new String[]{"clipboard","commands"}){main(()->{Object selected=null;for(Object x:api("SimonIMEService$Panel").getEnumConstants())if(x.toString().equals(panel.equals("clipboard")?"CLIPBOARD":"COMMANDS"))selected=x;call(service,"showPanel",selected);return null;});inst.waitForIdleSync();main(()->{collect(root,panel+"/"+theme);return null;});main(()->{call(service,"closePanel");return null;});}
   if(theme.equals("light")){main(()->{Object b=call(dual(),"buffer");call(b,"clearBuffer");for(int i=0;i<9;i++)for(String key:new String[]{"1","A"}){long time=1000L+i*400+(key.equals("A")?100:0);Object stroke=call(b,"begin",key,time);call(b,"end",stroke,"tap",false);call(b,"drain",time+100);}call(dual(),"refresh");return null;});
    for(String state:new String[]{"over8","ai_shown"}){if(state.equals("ai_shown"))main(()->{call(call(dual(),"buffer"),"applyAi","一".repeat(9));call(dual(),"refresh");return null;});int color=main(()->((TextView)((Map<?,?>)((Map<?,?>)field(dual(),"labels")).get("core")).get("down")).getCurrentTextColor());observations.put(new JSONObject().put("state",state).put("ai_color",color).put("expected_6891",-16223141).put("fits",color==-16223141));}
   }
  }
  save("fix5-style-defects.json",observations);assertTrue("must observe specified buttons and both AI states",observations.length()>=10);
  for(int i=0;i<observations.length();i++)assertTrue(observations.getJSONObject(i).toString(),observations.getJSONObject(i).getBoolean("fits"));
 }
}
