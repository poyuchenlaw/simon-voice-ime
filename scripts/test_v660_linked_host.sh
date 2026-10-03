#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/evidence/v660/host}"
JDK="${JAVA_HOME:-/home/simon/.local/jdk/jdk-17.0.2}"
HOST_LIB_DIR="${HOST_LIB_DIR:-/home/simon/simon-voice-ime/evidence/rime_spike/host-install-octagram/lib}"
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
 "$ROOT/scripts/v659_hostsrc/CorrectionPolicyHostTest.java"
g++ -std=c++17 -O2 -fPIC -shared -I"$JDK/include" -I"$JDK/include/linux" \
 -I"/home/simon/simon-voice-ime/evidence/rime_spike/android-prefix/include" -I"$ROOT/app/src/phone/cpp/rime_headers" \
 "$ROOT/app/src/phone/cpp/rime_jni.cpp" -L"$HOST_LIB_DIR" -lrime -Wl,-rpath,"$HOST_LIB_DIR" -o "$OUT/lib/librime_jni.so"
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" \
 -cp "$OUT/classes:/home/simon/android-sdk/platforms/android-34/android.jar" com.simon.voiceime.LinkedCaretHostTest "$ROOT/app/src/phone/assets/rime" "$OUT/user"


mkdir -p "$OUT/slip-user"
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" \
 -cp "$OUT/classes:/home/simon/android-sdk/platforms/android-34/android.jar" com.simon.voiceime.RealSlipHostTest "$ROOT/app/src/phone/assets/rime" "$OUT/slip-user"

mkdir -p "$OUT/learned-user"
LD_LIBRARY_PATH="$HOST_LIB_DIR:$OUT/lib" "$JDK/bin/java" -Djava.library.path="$OUT/lib:$HOST_LIB_DIR" \
 -cp "$OUT/classes:/home/simon/android-sdk/platforms/android-34/android.jar" com.simon.voiceime.LearnedSegmentHostTest "$ROOT/app/src/phone/assets/rime" "$OUT/learned-user"

# Exercise exact production word projection with native sessions and specification-derived oracles.
python3 - "$ROOT" "$OUT" "$JDK" <<'PY_WORD_PROJECTION'
from pathlib import Path
import subprocess,os,json,hashlib,re,sys
W=Path(sys.argv[1]);E=Path(sys.argv[2]);C=E/'junit-classes';C.mkdir(exist_ok=True)
J=Path(sys.argv[3])/'bin';cache=Path('/home/simon/.gradle/caches/modules-2/files-2.1')
jars=[next((cache/p).rglob(name)) for p,name in [('junit/junit/4.13.2','junit-4.13.2.jar'),('org.hamcrest/hamcrest-core/1.3','hamcrest-core-1.3.jar'),('org.json/json/20231013','json-20231013.jar')]]
cp=':'.join(map(str,jars))+':/home/simon/android-sdk/platforms/android-34/android.jar'
source=W/'app/src/main/java/com/simon/voiceime';phone=source/'AiSentencePhone.java';src=phone.read_text()
def method(signature):
 start=src.index(signature);opening=src.index('{',start);level=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[opening:],re.S):
  if m.group()=='{':level+=1
  elif m.group()=='}':
   level-=1
   if level==0:return src[start:opening+m.end()]
 raise RuntimeError('unclosed method')
project=method('private List<JSONObject> project(');focused=method('private boolean focused(')
body='''package com.simon.voiceime;
import org.json.*;import java.util.*;
class AiSentencePhoneProjectionHarness {
 interface Host { ZhuyinInputController controller(); }
 final Host host;final JSONObject request;
 String clausePrefix="",clauseKeyPrefix="",projectionWitness="";
 final Map<JSONObject,List<JSONObject>> projections=new IdentityHashMap<>();
 AiSentencePhoneProjectionHarness(ZhuyinInputController c,JSONObject req){host=()->c;request=req;}
 void event(String value){throw new AssertionError(value);}
'''+focused+'\n'+project.replace('private List<JSONObject> project(','List<JSONObject> project(',1)+'\n}'
# Only access visibility changed; original full method bytes and hashes are retained.
(E/'projection-extraction.json').write_text(json.dumps({'source':str(phone),'sha256':hashlib.sha256(phone.read_bytes()).hexdigest(),'project_sha256':hashlib.sha256(project.encode()).hexdigest(),'project_body':project,'focused_body':focused,'boundary':'component seam, not Android Looper/adapter lifecycle'},indent=2))
generated=E/'AiSentencePhoneProjectionHarness.java';generated.write_text(body)
files=[source/n for n in ['AiSentence.java','SentenceContract.java','AiComposition.java','ZhuyinInputController.java','RimeVocabularyInstaller.java']]+[W/'scripts/v639_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java']+[W/'app/src/phone/java/com/simon/voiceime'/n for n in ['ZhuyinKeyMap.java','RimeZhuyinEngine.java','RimeZhuyinNative.java']]+[generated,W/'scripts/v660_hostsrc/AiWordSpanCompositionTest.java',W/'app/src/testPhone/java/com/simon/voiceime/AiSentenceTest.java']
subprocess.run([str(J/'javac'),'-encoding','UTF-8','-cp',cp,'-d',str(C),*map(str,files)],check=True)
factory=True;user=E/('junit-user-green' if factory else 'junit-user-red');user.mkdir(exist_ok=True)
H=Path(os.environ.get('HOST_LIB_DIR','/home/simon/simon-voice-ime/evidence/rime_spike/host-install-octagram/lib'));lib=E/'lib'
command=[str(J/'java'),f'-Djava.library.path={lib}:{H}',f'-Dr5.assets={W}/app/src/phone/assets/rime',f'-Dr5.contract={W}/app/src/main/assets/sentence-contract.json',f'-Dr5.user={user}',f'-Dr5.factory={str(factory).lower()}','-cp',str(C)+':'+str(W/'app/src/testPhone/resources')+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.AiWordSpanCompositionTest']
if factory:command+=['com.simon.voiceime.AiSentenceTest']
r=subprocess.run(command,env=dict(os.environ,LD_LIBRARY_PATH=f'{H}:{lib}'))
raise SystemExit(r.returncode)

PY_WORD_PROJECTION
