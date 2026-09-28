package com.simon.voiceime;

final class T9AssetVersion {
    private T9AssetVersion() {}

    static String fromPackage(long versionCode, long lastUpdateTime) {
        return "apk:" + versionCode + ":" + lastUpdateTime;
    }

    static boolean shouldCopy(String installedVersion, String packagedVersion) {
        return packagedVersion == null || !packagedVersion.equals(installedVersion);
    }
}
