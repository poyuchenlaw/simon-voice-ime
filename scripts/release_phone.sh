#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then
  echo "usage: scripts/release_phone.sh <versionName>" >&2
  exit 2
fi
VERSION="$1"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
  echo "versionName must be numeric, such as 6.35" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-/home/simon/Android/Sdk}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$SDK/build-tools/35.0.0}"
GRADLE_HOME="${GRADLE_USER_HOME:-/home/simon/.gradle}"
APK_NORMALIZED="$ROOT/third_party/ApkDiffPatch/ApkNormalized"
ZIP_DIFF="$ROOT/third_party/ApkDiffPatch/ZipDiff"
ZIP_PATCH="$ROOT/third_party/ApkDiffPatch/ZipPatch"
AAPT="$BUILD_TOOLS/aapt"
APK_SIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"
WORK="$ROOT/out/release-phone-$VERSION"
DEST="$ROOT/dist/release_$VERSION"
EVIDENCE="$ROOT/evidence/release_$VERSION"
mkdir -p "$WORK" "$DEST" "$EVIDENCE"

for tool in gh python3; do command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }; done
for tool in "$APK_NORMALIZED" "$ZIP_DIFF" "$ZIP_PATCH" "$AAPT" "$APK_SIGNER" "$ZIPALIGN"; do
  [[ -x "$tool" ]] || { echo "missing executable: $tool" >&2; exit 2; }
done

# ApkDiffPatch v1.8.1 warns that normalized APKs cannot be signed by apksigner v35.
# Cache the official SDK package locally; use v34 to sign and v35 to verify/align.
COMPAT_TOOLS="$ROOT/out/android-build-tools34/android-14"
COMPAT_ARCHIVE="$ROOT/out/android-build-tools34/build-tools_r34-linux.zip"
COMPAT_APKSIGNER="$COMPAT_TOOLS/apksigner"
if [[ ! -x "$COMPAT_APKSIGNER" ]]; then
  for tool in curl unzip sha1sum; do command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }; done
  mkdir -p "$(dirname "$COMPAT_ARCHIVE")"
  if [[ ! -s "$COMPAT_ARCHIVE" ]]; then
    curl -fL --retry 3 https://dl.google.com/android/repository/build-tools_r34-linux.zip -o "$COMPAT_ARCHIVE"
  fi
  echo "d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9  $COMPAT_ARCHIVE" | sha1sum -c -
  unzip -q -o "$COMPAT_ARCHIVE" -d "$(dirname "$COMPAT_ARCHIVE")"
  chmod +x "$COMPAT_APKSIGNER"
fi

mapfile -t PREVIOUS_TAGS < <(gh release list --repo poyuchenlaw/simon-voice-ime --limit 30 --json tagName --jq '.[].tagName' \
  | sed -n 's/^v//p' | awk -v current="$VERSION" '$0 != current' | sort -Vr | head -2)
LATEST_TAG="${PREVIOUS_TAGS[0]:-}"
if [[ -n "$LATEST_TAG" ]]; then
  LATEST_DIR="$WORK/v$LATEST_TAG"
  mkdir -p "$LATEST_DIR"
  LATEST_META="$(gh api "repos/poyuchenlaw/simon-voice-ime/releases/tags/v$LATEST_TAG" --jq '[.assets[] | select(.name | startswith("simon-voice-ime-phone-") and endswith(".apk"))][0] | [.name,.digest] | @tsv')"
  LATEST_ASSET="${LATEST_META%%$'\t'*}"
  LATEST_DIGEST="${LATEST_META#*$'\t'}"
  LATEST_LOCAL="$ROOT/dist/simon-voice-ime-v$LATEST_TAG-phone.apk"
  if [[ -z "$LATEST_ASSET" || "$LATEST_ASSET" == null ]]; then
    echo "latest release v$LATEST_TAG has no phone APK" >&2; exit 2
  fi
  if [[ -f "$LATEST_LOCAL" && "$LATEST_DIGEST" == "sha256:$(sha256sum "$LATEST_LOCAL" | cut -d' ' -f1)" ]]; then
    cp "$LATEST_LOCAL" "$LATEST_DIR/$LATEST_ASSET"
  else
    gh release download "v$LATEST_TAG" --repo poyuchenlaw/simon-voice-ime --pattern "$LATEST_ASSET" --dir "$LATEST_DIR"
  fi
  LATEST_CODE="$("$AAPT" dump badging "$LATEST_DIR/$LATEST_ASSET" | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -1)"
else
  LATEST_CODE=""
fi
PHONE_VERSION_CODE="${PHONE_VERSION_CODE:-}"
if [[ -z "$PHONE_VERSION_CODE" ]]; then
  if [[ -z "$LATEST_CODE" ]]; then
    echo "set PHONE_VERSION_CODE when no previous release APK is available" >&2; exit 2
  fi
  PHONE_VERSION_CODE=$((LATEST_CODE + 1))
fi

scripts/build_apkdiffpatch_android.sh > "$EVIDENCE/native-build.log" 2>&1
make -C "$ROOT/third_party/ApkDiffPatch" -j2 > "$EVIDENCE/host-tools-build.log" 2>&1
GRADLE_USER_HOME="$GRADLE_HOME" "$ROOT/gradlew" --project-cache-dir "$ROOT/out/v635-gradle-cache" --no-daemon \
  -PphoneVersionName="$VERSION" -PphoneVersionCode="$PHONE_VERSION_CODE" \
  testPhoneReleaseUnitTest assemblePhoneRelease > "$EVIDENCE/gradle-build.log" 2>&1
BUILT="$ROOT/app/build/outputs/apk/phone/release/app-phone-release.apk"
[[ -s "$BUILT" ]] || { echo "Gradle phone APK missing" >&2; exit 2; }
# Verify repo-built ARM64 libraries are the exact current artifacts and newer than
# their native source inputs. This catches stale prebuilt JNI binaries in the APK.
NATIVE_TMP="$WORK/native-check"
rm -rf "$NATIVE_TMP"; mkdir -p "$NATIVE_TMP"
unzip -q "$BUILT" 'lib/arm64-v8a/*.so' -d "$NATIVE_TMP"
JNI_DIR="$ROOT/app/src/phone/jniLibs/arm64-v8a"
LLVM_READelf="${ANDROID_NDK_HOME:-/home/simon/android-sdk/ndk/26.3.11579264}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$LLVM_READelf" ]] || { echo "missing llvm-readelf: $LLVM_READelf" >&2; exit 2; }
for lib in libchewing_jni.so librime_jni.so librime.so libchewing.so libapkpatch.so; do
  [[ -s "$NATIVE_TMP/lib/arm64-v8a/$lib" && -s "$JNI_DIR/$lib" ]] || { echo "missing native library: $lib" >&2; exit 2; }
  "$LLVM_READelf" --dyn-syms --wide "$JNI_DIR/$lib" | awk 'NF>=8{print $8}' | sort -u > "$NATIVE_TMP/source-symbols.txt"
  "$LLVM_READelf" --dyn-syms --wide "$NATIVE_TMP/lib/arm64-v8a/$lib" | awk 'NF>=8{print $8}' | sort -u > "$NATIVE_TMP/apk-symbols.txt"
  cmp -s "$NATIVE_TMP/source-symbols.txt" "$NATIVE_TMP/apk-symbols.txt" || { echo "APK native exports differ from repo artifact: $lib" >&2; exit 2; }
done
strings "$NATIVE_TMP/lib/arm64-v8a/libchewing_jni.so" | grep -Fq 'chewing.conversion_engine' || {
  echo "APK libchewing_jni.so lacks chewing.conversion_engine; refusing stale native build" >&2; exit 2;
}
fresh_against() {
  local artifact="$1"; shift
  for source in "$@"; do
    [[ -f "$source" ]] || { echo "native source missing: $source" >&2; exit 2; }
    [[ "$artifact" -nt "$source" ]] || { echo "native library is older than source: $artifact <= $source" >&2; exit 2; }
  done
}
fresh_against "$JNI_DIR/libchewing_jni.so" "$ROOT/app/src/phone/cpp/chewing_jni.c"
while IFS= read -r source; do fresh_against "$JNI_DIR/libchewing.so" "$source"; done < <(find "$ROOT/third_party/libchewing/src" "$ROOT/third_party/libchewing/capi/src" "$ROOT/third_party/libchewing/capi/include" -type f \( -name '*.rs' -o -name '*.c' -o -name '*.h' \))
fresh_against "$JNI_DIR/librime_jni.so" "$ROOT/app/src/phone/cpp/rime_jni.cpp" "$ROOT/evidence/rime_spike/android-prefix/lib/librime.so"
fresh_against "$JNI_DIR/librime.so" "$ROOT/evidence/rime_spike/android-prefix/lib/librime.so"
while IFS= read -r source; do fresh_against "$JNI_DIR/libapkpatch.so" "$source"; done < <(find "$ROOT/third_party/ApkDiffPatch/builds/android_ndk_jni_mk" -type f \( -name '*.c' -o -name '*.cpp' -o -name '*.h' -o -name 'Android.mk' -o -name 'Application.mk' \))
RAW="$WORK/gradle-signed.apk"
NORMALIZED="$WORK/normalized.apk"
FINAL="$DEST/simon-voice-ime-phone-$VERSION.apk"
cp "$BUILT" "$RAW"
"$APK_NORMALIZED" "$RAW" "$NORMALIZED" -cl-6 -ap-c16k -as-4 > "$EVIDENCE/normalize.log" 2>&1
STORE_PASS="${RELEASE_STORE_PASSWORD:-simonime2026}"
KEY_PASS="${RELEASE_KEY_PASSWORD:-simonime2026}"
"$COMPAT_APKSIGNER" sign --ks "$ROOT/release.keystore" --ks-key-alias simon-voice-ime \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$FINAL" "$NORMALIZED"
"$APK_SIGNER" verify --verbose --print-certs "$FINAL" > "$EVIDENCE/apksigner-verify.txt" 2>&1
if "$ZIPALIGN" -h 2>&1 | grep -q -- '-P'; then
  "$ZIPALIGN" -c -P 16 -v 4 "$FINAL" > "$EVIDENCE/zipalign-16kb.txt" 2>&1
else
  # Build Tools 34 has no -P 16 switch; check 4 KB alignment there, then
  # independently verify every uncompressed native library's actual data offset.
  "$ZIPALIGN" -c -p -v 4 "$FINAL" > "$EVIDENCE/zipalign-16kb.txt" 2>&1
  python3 - "$FINAL" <<'PY' >> "$EVIDENCE/zipalign-16kb.txt"
import struct, sys, zipfile
path=sys.argv[1]
with zipfile.ZipFile(path) as z, open(path,'rb') as f:
    libs=[x for x in z.infolist() if x.filename.startswith('lib/') and x.filename.endswith('.so')]
    if not libs: raise SystemExit('APK contains no native libraries')
    for x in libs:
        f.seek(x.header_offset); h=f.read(30)
        if len(h)!=30 or struct.unpack_from('<I',h)[0]!=0x04034b50: raise SystemExit('invalid ZIP local header: '+x.filename)
        name_len,extra_len=struct.unpack_from('<HH',h,26)
        offset=x.header_offset+30+name_len+extra_len
        if offset%16384: raise SystemExit(f'16 KB alignment failed: {x.filename} offset={offset}')
        print(f'16 KB aligned: {x.filename} offset={offset}')
PY
fi
"$AAPT" dump badging "$FINAL" > "$EVIDENCE/badging.txt"
if ! rg -q "versionCode='$PHONE_VERSION_CODE'.*versionName='$VERSION'" "$EVIDENCE/badging.txt"; then
  echo "built APK version metadata does not match requested release" >&2; exit 2
fi
CERT_SHA="$(sed -n 's/.*certificate SHA-256 digest: //p' "$EVIDENCE/apksigner-verify.txt" | head -1 | tr 'A-F' 'a-f')"
EXPECTED_CERT="9f6ee9da00cba648fe8c0f231df31b55f895d1ee6ede2968ef91f9b7e381d9de"
[[ "$CERT_SHA" == "$EXPECTED_CERT" ]] || { echo "release signing certificate changed" >&2; exit 2; }
{
  "$COMPAT_APKSIGNER" version
  printf 'Build Tools archive SHA-1: d6d58e0c6925a9e4d9a541e84cd1f405c2f9d2a9\n'
  printf 'Build Tools archive SHA-256: %s\n' "$(sha256sum "$COMPAT_ARCHIVE" | cut -d' ' -f1)"
} > "$EVIDENCE/apksigner-tool.txt"

FULL_SHA="$(sha256sum "$FINAL" | cut -d' ' -f1)"
FULL_SIZE="$(stat -c %s "$FINAL")"
PATCH_RECORDS=()
for old_version in "${PREVIOUS_TAGS[@]}"; do
  OLD_DIR="$WORK/v$old_version"
  mkdir -p "$OLD_DIR"
  OLD_ASSETS="$(gh api "repos/poyuchenlaw/simon-voice-ime/releases/tags/v$old_version" --jq '[.assets[].name] | .[]')"
  if ! printf '%s\n' "$OLD_ASSETS" | rg -qx 'update-manifest\.json'; then
    continue
  fi
  gh release download "v$old_version" --repo poyuchenlaw/simon-voice-ime --pattern update-manifest.json --dir "$OLD_DIR" >/dev/null
  if [[ "$old_version" == "$LATEST_TAG" ]]; then
    OLD_APK="$OLD_DIR/$LATEST_ASSET"
  else
    OLD_META="$(printf '%s\n' "$OLD_ASSETS" | rg '^simon-voice-ime-phone-.*\.apk$' | head -1)"
    [[ -n "$OLD_META" && "$OLD_META" != null ]] || continue
    OLD_APK="$OLD_DIR/$OLD_META"
    OLD_LOCAL="$ROOT/dist/simon-voice-ime-v$old_version-phone.apk"
    if [[ -s "$OLD_LOCAL" ]] && python3 - "$OLD_DIR/update-manifest.json" "$OLD_LOCAL" <<'PY'
import hashlib, json, sys
m=json.load(open(sys.argv[1])); h=hashlib.sha256(open(sys.argv[2],'rb').read()).hexdigest()
sys.exit(0 if m.get('normalized') is True and m.get('sha256') == h else 1)
PY
    then
      cp "$OLD_LOCAL" "$OLD_APK"
    else
      gh release download "v$old_version" --repo poyuchenlaw/simon-voice-ime --pattern "$OLD_META" --dir "$OLD_DIR"
    fi
  fi
  OLD_MANIFEST="$OLD_DIR/update-manifest.json"
  if ! python3 - "$OLD_MANIFEST" "$OLD_APK" <<'PY'
import hashlib, json, sys
m=json.load(open(sys.argv[1]))
h=hashlib.sha256(open(sys.argv[2],'rb').read()).hexdigest()
sys.exit(0 if m.get('normalized') is True and m.get('sha256') == h else 1)
PY
  then
    continue
  fi
  OLD_CODE="$("$AAPT" dump badging "$OLD_APK" | sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" | head -1)"
  OLD_SHA="$(sha256sum "$OLD_APK" | cut -d' ' -f1)"
  PATCH_NAME="simon-voice-ime-phone-$old_version-to-$VERSION.patch"
  PATCH_OUT="$DEST/$PATCH_NAME"
  "$ZIP_DIFF" "$OLD_APK" "$FINAL" "$PATCH_OUT" -c-lzma-7-4m > "$EVIDENCE/zipdiff-v$old_version.log" 2>&1
  PATCHED="$WORK/patched-from-$old_version.apk"
  "$ZIP_PATCH" "$OLD_APK" "$PATCH_OUT" "$PATCHED" 67108864 "$WORK/uncompress-$old_version.tmp" > "$EVIDENCE/zippatch-v$old_version.log" 2>&1
  [[ "$(sha256sum "$PATCHED" | cut -d' ' -f1)" == "$FULL_SHA" ]] || { echo "round-trip mismatch from v$old_version" >&2; exit 2; }
  PATCH_SHA="$(sha256sum "$PATCH_OUT" | cut -d' ' -f1)"
  PATCH_SIZE="$(stat -c %s "$PATCH_OUT")"
  PATCH_RECORDS+=("$old_version|$OLD_CODE|$OLD_SHA|$PATCH_NAME|$PATCH_SHA|$PATCH_SIZE")
  "$ROOT/scripts/test_apkdiffpatch_host.sh" "$OLD_APK" "$PATCH_OUT" "$FINAL" > "$EVIDENCE/host-jni-v$old_version.log" 2>&1
done

if [[ -n "${EXTRA_BASE_APKS:-}" ]]; then
  read -r -a EXTRA_BASE_PATHS <<< "$EXTRA_BASE_APKS"
  for base_path in "${EXTRA_BASE_PATHS[@]}"; do
    if [[ "$base_path" = /* ]]; then OLD_APK="$base_path"; else OLD_APK="$ROOT/$base_path"; fi
    [[ -s "$OLD_APK" ]] || { echo "extra base APK missing: $OLD_APK" >&2; exit 2; }
    OLD_BADGING="$WORK/extra-base-$(basename "$OLD_APK").badging.txt"
    "$AAPT" dump badging "$OLD_APK" > "$OLD_BADGING"
    OLD_CODE="$(sed -n "s/.*versionCode='\([0-9][0-9]*\)'.*/\1/p" "$OLD_BADGING" | head -1)"
    OLD_VERSION_NAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" "$OLD_BADGING" | head -1)"
    [[ -n "$OLD_CODE" && -n "$OLD_VERSION_NAME" ]] || { echo "could not read extra base APK version: $OLD_APK" >&2; exit 2; }
    OLD_SHA="$(sha256sum "$OLD_APK" | cut -d' ' -f1)"
    PATCH_NAME="simon-voice-ime-phone-$OLD_VERSION_NAME-to-$VERSION.patch"
    PATCH_OUT="$DEST/$PATCH_NAME"
    "$ZIP_DIFF" "$OLD_APK" "$FINAL" "$PATCH_OUT" -c-lzma-7-4m > "$EVIDENCE/zipdiff-extra-$OLD_VERSION_NAME.log" 2>&1
    PATCHED="$WORK/patched-from-extra-$OLD_VERSION_NAME.apk"
    "$ZIP_PATCH" "$OLD_APK" "$PATCH_OUT" "$PATCHED" 67108864 "$WORK/uncompress-extra-$OLD_VERSION_NAME.tmp" > "$EVIDENCE/zippatch-extra-$OLD_VERSION_NAME.log" 2>&1
    [[ "$(sha256sum "$PATCHED" | cut -d' ' -f1)" == "$FULL_SHA" ]] || { echo "round-trip mismatch from extra base $OLD_VERSION_NAME" >&2; exit 2; }
    PATCH_SHA="$(sha256sum "$PATCH_OUT" | cut -d' ' -f1)"
    PATCH_SIZE="$(stat -c %s "$PATCH_OUT")"
    PATCH_RECORDS+=("$OLD_VERSION_NAME|$OLD_CODE|$OLD_SHA|$PATCH_NAME|$PATCH_SHA|$PATCH_SIZE")
    "$ROOT/scripts/test_apkdiffpatch_host.sh" "$OLD_APK" "$PATCH_OUT" "$FINAL" > "$EVIDENCE/host-jni-extra-$OLD_VERSION_NAME.log" 2>&1
  done
fi

python3 - "$FINAL" "$DEST/update-manifest.json" "$VERSION" "$PHONE_VERSION_CODE" "${PATCH_RECORDS[@]}" <<'PY'
import hashlib, json, os, sys
apk, manifest, name, code, *rows = sys.argv[1:]
patches=[]
for row in rows:
    old, old_code, old_sha, filename, sha, size = row.split('|')
    patches.append({'from_versionCode': int(old_code), 'from_sha256': old_sha,
                    'name': filename, 'sha256': sha, 'size': int(size)})
with open(apk,'rb') as f: full_sha=hashlib.sha256(f.read()).hexdigest()
result={'versionName':name, 'versionCode':int(code), 'sha256':full_sha,
        'size':os.path.getsize(apk), 'normalized':True,
        'normalization':'ApkDiffPatch-v1.8.1 -ap-c16k -as-4 then apksigner',
        'patcher_version':'ApkDiffPatch-v1.8.1', 'patches':patches}
with open(manifest,'w') as f: json.dump(result,f,indent=2); f.write('\n')
PY
printf '%s\n' "$FULL_SHA  $FINAL" > "$EVIDENCE/apk-sha256.txt"
stat -c '%s bytes  %n' "$FINAL" > "$EVIDENCE/apk-size.txt"
cp "$EVIDENCE/apksigner-verify.txt" "$EVIDENCE/signature.txt"
rm -f "$FINAL.idsig"
printf 'Artifacts written to %s\n' "$DEST"
printf 'No GitHub release was created.\n'
