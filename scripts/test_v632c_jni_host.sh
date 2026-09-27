#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
HOST_LIB="$ROOT/out/v632-host"
JNI_INC="$JDK/include"
WORK="$ROOT/evidence/v632c"
mkdir -p "$WORK/hostlib" "$WORK/system" "$WORK/user" "$WORK/classes"
cp "$ROOT/out/v632-host-install/share/libchewing/word.dat" "$ROOT/out/v632-host-install/share/libchewing/tsi.dat" "$WORK/system/"
cp "$HOST_LIB/libchewing.so" "$WORK/hostlib/"
gcc -shared -fPIC -O2 \
  -I"$ROOT/third_party/libchewing/capi/include" -I"$JNI_INC" -I"$JNI_INC/linux" \
  "$ROOT/app/src/phone/cpp/chewing_jni.c" -L"$HOST_LIB" -lchewing \
  -Wl,-rpath,"$HOST_LIB" -o "$WORK/hostlib/libchewing_jni.so"
"$JDK/bin/javac" -d "$WORK/classes" \
  "$WORK/hostsrc/com/simon/voiceime/ChewingEngine.java" \
  "$WORK/hostsrc/com/simon/voiceime/V632cNativeSmoke.java"
LOG="$WORK/jni-host-test.log"
"$JDK/bin/java" -Xcheck:jni -Djava.library.path="$WORK/hostlib" -cp "$WORK/classes" \
  com.simon.voiceime.V632cNativeSmoke "$WORK/system" "$WORK/user/chewing.dat" 2>&1 | tee "$LOG"
if grep -E 'WARNING in (native method|call)|JNI DETECTED ERROR' "$LOG"; then
  echo "JNI CheckJNI warning/error detected" >&2
  exit 1
fi
