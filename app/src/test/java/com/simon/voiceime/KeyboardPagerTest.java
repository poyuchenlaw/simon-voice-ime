package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.simon.voiceime.KeyboardPager.KeyboardMode.*;
import static com.simon.voiceime.KeyboardPager.Direction.*;

public class KeyboardPagerTest {
    @Test public void threePagesCycleInBothDirections() {
        assertEquals(BOPOMOFO, KeyboardPager.next(VOICE, LEFT));
        assertEquals(ENGLISH, KeyboardPager.next(VOICE, RIGHT));
        assertEquals(ENGLISH, KeyboardPager.next(BOPOMOFO, LEFT));
        assertEquals(VOICE, KeyboardPager.next(BOPOMOFO, RIGHT));
        assertEquals(VOICE, KeyboardPager.next(ENGLISH, LEFT));
        assertEquals(BOPOMOFO, KeyboardPager.next(ENGLISH, RIGHT));
    }

    @Test public void numbersIsOutsideSwipeCycle() {
        assertThrows(IllegalArgumentException.class, () -> KeyboardPager.next(NUMBERS, LEFT));
        assertThrows(IllegalArgumentException.class, () -> KeyboardPager.next(NUMBERS, RIGHT));
    }

    @Test public void homeKeysAndTopSwitchMatchV631Contract() throws Exception {
        String xml = readProjectFile("app/src/main/res/layout/keyboard_view.xml");
        String baseline = readProjectFile("evidence/v631/before/main/res/layout/keyboard_view.xml");
        String[] ids = {"btnSpace", "btnComma", "btnPeriod", "btnFullWidth", "btnHalfWidth", "btnBackspace", "btnEnter"};
        String[] labels = {"␣", "，", "。", "全", "半", "⌫", "↵"};
        int previous = -1;
        for (String id : ids) {
            int index = xml.indexOf("android:id=\"@+id/" + id + "\"");
            assertTrue("missing or out-of-order " + id, index > previous);
            int viewStart = xml.lastIndexOf("<TextView", index);
            int viewEnd = xml.indexOf("/>", index);
            String view = xml.substring(viewStart, viewEnd);
            assertTrue(id + " must be 38dp high", view.contains("android:layout_height=\"38dp\""));
            assertTrue(id + " must be a weighted row key", view.contains("android:layout_width=\"0dp\"")
                    && view.contains("android:layout_weight=\"1\"") && view.contains("android:clickable=\"true\""));
            String expectedLabel = labels[previous < 0 ? 0 : java.util.Arrays.asList(ids).indexOf(id)];
            assertTrue(id + " label mismatch", view.contains("android:text=\"" + expectedLabel + "\""));
            previous = index;
        }
        assertTrue(xml.contains("android:id=\"@+id/btnSwitchIME\""));
        assertTrue(xml.contains("android:text=\"EN\""));
        assertTrue("voice keyboard must retain horizontal mic layout",
                xml.contains("android:orientation=\"horizontal\""));
        assertTrue("voice key area must retain weight 3", xml.contains("android:layout_weight=\"3\""));
        int voiceIndex = xml.indexOf("android:id=\"@+id/voiceKeyboard\"");
        int micIndex = xml.indexOf("android:id=\"@+id/btnMic\"", voiceIndex);
        int voiceEnd = xml.indexOf("</com.simon.voiceime.SwipeInterceptLayout>", voiceIndex);
        assertTrue("mic must remain in right-side slot spanning both rows",
                micIndex > voiceIndex && micIndex < voiceEnd
                        && xml.lastIndexOf("android:id=\"@+id/btnEnter\"", micIndex) < micIndex
                        && xml.indexOf("android:layout_height=\"match_parent\"", micIndex) < voiceEnd
                        && xml.indexOf("android:textSize=\"28sp\"", micIndex) < voiceEnd);
        assertRowHeightMatchesBaseline(xml, baseline, "btnSwitchIME", "38dp");
        assertRowHeightMatchesBaseline(xml, baseline, "btnSpace", "42dp");
        String english = readProjectFile("app/src/main/res/layout/keyboard_english.xml");
        String numbers = readProjectFile("app/src/main/res/layout/keyboard_numbers.xml");
        assertTrue("English page must provide 123 entry", english.contains("android:tag=\"key:toNumbers\""));
        assertTrue("symbols page must provide return to home", numbers.contains("android:tag=\"key:toVoice\""));
    }

    private static void assertRowHeightMatchesBaseline(String xml, String baseline, String keyId, String height) {
        String marker = "android:id=\"@+id/" + keyId + "\"";
        int currentKey = xml.indexOf(marker);
        int baselineKey = baseline.indexOf(marker);
        assertTrue("missing row key " + keyId, currentKey >= 0 && baselineKey >= 0);
        int currentRow = xml.lastIndexOf("<LinearLayout", currentKey);
        int baselineRow = baseline.lastIndexOf("<LinearLayout", baselineKey);
        String expected = "android:layout_height=\"" + height + "\"";
        assertTrue("row for " + keyId + " must match baseline height " + height,
                xml.indexOf(expected, currentRow) < xml.indexOf(">", currentRow)
                        && baseline.indexOf(expected, baselineRow) < baseline.indexOf(">", baselineRow));
    }

    @Test public void fullAndHalfWidthPopupSymbolsMatchLegacyContent() {
        assertArrayEquals(new String[]{"，", "。", "、", "？", "！", "：", "；", "「", "」", "『", "』", "（", "）",
                "《", "》", "〈", "〉", "─", "…", "～", "％", "＃", "＠", "＆", "＊"}, SymbolPopupSpec.FULL_WIDTH);
        assertArrayEquals(new String[]{",", ".", "?", "!", ":", ";", "'", "\"", "(", ")", "[", "]", "{", "}", "/",
                "\\", "-", "_", "~", "@", "#", "%", "&", "*", "+", "=", "<", ">"}, SymbolPopupSpec.HALF_WIDTH);
    }

    private static String readProjectFile(String path) throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(path);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), java.nio.charset.StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new java.nio.file.NoSuchFileException(path);
    }

}
