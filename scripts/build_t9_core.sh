#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${ANDROID_NDK_HOME:-/home/simon/android-sdk/ndk/26.3.11579264}
export ANDROID_NDK_HOME="$NDK"
cd "$ROOT/native/zhuyin_t9_core"
python3 extract_rime.py
python3 generate.py
cargo ndk -t arm64-v8a -t x86_64 --platform 26 build --release --offline
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
for item in 'arm64-v8a aarch64-linux-android' 'x86_64 x86_64-linux-android'; do
 read -r ABI TARGET <<< "$item"
 mkdir -p "$ROOT/app/src/phone/jniLibs/$ABI"
 "$LLVM/${TARGET}26-clang" -shared -fPIC -O2 -ffunction-sections -fdata-sections -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--version-script=jni.exports -Wl,-s -Wl,-z,max-page-size=16384 -Wl,-z,defs -Wl,-soname,libzhuyin_t9_core.so jni.c target/$TARGET/release/libzhuyin_t9_core.a -ldl -lm -llog -o "target/$TARGET/release/libzhuyin_t9_core.so"
 cp "target/$TARGET/release/libzhuyin_t9_core.so" "$ROOT/app/src/phone/jniLibs/$ABI/"
 cmp "target/$TARGET/release/libzhuyin_t9_core.so" "$ROOT/app/src/phone/jniLibs/$ABI/libzhuyin_t9_core.so"
done
