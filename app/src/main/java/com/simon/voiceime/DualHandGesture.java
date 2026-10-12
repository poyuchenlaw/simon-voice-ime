package com.simon.voiceime;

/** Coordinates and path length are dp; all calibration parameters live here. */
public final class DualHandGesture {
    public static final float SWIPE_DP = 18, FAST_DRIFT_DP = 28, AXIS_RATIO = 1.4f;
    public static final long FAST_MS = 80, RACE_MS = 40;
    // Tunable definition of a linear fast stroke: endpoint distance / travelled distance.
    public static final float LINEAR_RATIO = .85f;
    public static final float RIGHT_HAND_RIGHT_DP = SWIPE_DP / 2;
    private DualHandGesture() {}
    public static final class Result {
        public final String direction;
        public final boolean ambiguous;
        Result(String direction, boolean ambiguous) { this.direction=direction; this.ambiguous=ambiguous; }
    }
    public static Result classify(String key, float dx, float dy, long ms, float travelled, int samples) {
        Result normal = classify(dx, dy, ms, travelled, samples);
        boolean rightHand = DualHandTable.isRight(key) || "core".equals(key) || "clipboard".equals(key);
        if (rightHand && dx > RIGHT_HAND_RIGHT_DP && dx >= Math.abs(dy))
            return new Result("right", Math.abs(dx) < AXIS_RATIO * Math.abs(dy));
        return normal;
    }
    public static Result classify(float dx, float dy, long ms, float travelled) {
        return classify(dx,dy,ms,travelled,0);
    }
    public static Result classify(float dx, float dy, long ms, float travelled, int samples) {
        if (!Float.isFinite(dx)||!Float.isFinite(dy)||!Float.isFinite(travelled)||ms<0||travelled<0||samples<0)
            throw new IllegalArgumentException("Invalid gesture measurements");
        float distance=(float)Math.hypot(dx,dy), x=Math.abs(dx), y=Math.abs(dy);
        // A stroke with only two distinct positions has no intermediate path; use its endpoints.
        if(samples==2&&distance>=FAST_DRIFT_DP)travelled=distance;
        if(distance<=SWIPE_DP || ms<FAST_MS && (distance<FAST_DRIFT_DP || distance<LINEAR_RATIO*travelled))
            return new Result("tap",false);
        boolean horizontal=x>=y; // Equal axes deterministically use horizontal; never emit diagonal.
        boolean ambiguous=Math.max(x,y)<AXIS_RATIO*Math.min(x,y);
        return new Result(horizontal?(dx>=0?"right":"left"):(dy>=0?"down":"up"),ambiguous);
    }
}
