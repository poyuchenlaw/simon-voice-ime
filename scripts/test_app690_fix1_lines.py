#!/usr/bin/env python3
from pathlib import Path
import os,re,subprocess
from fontTools.ttLib import TTFont
w=Path(__file__).resolve().parents[1];out=w/'out/app690-fix1-lines';out.mkdir(parents=True,exist_ok=True)
subprocess.run(['python3','scripts/test_app690_style_host.py','--out',str(out),'--skip-tools'],cwd=w,check=True)
source=(w/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
def body(name,end):return source[source.index('private void '+name+'('):source.index(end,source.index('private void '+name+'('))]
b=source[source.index('private void configureBopomofoStyle('):source.index('// Content changes need phonetic spans;')]
d=body('collectDTypingLabels','private void fitDTypingPage(')
padding=['false' if 'key.setIncludeFontPadding(false)' in v else 'true' for v in [b,d]]
font=TTFont(w/'app/src/phone/assets/fonts/NotoSansTC-Medium-subset.ttf');u=font['head'].unitsPerEm
subprocess.run(['javac','-encoding','UTF-8','-cp',str(out),'-d',str(out),str(w/'scripts/app690_host/App690Fix1LineHarness.java')],cwd=w,check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out),'com.simon.voiceime.App690Fix1LineHarness',str(out/'layouts.tsv'),*padding,str(font['head'].yMax/u),str(-font['head'].yMin/u)],cwd=w).returncode)
