package com.simon.voiceime;

import java.io.File;

/** Hash-gated wrapper around ApkDiffPatch's official native patch API. */
final class DeltaPatchRunner {
    private DeltaPatchRunner() { }

    static boolean apply(File oldApk, String expectedOldSha256,
                         File patchFile, String expectedPatchSha256,
                         File outputApk, String expectedOutputSha256, File tempFile) {
        outputApk.delete();
        try {
            if (!FileHash.matches(oldApk, expectedOldSha256)
                    || !FileHash.matches(patchFile, expectedPatchSha256)
                    || !FileHash.isValidSha256(expectedOutputSha256)
                    || !PatchUpdateSupport.isAvailable()) {
                patchFile.delete();
                return false;
            }
            if (!PatchUpdateSupport.patch(oldApk, patchFile, outputApk, tempFile)
                    || !FileHash.matches(outputApk, expectedOutputSha256)) {
                outputApk.delete();
                return false;
            }
            return true;
        } catch (Exception | LinkageError e) {
            outputApk.delete();
            return false;
        }
    }
}
