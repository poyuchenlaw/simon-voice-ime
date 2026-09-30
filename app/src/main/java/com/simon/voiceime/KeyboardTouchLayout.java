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

    private KeyTarget nearestKey(float x, float y, long downTime) {
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
        return y < top ? null : target;
    }

    private void dispatchKey(KeyTarget target, MotionEvent event, int pointer, int action) {
        float x = event.getX(pointer), y = event.getY(pointer);
        float screenX = x + event.getRawX() - event.getX();
        float screenY = y + event.getRawY() - event.getY();
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
            target.key.dispatchTouchEvent(local);
        } finally {
            local.recycle();
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            touchKeys.clear();
            KeyTarget target = nearestKey(event.getX(), event.getY(), event.getDownTime());
            routingKeys = target != null;
            if (target != null) touchKeys.put(event.getPointerId(0), target);
        }
        if (!routingKeys) return super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            KeyTarget target = nearestKey(event.getX(index), event.getY(index), event.getEventTime());
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
        if (action == MotionEvent.ACTION_POINTER_UP) touchKeys.remove(event.getPointerId(index));
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchKeys.clear();
            routingKeys = false;
        }
        return true;
    }
}
