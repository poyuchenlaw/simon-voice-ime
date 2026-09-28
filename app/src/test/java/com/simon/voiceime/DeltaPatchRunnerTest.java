package com.simon.voiceime;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class DeltaPatchRunnerTest {
    @Test public void rejectsOldApkWhenItsHashDoesNotMatchManifest() throws Exception {
        File dir = Files.createTempDirectory("v635-runner").toFile();
        File oldApk = new File(dir, "old.apk");
        File patch = new File(dir, "patch.bin");
        File output = new File(dir, "output.apk");
        File temp = new File(dir, "temp.bin");
        try {
            Files.write(oldApk.toPath(), new byte[]{1, 2, 3});
            Files.write(patch.toPath(), new byte[]{4, 5, 6});
            assertFalse(DeltaPatchRunner.apply(oldApk, "0".repeat(64), patch,
                    FileHash.sha256(patch), output, "0".repeat(64), temp));
            assertFalse(output.exists());
        } finally {
            oldApk.delete(); patch.delete(); output.delete(); temp.delete(); dir.delete();
        }
    }

    @Test public void rejectsPatchWhenDownloadedBytesDoNotMatchManifestHash() throws Exception {
        File dir = Files.createTempDirectory("v635-runner").toFile();
        File oldApk = new File(dir, "old.apk");
        File patch = new File(dir, "patch.bin");
        File output = new File(dir, "output.apk");
        File temp = new File(dir, "temp.bin");
        try {
            Files.write(oldApk.toPath(), new byte[]{1, 2, 3});
            Files.write(patch.toPath(), new byte[]{4, 5, 6});
            String oldHash = FileHash.sha256(oldApk);
            assertFalse(DeltaPatchRunner.apply(oldApk, oldHash, patch,
                    "0".repeat(64), output, "0".repeat(64), temp));
            assertFalse(output.exists());
        } finally {
            oldApk.delete(); patch.delete(); output.delete(); temp.delete(); dir.delete();
        }
    }
}
