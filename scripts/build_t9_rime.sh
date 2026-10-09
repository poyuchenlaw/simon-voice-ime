#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
HOST=${RIME_HOST_PREFIX:-/home/simon/simon-voice-ime/evidence/rime_spike/host-install-octagram}
DATA=${RIME_SOURCE_DATA:-/home/simon/simon-voice-ime/evidence/rime_spike/octagram-data}
STAGE="$ROOT/out/t9-rime-build"
mkdir -p "$STAGE/shared" "$STAGE/user"
cp -a "$ROOT/app/src/phone/assets/rime/." "$STAGE/shared/"
for file in terra_pinyin.dict.yaml essay.txt zhuyin.yaml bopomofo.schema.yaml; do
 cp "$DATA/$file" "$STAGE/shared/"
done
LD_LIBRARY_PATH="$HOST/lib" "$HOST/bin/rime_deployer" --compile "$STAGE/shared/bopomofo_t9_simon.schema.yaml" "$STAGE/shared" "$STAGE/user"
cp "$STAGE/shared/build/bopomofo_t9_simon.schema.yaml" "$STAGE/shared/build/bopomofo_t9_simon.prism.bin" "$ROOT/app/src/phone/assets/rime/build/"
python3 "$ROOT/scripts/check_phone_rime_assets.py" --compiled-only
