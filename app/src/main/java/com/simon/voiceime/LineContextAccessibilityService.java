package com.simon.voiceime;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;

/** On-demand visible LINE context. Never clicks, logs text, or stores conversations. */
public final class LineContextAccessibilityService extends AccessibilityService {
    static final String LINE_PACKAGE = "jp.naver.line.android";
    private static volatile LineContextAccessibilityService instance;
    private volatile long windowEpoch,contentSerial;
    static final class Snapshot {
        final JSONObject history;
        final LineContextAccessibilityService owner;
        final long epoch,content;
        Snapshot(JSONObject history, LineContextAccessibilityService owner, long epoch) {
            this.history=history; this.owner=owner; this.epoch=epoch; this.content=owner.contentSerial;
        }
        boolean current() { return instance==owner && owner.windowEpoch==epoch; }
        boolean contentCurrent() { return current() && owner.contentSerial==content; }
    }
    @Override protected void onServiceConnected() { instance=this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Window switches invalidate lifetime; content redraws are checked by fresh snapshot equality.
        if(event!=null && LINE_PACKAGE.contentEquals(event.getPackageName()==null?"":event.getPackageName())) {
            contentSerial++;
            if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)windowEpoch++;
        }
    }
    @Override public void onInterrupt() { windowEpoch++; }
    @Override public void onDestroy() { windowEpoch++; if(instance==this)instance=null; super.onDestroy(); }
    static Snapshot capture() {
        LineContextAccessibilityService service=instance;
        if(service==null)return null;
        long epoch=service.windowEpoch;
        AccessibilityNodeInfo root=null;
        try {
            for(AccessibilityWindowInfo window:service.getWindows()) {
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                AccessibilityNodeInfo candidate=window.getRoot();
                Rect visibleBounds=new Rect();if(candidate!=null)candidate.getBoundsInScreen(visibleBounds);
                if(candidate!=null && candidate.isVisibleToUser() && !visibleBounds.isEmpty()
                        && LINE_PACKAGE.contentEquals(candidate.getPackageName()==null?"":candidate.getPackageName())) {
                    if(root!=null) {candidate.recycle();return null;} // Two visible LINE windows are ambiguous.
                    root=candidate;
                } else if(candidate!=null)candidate.recycle();
            }
            if(root==null)return null;
            Rect screen=new Rect();root.getBoundsInScreen(screen);
            if(screen.width()<=0||screen.height()<=0)return null;
            ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();
            java.util.IdentityHashMap<AccessibilityNodeInfo,Boolean> inMessages=new java.util.IdentityHashMap<>();
            queue.add(root);inMessages.put(root,false);root=null;
            ArrayList<JSONObject> messages=new ArrayList<>();
            String title="";int visited=0;
            // ponytail: bounded visible-node heuristic; device fixtures are required before stronger sender claims.
            while(!queue.isEmpty() && visited++<600) {
                AccessibilityNodeInfo node=queue.removeFirst();
                String className=node.getClassName()==null?"":node.getClassName().toString();
                boolean messageArea=Boolean.TRUE.equals(inMessages.remove(node))||className.contains("RecyclerView")||className.contains("ListView");
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                String text=node.getText()==null?"":node.getText().toString().trim();
                boolean visible=node.isVisibleToUser()&&!node.isEditable();
                if(visible&&messageArea&&text.length()>2000) {
                    node.recycle();while(!queue.isEmpty())queue.removeFirst().recycle();return null;
                }
                if(visible&&!text.isEmpty()&&text.length()<=2000&&"android.widget.TextView".contentEquals(node.getClassName()==null?"":node.getClassName())) {
                    if(bounds.top>=screen.top&&bounds.bottom<screen.top+screen.height()*0.14&&title.isEmpty()&&!titleNoise(text)) title=text;
                    else if(messageArea&&bounds.bottom>screen.top&&bounds.top<screen.bottom&&!noise(text)) {
                        // Geometry cannot prove who spoke. Preserve uncertainty instead of inventing ownership.
                        messages.add(new JSONObject().put("sender","unknown").put("text",text).put("y",bounds.top));
                    }
                }
                if(!node.isEditable())for(int i=0;i<node.getChildCount();i++) {
                    if(queue.size()>=600) {node.recycle();while(!queue.isEmpty())queue.removeFirst().recycle();return null;}
                    AccessibilityNodeInfo child=node.getChild(i);if(child!=null){queue.add(child);inMessages.put(child,messageArea);}
                }
                node.recycle();
            }
            boolean truncated=!queue.isEmpty();
            while(!queue.isEmpty())queue.removeFirst().recycle();
            if(truncated)return null;
            messages.sort(Comparator.comparingInt(m->m.optInt("y")));
            JSONArray recent=new JSONArray();
            for(int i=Math.max(0,messages.size()-5);i<messages.size();i++) { JSONObject m=messages.get(i);m.remove("y");recent.put(m); }
            int textSize=0;for(int i=0;i<recent.length();i++)textSize+=recent.getJSONObject(i).getString("text").length();
            if(recent.length()==0||title.isEmpty()||title.length()>200||textSize>6000||service.windowEpoch!=epoch)return null;
            return new Snapshot(new JSONObject().put("package_name",LINE_PACKAGE).put("chat_title",title).put("messages",recent),service,epoch);
        } catch(Exception unavailable) { return null; }
        finally { if(root!=null)root.recycle(); }
    }
    private static boolean titleNoise(String text) {
        return text.matches("^\\d+(人|位)?$") || text.equals("搜尋") || text.equals("通話") || text.equals("免費通話") || text.equals("聊天") || text.equals("好友") || text.equals("主頁") || text.equalsIgnoreCase("LINE")
            || text.equalsIgnoreCase("Search") || text.equalsIgnoreCase("Call") || text.equalsIgnoreCase("Free call");
    }
    private static boolean noise(String text) {
        return text.equals("已讀")||text.equals("Read")||text.matches("^(上午|下午|AM|PM)?\\s*\\d{1,2}:\\d{2}$")
            ||text.matches("^\\d{4}[年/.-]\\d{1,2}[月/.-]\\d{1,2}日?.*$");
    }
}
