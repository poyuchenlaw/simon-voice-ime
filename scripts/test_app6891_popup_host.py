#!/usr/bin/env python3
"""Execute production popup coordinates/lifecycle with Android coordinate boundary fakes."""
import argparse, pathlib, re, subprocess
p=argparse.ArgumentParser();p.add_argument('--out',required=True);p.add_argument('--baseline',action='store_true');a=p.parse_args()
w=pathlib.Path(__file__).resolve().parents[1];out=w/a.out;out.mkdir(parents=True,exist_ok=True)
k=(w/'app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java').read_text()
e=(w/'app/src/phone/java/com/simon/voiceime/DualHandEditor.java').read_text()
s=(w/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
if a.baseline:
 def old(rel):return subprocess.check_output(['git','-C',str(w),'show','68a3756:'+rel],text=True)
 k=old('app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java');e=old('app/src/phone/java/com/simon/voiceime/DualHandEditor.java');s=old('app/src/main/java/com/simon/voiceime/SimonIMEService.java')
def block(src,sig):
 start=src.index(sig);opening=src.index('{',start);depth=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[opening:],re.S):
  if m.group()=='{':depth+=1
  elif m.group()=='}':
   depth-=1
   if depth==0:return src[start:opening+m.end()]
 raise ValueError(sig)
f=block(k,'private void feedback(');f=f[f.index('bubbleLabel.setText(value);'):];f='void bubblePosition(){'+f
m=block(e,'private void showMarker(');m=m[m.index('if(bounds!=null&&Float.isFinite'):m.index('markerExpiry=this::dismissMarker')];m='void markerPosition(){'+m+'}'
m=m.replace('android.util.DisplayMetrics','DisplayMetrics')
start=block(s,'public void onStartInput(');start=start[:start.index('cursorTapGuard.reset();')]+'}';start=start.replace('try {','').replace('@Override','').replace('public void onStartInput','void onStartInput')
request=block(s,'void dualHandCursorUpdates(')
stop=block(s,'private void dualHandStopCursorUpdates(') if 'private void dualHandStopCursorUpdates(' in s else 'private void dualHandStopCursorUpdates(){}'
finish=block(s,'public void onFinishInputView(');finish=finish[finish.index('if (rootView != null) rootView.setKeepScreenOn(false);'):finish.index('super.onFinishInputView')];finish='void finishInputView(){'+finish+'}'
anchor=block(e,'public void cursorAnchor(').replace('CursorAnchorInfo','Object')
dismiss=block(e,'private void dismissMarker(')
# Palette and dispatched-inset bodies are actual production statements.
palette=block(k,'private void applyPalette(');palette='void applyPalette(){'+palette[palette.index('{')+1:palette.index('topBar.setBackgroundColor')]+ '}'
listener=k[k.index('root.setOnApplyWindowInsetsListener'):];listener=listener[listener.index('{')+1:listener.index('});')];listener=listener.replace('android.graphics.Insets','Insets');listener='Object inset(WindowInsets in){'+listener+'}'
if 'private void applyInsets(' in k: listener+=block(k,'private void applyInsets(')
layout=k[k.index('root.addOnLayoutChangeListener'):];layout=layout[layout.index('{')+1:layout.index('});')];layout='void layout(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){'+layout+'}'
t=(w/'scripts/app689_host/App6891PopupHarness.java.in').read_text()
for n,body in [('BUBBLE',f),('MARKER',m),('START',start),('REQUEST',request),('PALETTE',palette),('INSET',listener),('LAYOUT',layout),('STOP',stop),('FINISH',finish),('ANCHOR',anchor),('DISMISS',dismiss)]:t=t.replace('/* '+n+' */',body)
(out/'App6891PopupHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(w/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java'),str(out/'App6891PopupHarness.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App6891PopupHarness']).returncode)
