#!/usr/bin/env python3
import pathlib,subprocess
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690a-shared';out.mkdir(parents=True,exist_ok=True)
t=(w/'scripts/app690a_host/App690ASharedHarness.java.in').read_text()
source=(w/'app/src/main/java/com/simon/voiceime/KeyboardTouchLayout.java').read_text()
start=source.index('            if(action==MotionEvent.ACTION_DOWN){KeyboardKeycaps.press')
end=source.index('            boolean consumed = target.key.dispatchTouchEvent',start)
prefix=source[start:end].replace('target.key','target')
t=t.replace('/* ROUTED_PRESS */','void dispatchPrefix(int action,View target){'+prefix+'}')
labels=(w/'app/src/main/java/com/simon/voiceime/KeyboardLabels.java').read_text()
start=labels.index('    public static String labelClass(');end=labels.index('    public static String functionLabel(',start)
t=t.replace('/* CLASSIFY */','static class KeyboardLabels {'+labels[start:end]+'}')


for name,cls in [('FONT','KeyboardTypeface'),('HAPTICS','KeyboardHaptics'),('ROLES','KeyboardRoles'),('CANDIDATES','KeyboardCandidates')]:
 s=(w/'app/src/main/java/com/simon/voiceime'/(cls+'.java')).read_text();body=s[s.index('{')+1:s.rfind('}')]
 t=t.replace('/* '+name+' */','static class '+cls+' {'+body+'}')
(out/'App690ASharedHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])+':'+str(next((w/'out/gradle-home/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar')))
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(out/'App690ASharedHarness.java'),str(w/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java')],check=True)
for missing in [False,True]:
 r=subprocess.run(['java','-ea','-Dapp690a.fontMissing='+str(missing).lower(),'-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690ASharedHarness'])
 if r.returncode:raise SystemExit(r.returncode)
