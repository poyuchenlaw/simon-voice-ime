package com.simon.voiceime;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;

public class KeyboardButtonsTest {
    private org.w3c.dom.Element page(String name) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("app/src/main"))) root = root.getParent();
        return javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(root.resolve("app/src/main/res/layout/" + name + ".xml").toFile()).getDocumentElement();
    }
    @Test public void bottomLeftButtonsHaveRequestedDestinations() throws Exception {
        String[] names = {"keyboard_bopomofo", "keyboard_english", "keyboard_numbers"};
        String[] targets = {"key:toEnglish", "key:toBopomofo", "key:toVoice"};
        String[] labels = {"EN", "注", "🎤"};
        for (int i=0;i<names.length;i++) {
            org.w3c.dom.Element page = page(names[i]);
            org.w3c.dom.Element row = (org.w3c.dom.Element)page.getLastChild().getPreviousSibling();
            org.w3c.dom.Element first = null;
            for (org.w3c.dom.Node n=row.getFirstChild();n!=null;n=n.getNextSibling())
                if(n instanceof org.w3c.dom.Element){first=(org.w3c.dom.Element)n;break;}
            assertEquals(names[i], targets[i], first.getAttribute("android:tag"));
            assertEquals(labels[i], first.getAttribute("android:text"));
            assertEquals("44dp", first.getAttribute("android:layout_width"));
            assertEquals("3dp", page.getAttribute("android:paddingStart"));
        }
    }
    @Test public void voiceHomeSwitchOccupiesSameBottomLeftSlot() throws Exception {
        org.w3c.dom.NodeList views=page("keyboard_view").getElementsByTagName("TextView");
        org.w3c.dom.Element button=null;
        for(int i=0;i<views.getLength();i++) {
            org.w3c.dom.Element view=(org.w3c.dom.Element)views.item(i);
            if("@+id/btnSwitchIME".equals(view.getAttribute("android:id"))) button=view;
        }
        assertNotNull(button);
        assertEquals("44dp",button.getAttribute("android:layout_width"));
        assertEquals("注",button.getAttribute("android:text"));
        org.w3c.dom.Node previous=button.getPreviousSibling();
        while(previous!=null) {assertFalse(previous instanceof org.w3c.dom.Element);previous=previous.getPreviousSibling();}
        org.w3c.dom.Element row=(org.w3c.dom.Element)button.getParentNode();
        assertEquals("42dp",row.getAttribute("android:layout_height"));
        org.w3c.dom.Element voice=(org.w3c.dom.Element)row.getParentNode().getParentNode();
        assertEquals("@+id/voiceKeyboard",voice.getAttribute("android:id"));
        assertEquals("3dp",voice.getAttribute("android:paddingStart"));
        assertEquals("3dp",voice.getAttribute("android:paddingBottom"));
    }
    @Test public void everyTypingKeyHasInsetBorderAndThemeText() throws Exception {
        for(String name:new String[]{"keyboard_bopomofo","keyboard_english"}) {
            org.w3c.dom.NodeList views=page(name).getElementsByTagName("TextView");
            for(int i=0;i<views.getLength();i++) {
                org.w3c.dom.Element key=(org.w3c.dom.Element)views.item(i);
                if(!key.getAttribute("android:tag").startsWith("key:"))continue;
                assertEquals(name+" "+key.getAttribute("android:text"), "@drawable/keyboard_key",key.getAttribute("android:background"));
                assertEquals("@color/keyboard_key_text",key.getAttribute("android:textColor"));
            }
        }
    }
    @Test public void pageSwitchGestureIsRemoved() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("app/src/main"))) root = root.getParent();
        assertFalse("fast horizontal strokes must have no page-switch handler",
                new String(Files.readAllBytes(root.resolve("app/src/main/java/com/simon/voiceime/SimonIMEService.java")), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("setupKeyboardSwipe"));
        assertFalse(Files.exists(root.resolve("app/src/main/java/com/simon/voiceime/SwipeGestureJudge.java")));
    }
}
