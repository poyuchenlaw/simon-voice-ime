#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$ROOT/scripts/apply_librime_patches.sh"
cmake -S "$ROOT/third_party/librime" -B "$ROOT/evidence/v651a/host-build" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$ROOT/evidence/rime_spike/host-install-octagram" \
  -DCMAKE_PREFIX_PATH="$ROOT/evidence/rime_spike/host-prefix;$ROOT/evidence/rime_spike/boost-prefix" \
  -DBoost_ROOT="$ROOT/evidence/rime_spike/boost-prefix" \
  -DBUILD_STATIC=ON -DBUILD_SHARED_LIBS=ON -DBUILD_MERGED_PLUGINS=ON \
  -DBUILD_TEST=OFF -DBUILD_SAMPLE=OFF -DBUILD_DATA=OFF -DENABLE_LOGGING=OFF
cmake --build "$ROOT/evidence/v651a/host-build" --target install --parallel 4
