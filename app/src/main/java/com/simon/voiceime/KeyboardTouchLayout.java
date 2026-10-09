package com.simon.voiceime;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Visual key gaps retain touch coverage. A touch sequence belongs to its DOWN key. */
public class KeyboardTouchLayout extends LinearLayout {
    private static final class KeyTarget {
        final View key;
        final Rect bounds;
        final long downTime;
        KeyTarget(View key, Rect bounds, long downTime) {
            this.key = key; this.bounds = bounds; this.downTime = downTime;
        }
    }
    private final Map<Integer, KeyTarget> touchKeys = new HashMap<>();
    private boolean routingKeys;
    private final int[] currentOrigin = new int[2];

    public KeyboardTouchLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private void collectKeys(ViewGroup group, List<View> tagged, List<View> clickable) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            Object tag = child.getTag();
            if (tag != null && tag.toString().startsWith("key:")) tagged.add(child);
            else if (child instanceof ViewGroup) collectKeys((ViewGroup) child, tagged, clickable);
            else if (child.isClickable()) clickable.add(child);
        }
    }

    private KeyTarget nearestKey(float x,float y,long downTime){return nearestKey(x,y,downTime,true);}
    private KeyTarget nearestKey(float x, float y, long downTime, boolean keyAreaOnly) {
        List<View> tagged = new ArrayList<>(), clickable = new ArrayList<>();
        collectKeys(this, tagged, clickable);
        List<View> keys = tagged.isEmpty() ? clickable : tagged;
        float nearest = Float.POSITIVE_INFINITY;
        int top = Integer.MAX_VALUE;
        KeyTarget target = null;
        for (View key : keys) {
            Rect bounds = new Rect();
            key.getDrawingRect(bounds);
            offsetDescendantRectToMyCoords(key, bounds);
            // Unmeasured keys cannot own a touch or raise the candidate boundary.
            if (bounds.isEmpty()) continue;
            top = Math.min(top, bounds.top);
            float dx = x - (bounds.left + key.getWidth() / 2f);
            float dy = y - (bounds.top + key.getHeight() / 2f);
            float distance = dx * dx + dy * dy;
            if (distance < nearest) {
                nearest = distance;
                target = new KeyTarget(key, bounds, downTime);
            }
        }
        // Preview/candidate bars retain native scrolling and clicks.
        return keyAreaOnly && y < top ? null : target;
    }

    private void dispatchKey(KeyTarget target, MotionEvent event, int pointer, int action) {
        float deliveredX = event.getX(pointer), deliveredY = event.getY(pointer);
        float x = deliveredX + event.getRawX() - event.getX() - currentOrigin[0];
        float y = deliveredY + event.getRawY() - event.getY() - currentOrigin[1];
        float screenX = x + currentOrigin[0];
        float screenY = y + currentOrigin[1];
        // A separate single-pointer stream lets Android's existing click/long-press
        // listeners handle each thumb, including when the primary pointer lifts first.
        MotionEvent local = MotionEvent.obtain(target.downTime, event.getEventTime(),
                action, screenX, screenY, event.getMetaState());
        local.setSource(event.getSource());
        float keyX = Math.max(0, Math.min(target.key.getWidth() - 1, x - target.bounds.left));
        float keyY = Math.max(0, Math.min(target.key.getHeight() - 1, y - target.bounds.top));
        // Offset only the local frame; raw screen coordinates remain physical touches.
        local.offsetLocation(keyX - screenX, keyY - screenY);
        try {
            long dispatchStarted=android.os.SystemClock.uptimeMillis();
            boolean consumed = target.key.dispatchTouchEvent(local);
            received(target.key,local,consumed,false);
            long delay = Math.max(0L, android.os.SystemClock.uptimeMillis()-event.getEventTime());
            if(action==MotionEvent.ACTION_UP) {
                long completed=android.os.SystemClock.uptimeMillis();
                long duration=Math.max(0L,completed-target.downTime);
                if(sampleKeyTiming(duration)) {
                    ImeTelemetry telemetry=ImeTelemetry.get();
                    if(telemetry!=null)telemetry.recordKeyTiming(duration,Math.max(0L,dispatchStarted-event.getEventTime()),Math.max(0L,completed-dispatchStarted),consumed);
                }
            }
            if (!consumed) missedTouch(delay,Math.round(x),Math.round(y),target);
            else if (action == MotionEvent.ACTION_UP && delay >= 197) touchEvent("touch_delay", delay, true);
            else if (action == MotionEvent.ACTION_CANCEL) touchEvent("touch_cancelled", delay, false);
        } finally {
            local.recycle();
        }
    }

    private static long diagnosticMinute=-1;
    private static int diagnosticCount;
    private int diagnosticRow,diagnosticRowId,diagnosticX,diagnosticY;
    private long diagnosticDown;
    private boolean diagnosticGesture;
    private org.json.JSONObject diagnosticState;
    private SimonIMEService diagnosticService;
    private void beginDiagnostic(MotionEvent event){
        diagnosticRow=0;diagnosticGesture=false;diagnosticDown=event.getDownTime();
        int[] ids={R.id.boStreamPreviewScroll,R.id.boWordCandidateScroll,R.id.boCandidateScroll};
        for(int i=0;i<ids.length;i++){
            View row=findViewById(ids[i]);Rect visible=new Rect();
            if(row==null||!row.isShown()||!row.getLocalVisibleRect(visible))continue;
            int[] origin=new int[2];row.getLocationOnScreen(origin);visible.offset(origin[0],origin[1]);
            if(visible.contains(Math.round(event.getRawX()),Math.round(event.getRawY()))){diagnosticRow=i+1;diagnosticRowId=ids[i];break;}
        }
        android.content.Context c=getContext();
        while(c instanceof android.content.ContextWrapper&&!(c instanceof SimonIMEService))c=((android.content.ContextWrapper)c).getBaseContext();
        if(diagnosticRow==0||!(c instanceof SimonIMEService))return;
        diagnosticService=(SimonIMEService)c;diagnosticState=diagnosticService.candidateDiagnosticState();
        diagnosticGesture=diagnosticState!=null;
    }
    private void diagnostic(View receiver,String phase,boolean consumed,boolean clicked){
        if(!diagnosticGesture)return;
        diagnosticState=diagnosticService.candidateDiagnosticState();
        if(diagnosticState==null)return;
        long minute=android.os.SystemClock.uptimeMillis()/60000;
        if(minute!=diagnosticMinute){diagnosticMinute=minute;diagnosticCount=0;}
        if(diagnosticCount>=120)return;
        diagnosticCount++;
        try{
            org.json.JSONObject e=new org.json.JSONObject(diagnosticState.toString())
                .put("phase",phase).put("row",diagnosticRow).put("row_id",diagnosticRowId).put("down_uptime_ms",diagnosticDown).put("x",diagnosticX).put("y",diagnosticY)
                .put("route",routingKeys?"key":"native").put("receiver_class",receiver.getClass().getName())
                .put("receiver_id",receiver.getId()).put("consumed",consumed).put("click_listener",clicked);
            ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry!=null)telemetry.record("candidate_route","bopomofo",e,false);
        }catch(org.json.JSONException error){android.util.Log.w("KeyboardTouchLayout","Touch diagnostic skipped",error);}
    }
    static void received(View view,MotionEvent event,boolean consumed,boolean clicked){
        android.view.ViewParent parent=view.getParent();
        while(parent!=null&&!(parent instanceof KeyboardTouchLayout))parent=parent.getParent();
        if(parent instanceof KeyboardTouchLayout){
            KeyboardTouchLayout root=(KeyboardTouchLayout)parent;
            int action=event==null?-1:event.getActionMasked();
            if(clicked||action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)
                root.diagnostic(view,clicked?"click":action==0?"receive_down":action==1?"receive_up":"receive_cancel",consumed,clicked);
        }
    }
    private int keyTimingCount;
    private boolean sampleKeyTiming(long duration) {
        return ++keyTimingCount%20==0||duration>150L;
    }

    private void touchEvent(String step, long ms, boolean ok) {
        ImeTelemetry telemetry=ImeTelemetry.get();
        if (telemetry==null) return;
        try { telemetry.record("key_outcome", "bopomofo", new org.json.JSONObject().put("key","").put("key_to_candidate_ms",org.json.JSONObject.NULL).put("step",step).put("ms",ms).put("ok",ok),false); } catch (org.json.JSONException ignored) {}
    }

    private void missedTouch(long delay,int x,int y,KeyTarget target){
        ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry==null)return;
        Object tag=target==null?null:target.key.getTag();String key=tag instanceof String&&((String)tag).startsWith("key:")?((String)tag).substring(4):"unknown";
        try{telemetry.record("key_outcome","bopomofo",ImeTelemetry.missedTouchFields(delay,x,y,key),false);}
        catch(org.json.JSONException error){android.util.Log.w("KeyboardTouchLayout","Missed touch metadata unavailable",error);}
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        // A moving IME window can leave MotionEvent local coordinates one origin
        // behind. Rebase physical screen coordinates at delivery, then lock DOWN.
        getLocationOnScreen(currentOrigin);
        float currentX=event.getRawX()-currentOrigin[0], currentY=event.getRawY()-currentOrigin[1];
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if(action==MotionEvent.ACTION_DOWN)beginDiagnostic(event);
        diagnosticX=Math.round(currentX);diagnosticY=Math.round(currentY);
        if (action == MotionEvent.ACTION_DOWN) {
            touchKeys.clear();
            KeyTarget target = nearestKey(currentX, currentY, event.getDownTime());
            routingKeys = target != null;
            if (target != null) {
                touchKeys.put(event.getPointerId(0), target);
                if (Math.abs(currentX-event.getX())>1 || Math.abs(currentY-event.getY())>1) touchEvent("touch_origin_rebased",0,true);
            }
        }
        if (!routingKeys) {
            boolean consumed=super.dispatchTouchEvent(event);
            if(!consumed&&action==MotionEvent.ACTION_DOWN)missedTouch(Math.max(0L,android.os.SystemClock.uptimeMillis()-event.getEventTime()),Math.round(currentX),Math.round(currentY),nearestKey(currentX,currentY,event.getDownTime(),false));
            if(action==0||action==1||action==3)diagnostic(this,action==0?"route_down":action==1?"route_up":"route_cancel",consumed,false);
            return consumed;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            KeyTarget target = nearestKey(currentX+event.getX(index)-event.getX(), currentY+event.getY(index)-event.getY(), event.getEventTime());
            if (target != null) touchKeys.put(event.getPointerId(index), target);
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) {
            for (Map.Entry<Integer, KeyTarget> entry : touchKeys.entrySet()) {
                int pointer = event.findPointerIndex(entry.getKey());
                if (pointer >= 0) dispatchKey(entry.getValue(), event, pointer, action);
            }
        } else {
            KeyTarget target = touchKeys.get(event.getPointerId(index));
            if (target != null) dispatchKey(target, event, index,
                    action == MotionEvent.ACTION_POINTER_DOWN ? MotionEvent.ACTION_DOWN
                            : action == MotionEvent.ACTION_POINTER_UP ? MotionEvent.ACTION_UP : action);
        }
        if(action==0||action==1||action==3)diagnostic(this,action==0?"route_down":action==1?"route_up":"route_cancel",true,false);
        if (action == MotionEvent.ACTION_POINTER_UP) touchKeys.remove(event.getPointerId(index));
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchKeys.clear();
            routingKeys = false;
        }
        return true;
    }
}
