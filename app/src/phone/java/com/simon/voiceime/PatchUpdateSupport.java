package com.simon.voiceime;

import com.github.sisong.ApkPatch;

import java.io.File;

final class PatchUpdateSupport {
    private static boolean loaded;

    static boolean isAvailable() {
        if (!loaded) {
            try {
                System.loadLibrary("apkpatch");
                loaded = true;
            } catch (UnsatisfiedLinkError | SecurityException e) {
                return false;
            }
        }
        return true;
    }

    static boolean patch(File oldApk, File patchFile, File output, File tempFile) {
        return isAvailable() && ApkPatch.patch(oldApk.getAbsolutePath(), patchFile.getAbsolutePath(),
                output.getAbsolutePath(), 64L * 1024L * 1024L, tempFile.getAbsolutePath(), 1) == 0
                && output.isFile();
    }
}
