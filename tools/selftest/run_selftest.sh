#!/usr/bin/env bash
# Compile + run the OnDeviceCorrector JVM self-test (no Android device needed).
set -euo pipefail
export JAVA_HOME=/home/simon/.local/jdk
export PATH="/home/simon/.local/jdk/bin:$PATH"
REPO=/home/simon/simon-voice-ime
OUT=/tmp/odc_test
rm -rf "$OUT"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
  "$REPO/app/src/main/java/com/simon/voiceime/correct/OpenCcS2tw.java" \
  "$REPO/app/src/main/java/com/simon/voiceime/correct/OnDeviceCorrector.java" \
  "$REPO/app/src/main/java/com/simon/voiceime/correct/TimeoutWall.java" \
  "$REPO/tools/selftest/OnDeviceCorrectorSelfTest.java"
cd "$REPO"
java -Dfile.encoding=UTF-8 -cp "$OUT" OnDeviceCorrectorSelfTest
