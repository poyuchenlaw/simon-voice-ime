#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:?output directory}"
mkdir -p "$OUT"
PREFIX="$ROOT/evidence/v670_d1/host-prefix"
cp "$ROOT/app/src/phone/assets/rime/build/bopomofo_express.prism.bin" "$OUT/renamed.prism.bin"
TMPDIR="$ROOT/out/tmp" g++ -std=c++17 -O2 -I"$ROOT/third_party/librime/src" -I"$ROOT/app/src/phone/cpp/rime_headers" -I"$PREFIX/include" -I"$ROOT/evidence/rime_spike/android-prefix/include" "$ROOT/scripts/v670_d1_hostsrc/native/SchemaSyllableGraphTest.cpp" -L"$PREFIX/lib" -lrime -Wl,-rpath,"$PREFIX/lib" -o "$OUT/graph-test"
"$OUT/graph-test" "$ROOT/app/src/phone/assets/rime/build/bopomofo_express.prism.bin" "$OUT/renamed.prism.bin"

TMPDIR="$ROOT/out/tmp" g++ -std=c++17 -O2 -I"$ROOT/third_party/librime/src" -I"$ROOT/app/src/phone/cpp/rime_headers" -I"$PREFIX/include" -I"$ROOT/evidence/rime_spike/android-prefix/include" "$ROOT/scripts/v670_d1_hostsrc/native/CompletePreferenceBoundaryTest.cpp" -L"$PREFIX/lib" -lrime -Wl,-rpath,"$PREFIX/lib" -o "$OUT/boundary-test"
"$OUT/boundary-test" "$ROOT/app/src/phone/assets/rime/build/bopomofo_express.prism.bin" "$ROOT/app/src/phone/assets/rime/build/terra_pinyin.table.bin"

TMPDIR="$ROOT/out/tmp" g++ -std=c++17 -O2 -I"$ROOT/third_party/librime/src" -I"$ROOT/app/src/phone/cpp/rime_headers" -I"$PREFIX/include" -I"$ROOT/evidence/rime_spike/android-prefix/include" "$ROOT/scripts/v670_d1_hostsrc/native/CompleteCandidateScoreProbe.cpp" -L"$PREFIX/lib" -lrime -Wl,-rpath,"$PREFIX/lib" -o "$OUT/score-probe"
mkdir -p "$OUT/score-user"
"$OUT/score-probe" "$ROOT/app/src/phone/assets/rime" "$OUT/score-user"
