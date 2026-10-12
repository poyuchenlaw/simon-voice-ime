package com.simon.voiceime;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import com.simon.voiceime.App688Harness.android;
import com.simon.voiceime.App688Harness.DualHandLabelView;

/** R4 / RUN1-1: baseline letter size must also contain unchanged bottom labels. */
public class App690ABottomLabelsTest {
    private Object invoke(App688Harness h, String name, Class<?> type, Object value) {
        try {
            java.lang.reflect.Method method = type == null ? App688Harness.class.getDeclaredMethod(name)
                    : App688Harness.class.getDeclaredMethod(name, type);
            method.setAccessible(true);
            return type == null ? method.invoke(h) : method.invoke(h, value);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private int dp(App688Harness h, float value) { return (int) invoke(h, "dp", float.class, value); }
    private List<String> violations(App688Harness h, float sp, int[] columns) {
        List<String> failures = new ArrayList<>();
        h.renderState = null;
        invoke(h, "renderLabels", null, null);
        String[] keys = {"tools", "symbols", "clipboard", "core"};
        for (String key : keys) {
            int slot = List.of("tools", "symbols", "Ø", "clipboard", "core").indexOf(key);
            float width = columns[slot + 2] - 2 * dp(h, 2);
            float height = dp(h, h.rowDp) - 2 * dp(h, 2);
            List<float[]> occupied = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (App688Harness.TextView label : h.keys.get(key).children) {
                if (label.visibility == App688Harness.View.GONE || label.value.isEmpty()) continue;
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setTextSize(sp * android.util.DisplayMetrics.scaledDensity);
                float[] ink = DualHandLabelView.extent(label.value, paint);
                // R4 explicitly exempts icons; baseline measures these in a 16dp frame.
                if (label.value.equals("⌫") || label.value.equals("⚙"))
                    ink = new float[]{16 * android.util.DisplayMetrics.density, 16 * android.util.DisplayMetrics.density};
                float w = ink[0], ht = ink[1], x = (width - w) / 2, y = (height - ht) / 2;
                int gravity = label.layout.gravity, margin = label.layout.margin;
                if ((gravity & App688Harness.Gravity.LEFT) != 0) x = margin;
                if ((gravity & App688Harness.Gravity.RIGHT) != 0) x = width - margin - w;
                if ((gravity & App688Harness.Gravity.TOP) != 0) y = margin;
                if ((gravity & App688Harness.Gravity.BOTTOM) != 0) y = height - margin - ht;
                String text = label.value.replace('\n', '/');
                if (x < 0 || y < 0 || x + w > width || y + ht > height)
                    failures.add(key + ":clipped:" + text + ":ink=" + w + "x" + ht + ":key=" + width + "x" + height);
                for (int i = 0; i < occupied.size(); i++) {
                    float[] b = occupied.get(i);
                    if (x < b[2] && x + w > b[0] && y < b[3] && y + ht > b[1])
                        failures.add(key + ":overlap:" + texts.get(i) + "|" + text);
                }
                occupied.add(new float[]{x, y, x + w, y + ht});
                texts.add(text);
            }
        }
        return failures;
    }

    @Test public void unchangedBottomLabelsFitBaselineLetterSizeAcrossGrid() throws Exception {
        List<String> failed = new ArrayList<>();
        System.out.println("GRID,width_dp,density,font_scale,baseline_sp,requested_sp,bottom_violations");
        try {
            for (int width : new int[]{320, 360, 400, 600, 800})
                for (float density : new float[]{1.3f, 1.5f, 2.625f, 2.75f, 3f})
                    for (float scale : new float[]{1f, 1.3f}) {
                        android.util.DisplayMetrics.density = density;
                        android.util.DisplayMetrics.scaledDensity = density * scale;
                        App688Harness h = new App688Harness();
                        int px = Math.round(width * density);
                        float sp = (float) invoke(h, "fitLabelSize", int.class, px);
                        List<String> violations = violations(h, sp, (int[]) invoke(h, "columnWidths", int.class, px));
                        System.out.println("GRID," + width + "," + density + "," + scale + "," + sp + "," + sp + "," + violations.size());
                        for (String violation : violations)
                            failed.add(width + "dp density=" + density + " scale=" + scale + " sp=" + sp + " " + violation);
                    }
        } finally {
            android.util.DisplayMetrics.density = android.util.DisplayMetrics.scaledDensity = 1;
        }
        System.out.println("BOTTOM_FAILURES=" + failed.size());
        for (String failure : failed) System.out.println("BOTTOM_FAILURE " + failure);
        assertTrue("R4-⑤-4 / RUN1-1 stop condition: " + (failed.isEmpty() ? "none" : failed.get(0)), failed.isEmpty());
    }
}
