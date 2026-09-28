package com.simon.voiceime;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class FileHashTest {
    @Test public void acceptsOnlyBytesWhoseSha256MatchesTheManifest() throws Exception {
        File file = File.createTempFile("v635-patch", ".bin");
        try {
            Files.write(file.toPath(), "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(FileHash.matches(file,
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
            Files.write(file.toPath(), "jello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(FileHash.matches(file,
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        } finally {
            assertTrue(file.delete());
        }
    }
}
