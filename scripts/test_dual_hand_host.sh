#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/out/dual-hand-test}"
mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" "$ROOT"/app/src/main/java/com/simon/voiceime/DualHand{Gesture,Table,State}.java "$ROOT/scripts/DualHandHostTest.java"
java -ea -cp "$OUT" com.simon.voiceime.DualHandHostTest "$ROOT/app/src/phone/assets/dual_hand"
