#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?host output directory required}"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
SHARED_ASSETS="${SHARED_ASSETS:-$ROOT/app/src/phone/assets/rime}"
HOST_LIB_DIR="${HOST_LIB_DIR:-/home/simon/simon-voice-ime/evidence/rime_spike/host-install-octagram/lib}"
mkdir -p "$OUT/classes" "$OUT/lib" "$OUT/user" "$OUT/corpus-user" "$OUT/tmp"
JSON_JAR="$(rg --files /home/simon/simon-voice-ime-wt671/out/gradle-home/caches/modules-2/files-2.1/org.json/json/20231013 | rg '/json-20231013.jar$')"
CP="$JSON_JAR:/home/simon/android-sdk/platforms/android-34/android.jar"
"$JDK/bin/javac" -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
 "$ROOT/scripts/v639_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/AiComposition.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/RimeVocabularyInstaller.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/ZhuyinInputController.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinEngine.java" \
 "$ROOT/scripts/v671_hostsrc/Z12HostTest.java" \
 "$ROOT/scripts/v670_hostsrc/TextRows670HostTest.java"
TMPDIR="$OUT/tmp" g++ -std=c++17 -O2 -fPIC -shared -I"$JDK/include" -I"$JDK/include/linux" \
 -I"/home/simon/simon-voice-ime/evidence/rime_spike/android-prefix/include" -I"$ROOT/app/src/phone/cpp/rime_headers" \
 "$ROOT/app/src/phone/cpp/rime_jni.cpp" -L"$HOST_LIB_DIR" -lrime -Wl,-rpath,"$HOST_LIB_DIR" -o "$OUT/lib/librime_jni.so"
MAIN=com.simon.voiceime.Z12HostTest
if [[ "${CASE:-delete}" == transaction ]]; then MAIN=com.simon.voiceime.TextRows670HostTest; fi
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.io.tmpdir="$OUT/tmp" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" -cp "$OUT/classes:$CP" \
 "$MAIN" "$SHARED_ASSETS" "$OUT/user" "${CASE:-delete}"
