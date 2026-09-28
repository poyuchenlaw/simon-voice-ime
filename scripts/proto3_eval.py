#!/usr/bin/env python3
"""Re-run the existing 200-utterance corpus via App JNI + ZhuyinInputController."""
import json, subprocess, sys, shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
WORK=ROOT/'evidence/proto3/eval'
HOST=ROOT/'out/v632-host'
JDK=Path('/home/simon/.local/jdk/jdk-17.0.2')
for source,target in [('cases.jsonl','cases.jsonl'),('corpus_g2pw.jsonl','corpus_g2pw.jsonl')]:
    src=ROOT/'evidence/rime_spike/data'/source
    dst=WORK/target
    if src.resolve()!=dst.resolve(): dst.write_bytes(src.read_bytes())
runner=WORK/'input.tsv'
if not runner.exists():
    rows=[]
    for line in (ROOT/'evidence/rime_spike/data/chewing_runner_input.tsv').read_text().splitlines():
        p=line.split('\t')
        if len(p)==3 and p[1] in ('A','B'): rows.append(line)
    runner.write_text('\n'.join(rows)+'\n')
(WORK/'hostlib').mkdir(exist_ok=True); (WORK/'system').mkdir(exist_ok=True); (WORK/'user').mkdir(exist_ok=True); (WORK/'classes').mkdir(exist_ok=True)
for entry in (WORK/'user').iterdir():
    shutil.rmtree(entry) if entry.is_dir() else entry.unlink()
for f in (ROOT/'app/src/phone/assets/libchewing').glob('*.dat'): (WORK/'system'/f.name).write_bytes(f.read_bytes())
(WORK/'hostlib/libchewing.so').write_bytes((HOST/'libchewing.so').read_bytes())
subprocess.run(['gcc','-shared','-fPIC','-O2','-I'+str(ROOT/'third_party/libchewing/capi/include'),
 '-I'+str(JDK/'include'),'-I'+str(JDK/'include/linux'),str(ROOT/'app/src/phone/cpp/chewing_jni.c'),
 '-L'+str(WORK/'hostlib'),'-lchewing','-Wl,-rpath,'+str(WORK/'hostlib'),'-o',str(WORK/'hostlib/libchewing_jni.so')],check=True)
subprocess.run([str(JDK/'bin/javac'),'-d',str(WORK/'classes'),
 str(ROOT/'scripts/proto3_hostsrc/com/simon/voiceime/ChewingEngine.java'),
 str(ROOT/'scripts/proto3_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java'),
 str(ROOT/'scripts/proto3_hostsrc/com/simon/voiceime/Proto3StreamingRegression.java'),
 str(ROOT/'scripts/proto3_hostsrc/com/simon/voiceime/Proto3Eval.java'),
 str(ROOT/'app/src/main/java/com/simon/voiceime/ZhuyinInputController.java'),
 str(ROOT/'app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java')],check=True)
with runner.open('rb') as src, (WORK/'proto3_chewing_results.tsv').open('wb') as out:
    subprocess.run([str(JDK/'bin/java'),'-Xcheck:jni','-Djava.library.path='+str(WORK/'hostlib'),'-cp',str(WORK/'classes'),
        'com.simon.voiceime.Proto3Eval',str(WORK/'system'),str(WORK/'user')],stdin=src,stdout=out,check=True)
print('App JNI/controller replay complete:',WORK/'proto3_chewing_results.tsv')
