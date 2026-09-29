package com.simon.voiceime;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression guard: the phone IME ships only the traditional 41-key Zhuyin page. */
public class NoT9FootprintTest {
    private static final String[] FORBIDDEN = {"t9", "nine-key", "bopomofo_t9_simon"};

    @Test public void productionTreeContainsNoNineKeyCodeOrAssets() throws IOException {
        Path root = projectRoot();
        assertNoForbiddenText(root.resolve("app/src/main"));
        assertNoForbiddenText(root.resolve("app/src/phone"));
        assertNoForbiddenText(root.resolve("scripts"));
        assertNoForbiddenText(root.resolve("app/build.gradle"));
    }

    @Test public void traditionalTouchLearningAndPrivacyGuardRemainActive() throws IOException {
        String source = read(projectRoot().resolve("app/src/main/java/com/simon/voiceime/SimonIMEService.java"));
        assertTrue("protected fields must suppress touch learning",
                source.contains("touchLearning!=null&&!protectedInputField"));
        assertTrue("protected fields must suppress touch telemetry",
                source.contains("imeTelemetry.key(page,key,dx,dy,cx,cy,protectedInputField)"));
        assertTrue("traditional 41-key touches must continue to record offsets",
                source.contains("recordBopomofoTouch"));
    }

    private static void assertNoForbiddenText(Path path) throws IOException {
        try (Stream<Path> paths = Files.isDirectory(path) ? Files.walk(path) : Stream.of(path)) {
            for (Path candidate : (Iterable<Path>) paths.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("__pycache__"))::iterator) {
                String lowerPath = candidate.toString().toLowerCase();
                String content = isText(candidate) ? read(candidate).toLowerCase() : "";
                for (String forbidden : FORBIDDEN) {
                    assertFalse("nine-key footprint remains: " + candidate,
                            lowerPath.contains(forbidden) || content.contains(forbidden));
                }
            }
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static boolean isText(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".yaml")
                || name.endsWith(".py") || name.endsWith(".gradle") || name.endsWith(".cpp")
                || name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".sh");
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath();
        if (Files.isDirectory(current.resolve("app"))) return current;
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("app"))) return parent;
        throw new AssertionError("Cannot locate project root from " + current);
    }
}
