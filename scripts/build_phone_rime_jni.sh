#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/home/simon/android-sdk/ndk/26.3.11579264}"
TOOL="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
PREFIX="$ROOT/evidence/rime_spike/android-prefix"
OUT="$ROOT/app/src/phone/jniLibs/arm64-v8a"
mkdir -p "$OUT"
test -f "$PREFIX/lib/librime.so"
"$TOOL" --target=aarch64-linux-android26 -std=c++17 -O2 -fPIC -shared -static-libstdc++ \
  -I"$PREFIX/include" -I"$ROOT/app/src/phone/cpp/rime_headers" "$ROOT/app/src/phone/cpp/rime_jni.cpp" \
  -L"$PREFIX/lib" -Wl,-z,defs -Wl,-soname,librime_jni.so -lrime \
  -o "$OUT/librime_jni.so"
cp "$PREFIX/lib/librime.so" "$OUT/librime.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$OUT/librime_jni.so" | grep -E 'Class:|Machine:'
