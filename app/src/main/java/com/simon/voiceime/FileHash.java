package com.simon.voiceime;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Streaming SHA-256 helpers used to validate downloaded and reconstructed APK files. */
final class FileHash {
    private FileHash() { }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    static boolean matches(File file, String expectedSha256) throws Exception {
        return isValidSha256(expectedSha256)
                && sha256(file).equalsIgnoreCase(expectedSha256);
    }

    static boolean isValidSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }
}
