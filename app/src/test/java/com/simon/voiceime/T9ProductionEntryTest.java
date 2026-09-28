package com.simon.voiceime;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevents a PROTO1-C-style regression where helper tests passed but onCreate still loaded Rime. */
public class T9ProductionEntryTest {
    private static final String CREATE_CALL = "createT9Engine(";

    @Test public void imeStartupDoesNotEagerlyCreateT9AndOnlyExplicitT9PathCanCreateIt()
            throws IOException {
        String source = readServiceSource();
        String onCreate = methodBody(source, "public void onCreate()");
        assertFalse("onCreate must not eagerly create the T9 engine", onCreate.contains(CREATE_CALL));

        String t9Entry = methodBody(source, "private void showT9Keyboard()");
        assertTrue("the explicit 九 keyboard path must retain lazy engine creation",
                t9Entry.contains(CREATE_CALL));
        String factory = methodBody(source, "private T9Engine createT9Engine()");
        assertTrue("the factory must honor the persisted crash-disable flag",
                factory.contains("T9InitGuard.isDisabled"));

        assertTrue("only the factory declaration and explicit 九 path may mention the factory",
                count(source, CREATE_CALL) == 2 && count(t9Entry, CREATE_CALL) == 1);
    }

    private static String readServiceSource() throws IOException {
        Path modulePath = Paths.get("src/main/java/com/simon/voiceime/SimonIMEService.java");
        Path rootPath = Paths.get("app/src/main/java/com/simon/voiceime/SimonIMEService.java");
        Path sourcePath = Files.exists(modulePath) ? modulePath : rootPath;
        return new String(Files.readAllBytes(sourcePath), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int signatureStart = source.indexOf(signature);
        if (signatureStart < 0) throw new AssertionError("Missing method: " + signature);
        int bodyStart = source.indexOf('{', signatureStart + signature.length());
        if (bodyStart < 0) throw new AssertionError("Missing body: " + signature);
        int depth = 1;
        for (int i = bodyStart + 1; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(bodyStart, i + 1);
        }
        throw new AssertionError("Unclosed method body: " + signature);
    }

    private static int count(String text, String value) {
        int matches = 0;
        for (int from = 0; (from = text.indexOf(value, from)) >= 0; from += value.length()) matches++;
        return matches;
    }
}
