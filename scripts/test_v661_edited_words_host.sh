#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-/home/simon/simon-voice-ime/evidence/v661/host}"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
HOST_LIB_DIR="${HOST_LIB_DIR:-$ROOT/evidence/rime_spike/host-install-octagram/lib}"
mkdir -p "$OUT/classes" "$OUT/lib" "$OUT/user"
"$JDK/bin/javac" -encoding UTF-8 -cp /home/simon/android-sdk/platforms/android-34/android.jar -d "$OUT/classes" \
 "$ROOT/scripts/v639_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/ZhuyinInputController.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/AiComposition.java" \
 "$ROOT/app/src/main/java/com/simon/voiceime/RimeVocabularyInstaller.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java" \
 "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinEngine.java" \
 "$ROOT/scripts/v660_hostsrc/LinkedCaretHostTest.java" \
 "$ROOT/scripts/v660_hostsrc/RealSlipHostTest.java" \
 "$ROOT/scripts/v660_hostsrc/LearnedSegmentHostTest.java" \
 "$ROOT/scripts/v659_hostsrc/InsertionCaretHost.java" \
 "$ROOT/scripts/v659_hostsrc/CorrectionPolicyHostTest.java" \
 "$ROOT/scripts/v661_hostsrc/EditedWordHostTest.java" \
 "$ROOT/scripts/v661_hostsrc/RestoredAlternativeHostTest.java"
g++ -std=c++17 -O2 -fPIC -shared -I"$JDK/include" -I"$JDK/include/linux" \
 -I"/home/simon/simon-voice-ime/evidence/rime_spike/android-prefix/include" -I"$ROOT/app/src/phone/cpp/rime_headers" \
 "$ROOT/app/src/phone/cpp/rime_jni.cpp" -L"$HOST_LIB_DIR" -lrime -Wl,-rpath,"$HOST_LIB_DIR" -o "$OUT/lib/librime_jni.so"
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" -cp "$OUT/classes:/home/simon/android-sdk/platforms/android-34/android.jar" com.simon.voiceime.EditedWordHostTest "$ROOT/app/src/phone/assets/rime" "$OUT/user"
mkdir -p "$OUT/restored-user"
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" -cp "$OUT/classes:/home/simon/android-sdk/platforms/android-34/android.jar" com.simon.voiceime.RestoredAlternativeHostTest "$ROOT/app/src/phone/assets/rime" "$OUT/restored-user"
