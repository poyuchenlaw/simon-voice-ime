#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 3 ]]; then
  echo "usage: $0 <old.apk> <patch> <new.apk>" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UPSTREAM="${APKDIFFPATCH_SOURCE_ROOT:-$ROOT/third_party/ApkDiffPatch}"
OUT="$ROOT/evidence/v635/host-jni"
mkdir -p "$OUT/classes" "$OUT/objects"
rm -f "$OUT/patched.apk" "$OUT/wrong-output-hash.apk" "$OUT/tampered.patch" \
  "$OUT/tampered-output.apk" "$OUT/fallback-full.apk" "$OUT/invalid.patch" \
  "$OUT/invalid-output.apk" "$OUT/uncompress.tmp"
JAVAC_BIN="$(readlink -f "$(command -v javac)")"
JAVA_HOME="$(dirname "$(dirname "$JAVAC_BIN")")"
INCLUDES=(-I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$UPSTREAM/src/patch" -I"$UPSTREAM/HDiffPatch" -I"$UPSTREAM/zlib1.3.1" -I"$UPSTREAM/lzma/C")
CFLAGS=(-fPIC -Os -DNDEBUG -D_IS_USED_MULTITHREAD=1 -D_IS_USED_PTHREAD=1 -D_IS_NEED_CACHE_OLD_BY_COVERS=0 -DUNALIGNED_OK)
CXXFLAGS=(-fPIC -Os -DNDEBUG -std=c++11 -fexceptions -D_IS_USED_MULTITHREAD=1 -D_IS_USED_PTHREAD=1 -D_IS_NEED_CACHE_OLD_BY_COVERS=0 -DUNALIGNED_OK)
C_FILES=(
  "$UPSTREAM/lzma/C/LzmaDec.c" "$UPSTREAM/lzma/C/Lzma2Dec.c"
  "$UPSTREAM/zlib1.3.1/crc32.c" "$UPSTREAM/zlib1.3.1/deflate.c"
  "$UPSTREAM/zlib1.3.1/inflate.c" "$UPSTREAM/zlib1.3.1/zutil.c"
  "$UPSTREAM/zlib1.3.1/adler32.c" "$UPSTREAM/zlib1.3.1/trees.c"
  "$UPSTREAM/zlib1.3.1/inftrees.c" "$UPSTREAM/zlib1.3.1/inffast.c"
  "$UPSTREAM/HDiffPatch/file_for_patch.c"
  "$UPSTREAM/HDiffPatch/libHDiffPatch/HPatch/patch.c"
)
CPP_FILES=(
  "$UPSTREAM/HDiffPatch/libParallel/parallel_import.cpp"
  "$UPSTREAM/HDiffPatch/libParallel/parallel_channel.cpp"
  "$UPSTREAM/src/patch/NewStream.cpp" "$UPSTREAM/src/patch/OldStream.cpp"
  "$UPSTREAM/src/patch/Patcher.cpp" "$UPSTREAM/src/patch/ZipDiffData.cpp"
  "$UPSTREAM/src/patch/Zipper.cpp"
  "$UPSTREAM/builds/android_ndk_jni_mk/apk_patch.cpp"
  "$UPSTREAM/builds/android_ndk_jni_mk/apk_patch_jni.cpp"
)
OBJECTS=()
for source in "${C_FILES[@]}"; do
  object="$OUT/objects/$(basename "${source%.*}").c.o"
  gcc "${CFLAGS[@]}" "${INCLUDES[@]}" -c "$source" -o "$object"
  OBJECTS+=("$object")
done
for source in "${CPP_FILES[@]}"; do
  object="$OUT/objects/$(basename "${source%.*}").cpp.o"
  g++ "${CXXFLAGS[@]}" "${INCLUDES[@]}" -c "$source" -o "$object"
  OBJECTS+=("$object")
done
g++ -shared -pthread -Wl,-Bsymbolic-functions "${OBJECTS[@]}" -o "$OUT/libapkpatch.so"
cat > "$OUT/DeltaPatchHostSmoke.java" <<'JAVA'
package com.simon.voiceime;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;

public final class DeltaPatchHostSmoke {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        File oldApk = new File(args[0]);
        File patch = new File(args[1]);
        File newApk = new File(args[2]);
        File temp = new File(args[3]);
        File output = new File(args[4]);
        String oldHash = FileHash.sha256(oldApk);
        String patchHash = FileHash.sha256(patch);
        String newHash = FileHash.sha256(newApk);
        require(DeltaPatchRunner.apply(oldApk, oldHash, patch, patchHash,
                output, newHash, temp), "official JNI patch failed");
        require(FileHash.matches(output, newHash), "patched output hash mismatch");
        System.out.println("JNI_PATCH=PASS output_sha256=" + newHash);

        File wrongHashOutput = new File(output.getParentFile(), "wrong-output-hash.apk");
        require(!DeltaPatchRunner.apply(oldApk, oldHash, patch, patchHash,
                wrongHashOutput, "0".repeat(64), temp), "wrong reconstructed APK hash was accepted");
        require(!wrongHashOutput.exists(), "hash-rejected APK remained installable");
        System.out.println("RECONSTRUCTED_APK_HASH_MISMATCH=REJECTED");

        File tampered = new File(output.getParentFile(), "tampered.patch");
        Files.copy(patch.toPath(), tampered.toPath());
        try (RandomAccessFile file = new RandomAccessFile(tampered, "rw")) {
            long last = file.length() - 1;
            file.seek(last);
            int value = file.read();
            file.seek(last);
            file.write(value ^ 1);
        }
        File rejectedOutput = new File(output.getParentFile(), "tampered-output.apk");
        require(!DeltaPatchRunner.apply(oldApk, oldHash, tampered, patchHash,
                rejectedOutput, newHash, temp), "tampered patch was accepted");
        require(!rejectedOutput.exists(), "tampered patch left installable output");
        File fallback = new File(output.getParentFile(), "fallback-full.apk");
        Files.copy(newApk.toPath(), fallback.toPath());
        require(FileHash.matches(fallback, newHash), "full-download fallback hash mismatch");
        System.out.println("TAMPERED_PATCH=REJECTED full_fallback=PASS");

        File invalid = new File(output.getParentFile(), "invalid.patch");
        Files.write(invalid.toPath(), new byte[]{1, 2, 3, 4});
        File invalidOutput = new File(output.getParentFile(), "invalid-output.apk");
        require(!DeltaPatchRunner.apply(oldApk, oldHash, invalid, FileHash.sha256(invalid),
                invalidOutput, newHash, temp), "invalid patch result was accepted");
        require(!invalidOutput.exists(), "native patch failure left output");
        System.out.println("NATIVE_PATCH_FAILURE=REJECTED");
    }
}
JAVA
javac -d "$OUT/classes" \
  "$ROOT/app/src/main/java/com/simon/voiceime/FileHash.java" \
  "$ROOT/app/src/main/java/com/simon/voiceime/DeltaPatchRunner.java" \
  "$ROOT/app/src/phone/java/com/simon/voiceime/PatchUpdateSupport.java" \
  "$ROOT/app/src/phone/java/com/github/sisong/ApkPatch.java" \
  "$OUT/DeltaPatchHostSmoke.java"
java -Djava.library.path="$OUT" -cp "$OUT/classes" com.simon.voiceime.DeltaPatchHostSmoke \
  "$1" "$2" "$3" "$OUT/uncompress.tmp" "$OUT/patched.apk" | tee "$OUT/host-smoke.log"
