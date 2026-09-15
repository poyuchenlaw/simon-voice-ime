package com.simon.voiceime;

/** Length-only safety net; never selects or replaces the committed result. */
public final class TextLossGuard {
    public static final int MIN_EXTRA_CHARS = 10;
    public static final double MIN_RATIO = 1.5;
    private TextLossGuard() {}

    /** Same cumulative overlap removal used by the on-device preview. */
    public static String joinDedup(java.util.List<String> segments, int maxOverlap) {
        StringBuilder joined = new StringBuilder();
        for (String segment : segments) {
            joined.append(dedupOverlapHead(joined.toString(), segment, maxOverlap));
        }
        return joined.toString();
    }

    public static String dedupOverlapHead(String prev, String seg, int maxWindow) {
        if (prev == null || prev.isEmpty() || seg == null || seg.isEmpty()) return seg == null ? "" : seg;
        int max = Math.min(maxWindow, Math.min(prev.length(), seg.length()));
        for (int k = max; k > 0; k--) {
            if (prev.regionMatches(prev.length() - k, seg, 0, k)) return seg.substring(k);
        }
        return seg;
    }

    public static boolean shouldRescue(int committedLen, int candidateLen) {
        return shouldRescue(committedLen, candidateLen, MIN_EXTRA_CHARS, MIN_RATIO);
    }

    public static boolean shouldRescue(int committedLen, int candidateLen, int extra, double ratio) {
        int minExtra = extra > 0 ? extra : MIN_EXTRA_CHARS;
        double minRatio = Double.isFinite(ratio) && ratio >= 1 ? ratio : MIN_RATIO;
        return committedLen >= 0 && candidateLen >= 0
                && (long) candidateLen - committedLen >= minExtra
                && candidateLen >= committedLen * minRatio;
    }
}
