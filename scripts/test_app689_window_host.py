#!/usr/bin/env python3
"""Run production Insets and preference guards, substituting only Android boundary types."""
import argparse,pathlib,re,subprocess,hashlib
p=argparse.ArgumentParser();p.add_argument('--out',required=True);p.add_argument('--baseline');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=pathlib.Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
k=(root/'app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java').read_text();s=(root/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
def block(src,signature):
 start=src.index(signature);opening=src.index('{',start);depth=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[opening:],re.S):
  if m.group()=='{':depth+=1
  elif m.group()=='}':
   depth-=1
   if depth==0:return src[start:opening+m.end()]
 raise ValueError(signature)
parts={'REGION':block(k,'public void touchableRegion('),'INSETS':block(s,'@Override public void onComputeInsets('),'FULLSCREEN':block(s,'@Override public boolean onEvaluateFullscreenMode('),'HAPTIC':block(k,'private void haptic(')}
if a.baseline:
    old=subprocess.check_output(['git','show',a.baseline+':app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java'],cwd=root,text=True)
    parts={'REGION':'public void touchableRegion(Region r,int x,int y){}','INSETS':'','FULLSCREEN':'',
           'HAPTIC':block(old,'private void vibrate(').replace('private void vibrate()','private void haptic(int kind)')}
    # In 814086d Insets/fullscreen are inherited; Base models their boundary, not a new product implementation.
t=(root/'scripts/app689_host/App689WindowHarness.java.in').read_text()
if not a.baseline:
    shared=(root/'app/src/main/java/com/simon/voiceime/KeyboardHaptics.java').read_text()
    methods=block(shared,'public static boolean enabled(').replace('Context c','Service c').replace('SharedPreferences p','Prefs p')+'\n'+block(shared,'public static boolean perform(')
    t=t.replace('/* SHARED_HAPTIC */','static class KeyboardHaptics {static int requests,calls,emitted;'+methods+'}')
else:t=t.replace('/* SHARED_HAPTIC */','')

for name,body in parts.items():t=t.replace('/* '+name+' */',body.replace('android.graphics.Region','Region').replace('android.inputmethodservice.InputMethodService.Insets','Insets').replace('HapticFeedbackConstants.CLOCK_TICK','4'))
(out/'App689WindowHarness.java').write_text(t);(out/'extraction.sha256').write_text('\n'.join(hashlib.sha256(body.encode()).hexdigest()+' '+name for name,body in parts.items())+'\n')
cp=':'.join(str(root/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(root/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java'),str(out/'App689WindowHarness.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App689WindowHarness']).returncode)
