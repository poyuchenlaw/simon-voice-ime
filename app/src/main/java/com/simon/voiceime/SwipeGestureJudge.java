package com.simon.voiceime;

/** Distances are pixels; callers supply the density/width-dependent threshold. */
public final class SwipeGestureJudge {
    public enum Result { NONE, LEFT, RIGHT }

    private SwipeGestureJudge() {}

    public static Result judge(float dx, float dy, float minDistancePx, float touchSlopPx) {
        float distance = Math.abs(dx);
        if (distance <= touchSlopPx || distance < minDistancePx
                || distance < 1.5f * Math.abs(dy)) {
            return Result.NONE;
        }
        return dx < 0 ? Result.LEFT : Result.RIGHT;
    }
}
