#!/usr/bin/env python3
"""Execute original routing up to the request construction boundary (no Android/network)."""
import argparse,pathlib,re,subprocess,hashlib
p=argparse.ArgumentParser();p.add_argument('--out',required=True);p.add_argument('--baseline');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=pathlib.Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
path='app/src/phone/java/com/simon/voiceime/DualHandEditor.java'
src=subprocess.check_output(['git','show',a.baseline+':'+path],cwd=root,text=True) if a.baseline else (root/path).read_text()
def block(signature):
 start=src.index(signature);opening=src.index('{',start);depth=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[opening:],re.S):
  if m.group()=='{':depth+=1
  elif m.group()=='}':
   depth-=1
   if depth==0:return src[start:opening+m.end()].replace('@Override ','')
 raise ValueError(signature)
space=block('@Override public boolean space(');ai=block('@Override public void ai(');guard=ai[:ai.index('        Snapshot owner=')]+ '        requests++;\n    }'
restore=block('@Override public boolean restore(')
# Baseline restore reaches Android editor word deletion; network-boundary substitute records the unintended commit/delete route.
if a.baseline:restore=restore[:restore.index('        Snapshot now=')]+'        service.erased++;return true;\n    }'
t=(root/'scripts/app689_host/App689EditorHarness.java.in').read_text();t=t.replace('/* SPACE */',space).replace('/* AI_GUARD */',guard).replace('/* RESTORE */',restore)
(out/'App689EditorHarness.java').write_text(t);(out/'extraction.sha256').write_text(hashlib.sha256(src.encode()).hexdigest()+' '+path+'\n')
cp=':'.join(str(root/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
prod=root/'app/src/main/java/com/simon/voiceime';files=[prod/(n+'.java') for n in ['DualHandState','DualHandTracks','DualHandMemory','DualHandGesture','DualHandTable']]
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*map(str,files),str(out/'App689EditorHarness.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App689EditorHarness'],cwd=root).returncode)
