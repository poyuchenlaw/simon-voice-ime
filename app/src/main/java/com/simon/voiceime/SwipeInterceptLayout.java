package com.simon.voiceime;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/** Only wraps keyboard pages, leaving the clipboard panel's gestures untouched. */
public class SwipeInterceptLayout extends LinearLayout {
    public interface OnSwipeListener {
        void onSwipe(KeyboardPager.Direction direction);
    }

    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean excluded;
    private OnSwipeListener listener;

    public SwipeInterceptLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setOnSwipeListener(OnSwipeListener listener) {
        this.listener = listener;
    }

    private void begin(MotionEvent event) {
        downX = event.getX();
        downY = event.getY();
        excluded = listener == null;
        View mic = findViewById(R.id.btnMic);
        if (mic != null && mic.getVisibility() == VISIBLE) {
            Rect bounds = new Rect();
            mic.getDrawingRect(bounds);
            offsetDescendantRectToMyCoords(mic, bounds);
            excluded |= bounds.contains((int) downX, (int) downY);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                excluded = true;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!excluded) {
                    float dx = Math.abs(event.getX() - downX);
                    float dy = Math.abs(event.getY() - downY);
                    return dx > touchSlop && dx >= 1.5f * dy;
                }
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) begin(event);
        if (excluded) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                excluded = true;
                break;
            case MotionEvent.ACTION_UP:
                float minDistance = Math.max(56f * getResources().getDisplayMetrics().density,
                        0.22f * getWidth());
                SwipeGestureJudge.Result result = SwipeGestureJudge.judge(
                        event.getX() - downX, event.getY() - downY, minDistance, touchSlop);
                if (result != SwipeGestureJudge.Result.NONE && listener != null) {
                    listener.onSwipe(result == SwipeGestureJudge.Result.LEFT
                            ? KeyboardPager.Direction.LEFT : KeyboardPager.Direction.RIGHT);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                excluded = true;
                break;
            default:
                break;
        }
        return true;
    }
}
