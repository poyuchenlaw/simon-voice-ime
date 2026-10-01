#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/evidence/v651a/complete-host}"
HOST_LIB_DIR="${HOST_LIB_DIR:-$ROOT/evidence/rime_spike/host-install-octagram/lib}"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
mkdir -p "$OUT/classes" "$OUT/lib" "$OUT/user" "$OUT/tmp"
"$JDK/bin/javac" -encoding UTF-8 -d "$OUT/classes" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/RimeVocabularyInstaller.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java" \
 "$ROOT/scripts/v651a_hostsrc/com/simon/voiceime/CompleteSyllablesHostTest.java"
TMPDIR="$OUT/tmp" g++ -std=c++17 -O2 -fPIC -shared \
 -I"$JDK/include" -I"$JDK/include/linux" -I"$ROOT/evidence/rime_spike/android-prefix/include" \
 -I"$ROOT/app/src/phone/cpp/rime_headers" "$ROOT/app/src/phone/cpp/rime_jni.cpp" \
 -L"$HOST_LIB_DIR" -lrime -Wl,-rpath,"$HOST_LIB_DIR" -o "$OUT/lib/librime_jni.so"
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" \
 -cp "$OUT/classes" com.simon.voiceime.CompleteSyllablesHostTest "$ROOT/app/src/phone/assets/rime" "$OUT/user"
