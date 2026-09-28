#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/home/simon/android-sdk/ndk/26.3.11579264}"
UPSTREAM="$ROOT/third_party/ApkDiffPatch/builds/android_ndk_jni_mk"
DEST="$ROOT/app/src/phone/jniLibs"
NDK_APPLICATION_MK="$ROOT/out/apkdiffpatch-application.mk"

if [[ ! -x "$NDK/ndk-build" ]]; then
  echo "Android NDK r26d not found: $NDK" >&2
  exit 2
fi
mkdir -p "$(dirname "$NDK_APPLICATION_MK")"
cat > "$NDK_APPLICATION_MK" <<MAKEFILE
include $UPSTREAM/Application.mk
APP_LDFLAGS += -Wl,-Bsymbolic-functions
MAKEFILE
cd "$UPSTREAM"
"$NDK/ndk-build" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk \
  NDK_APPLICATION_MK="$NDK_APPLICATION_MK" APP_ABI=arm64-v8a APP_STL=c++_static
install -D -m 0644 "$UPSTREAM/libs/arm64-v8a/libapkpatch.so" "$DEST/arm64-v8a/libapkpatch.so"
