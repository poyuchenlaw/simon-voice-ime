#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/phone/debug/app-phone-debug.apk}"
TELEMETRY="${2:-/home/simon/ime-telemetry/data}"
JDK="${JAVA_HOME:-/home/simon/.local/jdk}"
HOST="$ROOT/evidence/rime_spike/host-install-octagram"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/apk" "$TMP/classes" "$TMP/lib" "$TMP/user"
unzip -q "$APK" 'assets/rime/*' 'lib/arm64-v8a/*.so' -d "$TMP/apk"
python3 "$ROOT/scripts/check_phone_rime_assets.py" "$TMP/apk/assets/rime" --compiled-only
cmp -s "$TMP/apk/lib/arm64-v8a/librime.so" "$ROOT/app/src/phone/jniLibs/arm64-v8a/librime.so" || {
  echo "APK librime.so differs from the Gradle-produced native artifact" >&2; exit 1;
}
python3 - "$TELEMETRY" "$TMP/keys.tsv" <<'PY'
import json, sys
from collections import defaultdict
from pathlib import Path
groups=defaultdict(list)
files=sorted(Path(sys.argv[1]).glob('*.jsonl'))
for path in files:
    for line in path.read_text(encoding='utf-8').splitlines():
        try: row=json.loads(line)
        except Exception: continue
        if row.get('type')=='key' and row.get('page')=='bopomofo':
            key=row.get('key')
            if isinstance(key,str) and key:
                groups[row.get('session_id') or 'unknown'].append((str(row.get('ts','')),key))
with open(sys.argv[2],'w',encoding='utf-8') as out:
    for events in groups.values():
        events.sort(key=lambda event:event[0])
        out.write('\t'.join(key for _,key in events)+'\n')
print(f"telemetry replay input: files={len(files)} groups={len(groups)} bopomofo_key_events={sum(len(v) for v in groups.values())}")
PY
"$JDK/bin/javac" -encoding UTF-8 -d "$TMP/classes" \
  "$ROOT/scripts/v639_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java" \
  "$ROOT/scripts/v639_hostsrc/com/simon/voiceime/LoggedKeysReplay.java" \
  "$ROOT/app/src/main/java/com/simon/voiceime/ZhuyinInputController.java" \
  "$ROOT/app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java" \
  "$ROOT/app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java"
"${CXX:-g++}" -std=c++17 -O2 -fPIC -shared \
  -I"$JDK/include" -I"$JDK/include/linux" -I"$ROOT/evidence/rime_spike/android-prefix/include" \
  "$ROOT/app/src/phone/cpp/rime_jni.cpp" -L"$HOST/lib" -lrime \
  -Wl,-rpath,"$HOST/lib" -o "$TMP/lib/librime_jni.so"
LD_LIBRARY_PATH="$HOST/lib:$TMP/lib" "$JDK/bin/java" \
  -Djava.library.path="$TMP/lib:$HOST/lib" -cp "$TMP/classes" \
  com.simon.voiceime.LoggedKeysReplay "$TMP/apk/assets/rime" "$TMP/user" < "$TMP/keys.tsv"
echo "NOTE: APK assets and its extracted ARM64 librime.so hash were checked; execution used the x86_64 host librime build because this host cannot load APK ARM64 libraries."
