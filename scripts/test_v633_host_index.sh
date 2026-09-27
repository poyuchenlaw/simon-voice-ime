#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/evidence/v633/host"
cc -I"$ROOT/third_party/libchewing/capi/include" "$OUT/index_acceptance.c" \
  -L"$ROOT/out/v632-host" -Wl,-rpath,"$ROOT/out/v632-host" -lchewing -lsqlite3 \
  -o "$OUT/index_acceptance"
mkdir -p "$OUT/user"
cp "$ROOT/out/v632-host-install/share/libchewing/"*.dat "$OUT/system/"
rm -f "$OUT/user/chewing.dat"
"$OUT/index_acceptance" "$ROOT/app/src/phone/assets/zhuyin_initials.db" \
  "$OUT/system" "$OUT/user/chewing.dat" \
  "$ROOT/app/src/phone/assets/personal_seed.csv" \
  2>&1 | tee "$OUT/index_acceptance.log"
