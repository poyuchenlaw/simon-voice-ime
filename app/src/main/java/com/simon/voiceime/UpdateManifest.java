package com.simon.voiceime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Validated metadata for one full APK and the patches that can produce it. */
final class UpdateManifest {
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern ASSET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");

    static final class Patch {
        final int fromVersionCode;
        final String fromSha256;
        final String name;
        final String sha256;
        final long size;

        Patch(int fromVersionCode, String fromSha256, String name, String sha256, long size) {
            this.fromVersionCode = fromVersionCode;
            this.fromSha256 = fromSha256;
            this.name = name;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    final String versionName;
    final int versionCode;
    final String fullApkSha256;
    final long fullApkSize;
    final List<Patch> patches;

    private UpdateManifest(String versionName, int versionCode, String fullApkSha256,
                           long fullApkSize, List<Patch> patches) {
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.fullApkSha256 = fullApkSha256;
        this.fullApkSize = fullApkSize;
        this.patches = Collections.unmodifiableList(patches);
    }

    static UpdateManifest parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String versionName = root.getString("versionName");
        int versionCode = root.getInt("versionCode");
        String fullSha256 = root.getString("sha256");
        long fullSize = root.getLong("size");
        if (versionName.trim().isEmpty() || versionCode <= 0 || fullSize <= 0
                || !isSha256(fullSha256)) {
            throw new IllegalArgumentException("invalid full APK metadata");
        }

        JSONArray patchArray = root.getJSONArray("patches");
        List<Patch> patches = new ArrayList<>();
        for (int i = 0; i < patchArray.length(); i++) {
            JSONObject item = patchArray.getJSONObject(i);
            int fromCode = item.getInt("from_versionCode");
            String fromSha = item.getString("from_sha256");
            String name = item.getString("name");
            String sha = item.getString("sha256");
            long size = item.getLong("size");
            if (fromCode <= 0 || !isSha256(fromSha) || !isAssetName(name)
                    || !isSha256(sha) || size <= 0) {
                throw new IllegalArgumentException("invalid patch metadata at index " + i);
            }
            patches.add(new Patch(fromCode, fromSha, name, sha, size));
        }
        return new UpdateManifest(versionName, versionCode, fullSha256, fullSize, patches);
    }

    Patch findPatch(int installedVersionCode, String installedSha256) {
        if (!isSha256(installedSha256)) return null;
        for (Patch patch : patches) {
            if (patch.fromVersionCode == installedVersionCode
                    && patch.fromSha256.equalsIgnoreCase(installedSha256)) return patch;
        }
        return null;
    }

    private static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static boolean isAssetName(String value) {
        return value != null && ASSET_NAME.matcher(value).matches()
                && !value.equals(".") && !value.equals("..");
    }
}
