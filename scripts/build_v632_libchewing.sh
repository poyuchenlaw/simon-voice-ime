#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/home/simon/android-sdk/ndk/26.3.11579264}"
HOST_BUILD="$ROOT/out/v632-host"
HOST_PREFIX="$ROOT/out/v632-host-install"
ANDROID_BUILD="$ROOT/out/v632-android"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

rustup target add aarch64-linux-android
cmake --preset rust-release -S "$ROOT/third_party/libchewing" -B "$HOST_BUILD" \
  -DBUILD_DOC=OFF -DBUILD_TESTING=ON -DBUILD_DATA=ON \
  -DCMAKE_INSTALL_PREFIX="$HOST_PREFIX"
cmake --build "$HOST_BUILD" --target libchewing dict_chewing static_data cargo-build_chewing-cli -j2
cmake --build "$HOST_BUILD" --target install -j2
cc -I"$ROOT/third_party/libchewing/capi/include" "$ROOT/evidence/v632/host_engine_test.c" \
  -L"$HOST_BUILD" -Wl,-rpath,"$HOST_BUILD" -lchewing -o "$ROOT/out/v632-host-engine-test"
HOST_USER_DIR="$(mktemp -d /tmp/v632-user-XXXXXX)"
"$ROOT/out/v632-host-engine-test" "$HOST_PREFIX/share/libchewing" "$HOST_USER_DIR/chewing.dat"
cmake --build "$HOST_BUILD" --target install -j2

cmake -S "$ROOT/third_party/libchewing" -B "$ANDROID_BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON -DBUILD_DOC=OFF -DBUILD_TESTING=OFF -DBUILD_DATA=OFF \
  -DWITH_SQLITE3=OFF -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
cmake --build "$ANDROID_BUILD" --target libchewing -j2

JNI_DIR="$ROOT/app/src/phone/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR" "$ROOT/app/src/phone/assets/libchewing"
cp "$ANDROID_BUILD/libchewing.so" "$JNI_DIR/libchewing.so"
"$LLVM/aarch64-linux-android26-clang" -shared -fPIC -O2 \
  -Wl,-z,max-page-size=16384 -Wl,-soname,libchewing_jni.so \
  -I"$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include" \
  -I"$ROOT/third_party/libchewing/capi/include" \
  "$ROOT/app/src/phone/cpp/chewing_jni.c" \
  -L"$JNI_DIR" -lchewing -o "$JNI_DIR/libchewing_jni.so"
cp "$HOST_PREFIX/share/libchewing/"*.dat "$ROOT/app/src/phone/assets/libchewing/"
printf 'Native libraries and dictionaries built for arm64-v8a.\n'
