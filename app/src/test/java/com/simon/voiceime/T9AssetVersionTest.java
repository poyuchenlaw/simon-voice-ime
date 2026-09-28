package com.simon.voiceime;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class T9AssetVersionTest {
    @Test public void matchingAssetVersionSkipsCopy() {
        assertFalse(T9AssetVersion.shouldCopy("sha256:abc123", "sha256:abc123"));
    }

    @Test public void changedOrMissingAssetVersionCopies() {
        assertTrue(T9AssetVersion.shouldCopy("sha256:old", "sha256:new"));
        assertTrue(T9AssetVersion.shouldCopy(null, "sha256:new"));
    }
}
