package com.simon.voiceime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;

public class UpdateCacheCleanupTest {
    @Test
    public void removesOnlyInstallerArtifacts() throws Exception {
        File cache = Files.createTempDirectory("update-cache-cleanup").toFile();
        String[] names = {"update.apk", "update-patched.apk", "update.patch", "patch-uncompress.tmp"};
        for (String name : names) assertTrue(new File(cache, name).createNewFile());
        File unrelated = new File(cache, "keep.tmp");
        assertTrue(unrelated.createNewFile());

        UpdateCacheCleanupReceiver.cleanupUpdateCache(cache);

        for (String name : names) assertFalse(new File(cache, name).exists());
        assertTrue(unrelated.exists());
    }
}
