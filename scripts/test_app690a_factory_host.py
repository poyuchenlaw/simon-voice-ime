#!/usr/bin/env python3
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--mutate-inset',action='store_true');a=p.parse_args()
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690a-factory';out.mkdir(parents=True,exist_ok=True)
s=(w/'app/src/main/java/com/simon/voiceime/KeyboardKeycaps.java').read_text();body=s[s.index('{')+1:s.rfind('}')]
if a.mutate_inset:body=body.replace('r.inset(inset,inset)','r.inset(0,0)')
t=(w/'scripts/app690a_host/App690AFactoryHarness.java.in').read_text().replace('/* FACTORY */','static class KeyboardKeycaps {'+body+'}')
(out/'App690AFactoryHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(out/'App690AFactoryHarness.java'),str(w/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690AFactoryHarness']).returncode)
