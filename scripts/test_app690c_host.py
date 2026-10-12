#!/usr/bin/env python3
"""Compile actual C init/mode and shared classification; AWT measures bundled ink."""
import pathlib,re,subprocess,xml.etree.ElementTree as ET
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690c-host';out.mkdir(parents=True,exist_ok=True)
s=(w/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
def method(name):
 start=s.index('    private void '+name+'(');opening=s.index('{',start);level=0
 for t in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}]',s[opening:],re.S):
  if t.group()=='{':level+=1
  if t.group()=='}':
   level-=1
   if level==0:return s[start:opening+t.end()]
start=s.index('        btnMic = rootView.findViewById(R.id.btnMic);')+len('        btnMic = rootView.findViewById(R.id.btnMic);');end=s.index('        btnMode = rootView.findViewById(R.id.btnMode);',start)
init=s[start:end];labels=(w/'app/src/main/java/com/simon/voiceime/KeyboardLabels.java').read_text();labels='static class KeyboardLabels {'+labels[labels.index('    public static final float'):labels.index('    public static float contentWidth(')]+'}'
A='{http://schemas.android.com/apk/res/android}';root=ET.parse(w/'app/src/main/res/layout/keyboard_view.xml').getroot();voice=next(n for n in root.iter() if n.get(A+'id')=='@+id/voiceKeyboard');keys=[n for n in voice.iter() if n.get(A+'text') is not None]
ids=[n.get(A+'id')[5:] for n in keys]+['keyboard_text_role','keyboard_visible_inset'];decl=''.join('static final int '+name+'='+str(i+1)+';' for i,name in enumerate(ids))
fixture=''.join('c.rootView.views.put(R.id.'+n.get(A+'id')[5:]+',new TextView("'+n.get(A+'text')+'",'+n.get(A+'textSize')[:-2]+'));' for n in keys)
t=(w/'scripts/app690c_host/App690CHarness.java.in').read_text().replace('/* IDS */',decl).replace('/* FIXTURE */',fixture).replace('/* LABELS */',labels).replace('/* INIT */',init).replace('/* MODES */',method('cycleMode')+'\n'+method('updateModeUI'))
j=out/'App690CHarness.java';j.write_text(t)
subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(j),str(w/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-Djava.awt.headless=true','-cp',str(out),'com.simon.voiceime.App690CHarness',str(w/'app/src/phone/assets/fonts/NotoSansTC-Medium-subset.ttf')]).returncode)
