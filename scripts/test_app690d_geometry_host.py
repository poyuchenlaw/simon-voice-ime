#!/usr/bin/env python3
"""Execute D's product fitting body with AWT ink metrics and XML-derived Android rows.
Host geometry evidence; Android shaping/rounding and icon pixels remain X1 checks.
"""
import os
import pathlib, subprocess, xml.etree.ElementTree as ET
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690d-geometry';out.mkdir(parents=True,exist_ok=True)
src=pathlib.Path(os.environ.get('APP690D_SOURCE_ROOT',w))
a='{http://schemas.android.com/apk/res/android}'
rows=[]
for page in ['keyboard_english','keyboard_numbers']:
 root=ET.parse(src/('app/src/main/res/layout/'+page+'.xml')).getroot()
 for row in root:
  keys=[k for k in row if k.get(a+'tag','').startswith('key:')]
  if keys:
   rows.append((page,[(k.get(a+'text'),k.get(a+'tag'),k.get(a+'layout_width'),k.get(a+'layout_weight','0'),k.get(a+'layout_marginEnd','0dp'),k.get(a+'textSize')) for k in keys]))
# Keep row truth from the actual XML; widths/margins/counts are checked separately against BASE.
java_rows=[]
for page,keys in rows:
 args=[]
 for text,route,width,weight,margin,size in keys:
  import json
  args.append('new KeySpec('+','.join([json.dumps(text,ensure_ascii=False),json.dumps(route,ensure_ascii=False),width[:-2] if width.endswith('dp') else '0',weight+'f',margin[:-2],size[:-2]])+')')
 java_rows.append('rows.add(new Row("'+page+'",List.of('+','.join(args)+')));')
source=(src/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
def body(source,sig):
 start=source.index(sig);pos=source.index('{',start);depth=1;end=pos+1
 while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
fit=body(source,'private void fitDTypingPage(') if 'private void fitDTypingPage(' in source else 'private void fitDTypingPage(View page,List<TextView> keys,KeyboardLabels.Cache cache) {}'
labels=(w/'app/src/main/java/com/simon/voiceime/KeyboardLabels.java').read_text()
labels='static class KeyboardLabels {'+labels[labels.index('{')+1:labels.rfind('}')].replace('public final','public final')+'}'
t=(w/'scripts/app690d_host/App690DGeometry.java.in').read_text().replace('/* FIT */',fit).replace('/* LABELS */',labels).replace('/* ROWS */','\n'.join(java_rows))
(out/'App690DGeometry.java').write_text(t)
subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(out/'App690DGeometry.java')],cwd=w,check=True)
raise SystemExit(subprocess.run(['java','-ea','-Djava.awt.headless=true','-cp',str(out),'App690DGeometry'],cwd=w).returncode)
