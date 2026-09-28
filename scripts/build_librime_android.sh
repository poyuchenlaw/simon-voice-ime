#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RIME="$ROOT/third_party/librime"
NDK="${ANDROID_NDK_HOME:-/home/simon/android-sdk/ndk/26.3.11579264}"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
BUILD="$ROOT/evidence/rime_spike/build/android"
PREFIX="$ROOT/evidence/rime_spike/android-prefix"
BOOST_HEADERS="$ROOT/third_party/boost-local/usr/include"
BOOST_REGEX="$ROOT/third_party/boost-regex-source"
API=26

for needed in "$TOOLCHAIN" "$RIME/CMakeLists.txt" "$BOOST_HEADERS/boost/version.hpp"; do
  [[ -f "$needed" ]] || { echo "missing prerequisite: $needed" >&2; exit 2; }
done
mkdir -p "$BUILD/boost" "$PREFIX/include" "$PREFIX/lib"

# Boost.Regex is the only compiled Boost component required by librime.
# Build the upstream 1.83 source against the same NDK and headers.
clang="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
ar="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
for source in posix_api.cpp regex.cpp regex_debug.cpp static_mutex.cpp wide_posix_api.cpp; do
  "$clang" --target="aarch64-linux-android${API}" -fPIC -std=c++17 \
    -DBOOST_REGEX_NO_LIB -DBOOST_REGEX_SOURCE \
    -I"$BOOST_HEADERS" -I"$BOOST_REGEX/include" \
    -c "$BOOST_REGEX/src/$source" -o "$BUILD/boost/${source%.cpp}.o"
done
"$ar" rcs "$PREFIX/lib/libboost_regex.a" "$BUILD"/boost/*.o
cp -a "$BOOST_HEADERS/boost" "$PREFIX/include/"

build_dep() {
  local name="$1"; shift
  local source="$RIME/deps/$name"
  local build="$BUILD/deps/$name"
  cmake -S "$source" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF "$@"
  cmake --build "$build" --target install --parallel 2
}

build_dep leveldb -DLEVELDB_BUILD_BENCHMARKS=OFF -DLEVELDB_BUILD_TESTS=OFF
build_dep marisa-trie -DBUILD_TESTING=OFF -DENABLE_TOOLS=OFF
opencc_build="$BUILD/deps/opencc"
cmake -S "$RIME/deps/opencc" -B "$opencc_build" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_PREFIX_PATH="$PREFIX" -DLIBMARISA="$PREFIX/lib/libmarisa.a" \
  -DCMAKE_CXX_FLAGS="-I$PREFIX/include" \
  -DUSE_SYSTEM_MARISA=ON -DUSE_SYSTEM_DARTS=OFF -DENABLE_GTEST=OFF
cmake --build "$opencc_build" --target libopencc --parallel 2
mkdir -p "$PREFIX/include/opencc" "$PREFIX/lib"
cp -a "$RIME/deps/opencc/src/"*.h "$RIME/deps/opencc/src/"*.hpp "$PREFIX/include/opencc/"
cp "$opencc_build/src/libopencc.a" "$PREFIX/lib/"
build_dep yaml-cpp -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF

cmake -S "$RIME" -B "$BUILD/librime" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$PREFIX" \
  -DCMAKE_PREFIX_PATH="$PREFIX" -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
  -DBUILD_STATIC=ON -DBUILD_SHARED_LIBS=ON -DBUILD_MERGED_PLUGINS=ON \
  -DBUILD_SEPARATE_LIBS=OFF -DBUILD_TEST=OFF -DBUILD_SAMPLE=OFF \
  -DBUILD_DATA=OFF -DENABLE_LOGGING=OFF \
  -DBoost_ROOT="$PREFIX" -DBoost_INCLUDE_DIR="$PREFIX/include" \
  -DBoost_LIBRARY_DIR_RELEASE="$PREFIX/lib" \
  -DBoost_REGEX_LIBRARY_RELEASE="$PREFIX/lib/libboost_regex.a"
cmake --build "$BUILD/librime" --target install --parallel 2

llvm_strip="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
while IFS= read -r -d '' file; do "$llvm_strip" --strip-unneeded "$file"; done \
  < <(find "$PREFIX" -type f -name '*.so' -print0)
find "$PREFIX" -type f -name '*.so' -printf '%p %s bytes\n' | sort
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$PREFIX/lib/librime.so" | grep -E 'Class:|Machine:'
