#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${IME_HOST_SOURCE_ROOT:-/home/simon/simon-voice-ime}"
OUT="${1:-$ROOT/evidence/v650b/host}"
ASSETS="${2:-$ROOT/app/src/phone/assets/rime}"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
HOST="$SOURCE/evidence/rime_spike/host-install-octagram"
mkdir -p "$OUT/classes" "$OUT/lib" "$OUT/files/rime/user"
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT/classes" \
  "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java" \
  "$ROOT/scripts/v650b_hostsrc/com/simon/voiceime/VocabularyHostTest.java" \
  "$ROOT/app/src/main/java/com/simon/voiceime/RimeVocabularyInstaller.java"
"${CXX:-g++}" -std=c++17 -O2 -fPIC -shared \
  -I"$JDK/include" -I"$JDK/include/linux" -I"$SOURCE/evidence/rime_spike/android-prefix/include" -I"$ROOT/app/src/phone/cpp/rime_headers" \
  "$ROOT/app/src/phone/cpp/rime_jni.cpp" -L"$HOST/lib" -lrime -Wl,-rpath,"$HOST/lib" -o "$OUT/lib/librime_jni.so"
LD_LIBRARY_PATH="$HOST/lib:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST/lib" \
  -cp "$OUT/classes" com.simon.voiceime.VocabularyHostTest "$ASSETS" "$OUT/files"
