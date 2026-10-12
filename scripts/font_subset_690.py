#!/usr/bin/env python3
"""Generate the fixed-label subset, reproducibly; no download or dependency installation."""
import argparse, ast, hashlib, json, pathlib, re, xml.etree.ElementTree as ET
import fontTools
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools import subset
W=pathlib.Path(__file__).resolve().parents[1]
LAYOUTS=['keyboard_view','keyboard_bopomofo','keyboard_english','keyboard_numbers','clipboard_panel','commands_panel','clip_item','cmd_item']
METHODS=['renderTextCandidateRows','pooledTextChoice','renderZhuyinCandidates','appendZhuyinCandidateBatch','sentenceOption','sentenceUndo','shownTextPreview','renderZhuyinShortcuts','configureTextRows','configurePreviewViewport','cycleMode','updateModeUI','startRecording','finishStoppedRecordingAndSend','updateSuggestionBar','showClipboardPanel','ClipAdapter','showCommandsPanel','showSymbolPopupOrFallback','buildSymbolPopupContent','buildChoiceGrid','updateShiftUI','dualHandPanel','showAddToCommandsInline','showPanel','showBatchAddToCommandsInline','showVocabListInline']
ANDROID='{http://schemas.android.com/apk/res/android}'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def literals(src):
 for token in re.findall(r'"(?:\\.|[^"\\])*"',src):
  try:yield json.loads(token)
  except ValueError:pass

def method(src,name):
 m=re.search(r'(?m)^[ \t]*(?:@Override\s+)?(?:(?:public|private|protected|static|final)\s+)*[\w.<>\[\]]+\s+'+re.escape(name)+r'\s*\([^;{}]*\)\s*\{',src)
 if not m:m=re.search(r'(?:private\s+)?class\s+'+re.escape(name)+r'\b[^{}]*\{',src)
 if not m:return ''
 start=m.end()-1;level=0
 for t in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[start:],re.S):
  if t.group()=='{':level+=1
  if t.group()=='}':
   level-=1
   if level==0:return src[m.start():start+t.end()]
 return ''
def required(root):
 chars=set(chr(cp) for cp in range(0x20,0x7f))|set(chr(cp) for cp in range(0x3105,0x312a))|set('˙ˊˇˋØ，。、；：？！「」『』（）…—～');sources=[]
 res=root/'app/src/main/res';strings={}
 for p in sorted(res.glob('values*/strings.xml')):
  for node in ET.parse(p).getroot():
   if node.tag=='string':strings.setdefault(node.get('name'),set()).update(''.join(node.itertext()))
 for name in LAYOUTS:
  p=res/'layout'/(name+'.xml')
  if not p.exists():continue
  for node in ET.parse(p).iter():
   for attr in ['text','hint']:
    value=node.get(ANDROID+attr,'')
    chars.update(strings.get(value[8:],set()) if value.startswith('@string/') else value if not value.startswith('@') else '')
  sources.append({'path':str(p.relative_to(root)),'method':'XML android:text/android:hint; referenced @string resolved in values*/strings.xml'})
 for name in ['dual_hand_map.tsv','left_hand.tsv']:
  p=root/'app/src/phone/assets/dual_hand'/name
  for line in p.read_text().splitlines()[1:]:chars.update(line.split('\t')[-1])
  sources.append({'path':str(p.relative_to(root)),'method':'last TSV output column'})
 main=root/'app/src/main/java/com/simon/voiceime';phone=root/'app/src/phone/java/com/simon/voiceime'
 p=main/'SymbolPopupSpec.java';chars.update(''.join(literals(p.read_text())));sources.append({'path':str(p.relative_to(root)),'method':'all Java string literals (symbol lists)'})
 p=phone/'DualHandKeyboard.java';src=p.read_text()
 for call in re.finditer(r'(?:functionLabels|text)\([^;\n]+',src):chars.update(''.join(literals(call.group())))
 sources.append({'path':str(p.relative_to(root)),'method':'functionLabels/text invocation string literals'})
 p=main/'SimonIMEService.java';src=p.read_text()
 for name in METHODS:
  body=method(src,name)
  if body:chars.update(''.join(literals(body)));sources.append({'path':str(p.relative_to(root)),'method':'Java strings in scoped '+name})
 # Strip XML escape control characters and metadata; fixed printable text remains.
 chars={c for c in chars if ord(c)>=0x20 and c not in '\r\n\t'}
 for rel in ['app/src/phone/assets/fonts/KEYCAP_EXTRA_CHARS.txt','scripts/app690_host/PROBE_KEYCAP_CHARS.txt']:
  p=root/rel
  if p.exists():
   for line in p.read_text().splitlines():
    if not line.startswith('#'):chars.update(line)
  sources.append({'path':rel,'method':'non-comment UTF-8 literal lines','present':p.exists()})
 return chars,sources

def generate(root,source,target):
 chars,sources=required(root);font=TTFont(source,recalcTimestamp=False);source_cmap=set(map(chr,font.getBestCmap()));missing=sorted(chars-source_cmap)
 medium=instantiateVariableFont(font,{'wght':500},inplace=False);medium.recalcTimestamp=False
 options=subset.Options();options.recalc_timestamp=False
 sub=subset.Subsetter(options=options);sub.populate(unicodes=map(ord,chars));sub.subset(medium)
 target.mkdir(parents=True,exist_ok=True);out=target/'NotoSansTC-Medium-subset.ttf';medium.save(out)
 manifest={'source':str(source),'source_sha256':sha(source),'fonttools_version':fontTools.__version__,'weight':500,'character_count':len(chars),'characters':''.join(sorted(chars)),'source_missing_characters':''.join(missing),'source_missing_codepoints':[f'U+{ord(c):04X}' for c in missing],'output_sha256':sha(out),'bytes':out.stat().st_size,'command':'python3 scripts/font_subset_690.py','extraction':sources,'probe_present':(root/'scripts/app690_host/PROBE_KEYCAP_CHARS.txt').exists()}
 (target/'SUBSET_MANIFEST.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:manifest[k] for k in ['character_count','bytes','output_sha256','source_missing_codepoints','probe_present']}))
 if out.stat().st_size>300*1024:raise SystemExit('subset exceeds 300KB')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,default=W);p.add_argument('--source',type=pathlib.Path,default=pathlib.Path('/home/simon/.local/share/fonts/NotoSansTC-VF.ttf'));p.add_argument('--out',type=pathlib.Path);a=p.parse_args();generate(a.root,a.source,a.out or a.root/'app/src/phone/assets/fonts')
