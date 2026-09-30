package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import static com.simon.voiceime.KeyboardPager.KeyboardMode.*;


public class KeyboardPagerTest {
    @Test public void homeControlsRetainContractWithBottomLeftSwitch() throws Exception {
        String xml = readProjectFile("app/src/main/res/layout/keyboard_view.xml");
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
        int voiceEnd = xml.indexOf("</com.simon.voiceime.KeyboardTouchLayout>", voiceIndex);
        assertTrue("mic must remain in right-side slot spanning both rows",
                micIndex > voiceIndex && micIndex < voiceEnd
                        && xml.lastIndexOf("android:id=\"@+id/btnEnter\"", micIndex) < micIndex
                        && xml.indexOf("android:layout_height=\"match_parent\"", micIndex) < voiceEnd
                        && xml.indexOf("android:textSize=\"28sp\"", micIndex) < voiceEnd);
        assertTrue("switch belongs to bottom row", xml.indexOf("@+id/btnSwitchIME") > xml.indexOf("<!-- Row 2"));
        assertRowHeight(xml, "btnSpace", "42dp");
        String english = readProjectFile("app/src/main/res/layout/keyboard_english.xml");
        String numbers = readProjectFile("app/src/main/res/layout/keyboard_numbers.xml");
        assertTrue("English page must provide 123 entry", english.contains("android:tag=\"key:toNumbers\""));
        assertTrue("symbols page must provide return to home", numbers.contains("android:tag=\"key:toVoice\""));
    }

    private static void assertRowHeight(String xml, String keyId, String height) {
        int key = xml.indexOf("android:id=\"@+id/" + keyId + "\"");
        int row = xml.lastIndexOf("<LinearLayout", key);
        assertTrue("missing row key " + keyId, key >= 0);
        assertTrue("row for " + keyId + " must retain " + height,
                xml.indexOf("android:layout_height=\"" + height + "\"", row) < xml.indexOf(">", row));
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
