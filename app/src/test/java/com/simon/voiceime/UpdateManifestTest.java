package com.simon.voiceime;

import org.junit.Test;

import static org.junit.Assert.*;

public class UpdateManifestTest {
    private static final String VALID = "{\"versionName\":\"6.35\",\"versionCode\":72,"
            + "\"sha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
            + "\"size\":55800000,\"patches\":[{\"from_versionCode\":71,"
            + "\"from_sha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\","
            + "\"name\":\"simon-voice-ime-phone-6.34-to-6.35.patch\","
            + "\"sha256\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\","
            + "\"size\":1234567}]}";

    @Test public void parsesVersionFullApkAndPatchMetadata() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(VALID);
        assertEquals("6.35", manifest.versionName);
        assertEquals(72, manifest.versionCode);
        assertEquals(55800000L, manifest.fullApkSize);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                manifest.fullApkSha256);
        assertEquals(1234567L, manifest.patches.get(0).size);
    }

    @Test public void selectsPatchOnlyWhenInstalledVersionAndApkHashBothMatch() throws Exception {
        UpdateManifest manifest = UpdateManifest.parse(VALID);
        assertNotNull(manifest.findPatch(71,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertNull(manifest.findPatch(70,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        assertNull(manifest.findPatch(71,
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"));
    }

    @Test public void rejectsMalformedOrIncompleteManifest() {
        assertThrows(Exception.class, () -> UpdateManifest.parse("not-json"));
        assertThrows(Exception.class, () -> UpdateManifest.parse("{}"));
        assertThrows(Exception.class, () -> UpdateManifest.parse(VALID.replace("1234567", "-1")));
        assertThrows(Exception.class, () -> UpdateManifest.parse(VALID.replace(
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "bad")));
        assertThrows(Exception.class, () -> UpdateManifest.parse(VALID.replace(
                "simon-voice-ime-phone-6.34-to-6.35.patch", "../untrusted.patch")));
    }
}
