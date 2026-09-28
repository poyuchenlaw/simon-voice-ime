#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
WORK="$ROOT/evidence/v634"
HOST_LIB="$ROOT/out/v632-host"
mkdir -p "$WORK/hostlib" "$WORK/system" "$WORK/user" "$WORK/classes"
cp "$ROOT/app/src/phone/assets/libchewing/"*.dat "$WORK/system/"
cp "$HOST_LIB/libchewing.so" "$WORK/hostlib/"
gcc -shared -fPIC -O2 \
  -I"$ROOT/third_party/libchewing/capi/include" -I"$JDK/include" -I"$JDK/include/linux" \
  "$ROOT/app/src/phone/cpp/chewing_jni.c" -L"$WORK/hostlib" -lchewing \
  -Wl,-rpath,"$WORK/hostlib" -o "$WORK/hostlib/libchewing_jni.so"
"$JDK/bin/javac" -d "$WORK/classes" \
  "$WORK/hostsrc/com/simon/voiceime/ChewingEngine.java" \
  "$WORK/hostsrc/com/simon/voiceime/V634SelectionRegression.java"
LOG="$WORK/jni-selection-test.log"
"$JDK/bin/java" -Xcheck:jni -Djava.library.path="$WORK/hostlib" -cp "$WORK/classes" \
  com.simon.voiceime.V634SelectionRegression "$WORK/system" "$WORK/user/chewing.dat" 2>&1 | tee "$LOG"
if grep -E 'WARNING in (native method|call)|JNI DETECTED ERROR' "$LOG"; then
  echo "JNI CheckJNI warning/error detected" >&2; exit 1
fi
