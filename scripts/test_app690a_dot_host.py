#!/usr/bin/env python3
import pathlib,re,subprocess,argparse
p=argparse.ArgumentParser();p.add_argument('--mutate-always-dot',action='store_true');a=p.parse_args()
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690a-dots';out.mkdir(parents=True,exist_ok=True)
s=(w/'app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java').read_text();start=s.index('@Override protected void onDraw(Canvas canvas)');opening=s.index('{',start);depth=0
for m in re.finditer(r'"(?:\\.|[^"\\])*"|[{}]',s[opening:]):
 if m.group()=='{':depth+=1
 if m.group()=='}':
  depth-=1
  if depth==0:body=s[start:opening+m.end()];break
body=body.replace('@Override protected void onDraw','void draw').replace('super.onDraw(canvas);','')
if a.mutate_always_dot:body=body.replace('DualHandVisuals.guideDot(output(key,"tap"))','true')
t=(w/'scripts/app690a_host/App690AGuideDotsHarness.java.in').read_text().replace('/* DRAW */',body)
(out/'App690AGuideDotsHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(out/'App690AGuideDotsHarness.java'),*[str(w/'app/src/main/java/com/simon/voiceime'/(n+'.java')) for n in ['DualHandTable','DualHandVisuals']]],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690AGuideDotsHarness']).returncode)
