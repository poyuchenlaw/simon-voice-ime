package com.simon.voiceime;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;

/** Reads only explicitly marked incoming message nodes; ambiguous screens yield no hint. */
public final class ConversationContextService extends AccessibilityService {
 private SharedPreferences prefs;
 private final SharedPreferences.OnSharedPreferenceChangeListener changed=(p,key)->{
  if("local_screen_context".equals(key))LocalConversationContext.clear();
 };
 @Override protected void onServiceConnected(){
  LocalConversationContext.clear();prefs=getSharedPreferences("simon_ime_prefs",MODE_PRIVATE);
  prefs.registerOnSharedPreferenceChangeListener(changed);
 }
 @Override public void onAccessibilityEvent(AccessibilityEvent event){
  if(prefs==null||!prefs.getBoolean("local_screen_context",false)){LocalConversationContext.clear();return;}
  // Invalidate first on every event: app/window/focus switches never reuse old text.
  LocalConversationContext.clear();AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
  ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(root);String latest="";String owner=root.getPackageName()==null?"":root.getPackageName().toString();boolean unsafe=false;
  try {
   if(owner.equals(getPackageName())||owner.isEmpty())return;
   int visited=0;
   while(!nodes.isEmpty()&&visited++<200){
    AccessibilityNodeInfo node=nodes.removeFirst();
    try {
     if(node.isPassword()){unsafe=true;break;}
     if(!node.isVisibleToUser())continue;
     String id=node.getViewIdResourceName(),description=node.getContentDescription()==null?"":node.getContentDescription().toString();
     // Semantic evidence of direction is mandatory. Never guess from screen position.
     boolean incoming=id!=null&&(id.endsWith(":id/incoming_message")||id.endsWith(":id/message_in"))
         ||description.startsWith("收到的訊息：")||description.startsWith("Incoming message:");
     if(incoming&&!node.isEditable()&&node.getText()!=null)latest=node.getText().toString();
     for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)nodes.addLast(child);}
    }finally{node.recycle();}
   }
   if(!unsafe&&nodes.isEmpty()&&!latest.isEmpty())LocalConversationContext.update(owner,latest,SystemClock.elapsedRealtime());
  }finally{while(!nodes.isEmpty())nodes.removeFirst().recycle();}
 }
 @Override public void onInterrupt(){LocalConversationContext.clear();}
 @Override public void onDestroy(){LocalConversationContext.clear();if(prefs!=null)prefs.unregisterOnSharedPreferenceChangeListener(changed);super.onDestroy();}
}
