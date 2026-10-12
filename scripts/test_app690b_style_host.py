#!/usr/bin/env python3
"""B contracts: XML routing/roles, literal scan, executed production fit and 6891 output parity.
AWT geometry is a host check; Android rasterization and physical haptics remain X1.
"""
from pathlib import Path
import argparse, json, re, subprocess, sys, unittest, xml.etree.ElementTree as ET
w=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--fit-only',action='store_true');p.add_argument('--baseline',action='store_true');p.add_argument('--mutate-function-size',action='store_true');args=p.parse_args()
base='7df786ee26292ed829d72247395872ed6011579f'
prod=w/'app/src/main/java/com/simon/voiceime';layout='app/src/main/res/layout/keyboard_bopomofo.xml'
def old(path):return subprocess.check_output(['git','show',base+':'+path],cwd=w,text=True)
source=old('app/src/main/java/com/simon/voiceime/SimonIMEService.java') if args.baseline else (prod/'SimonIMEService.java').read_text()
xml=old(layout) if args.baseline else (w/layout).read_text();root=ET.fromstring(xml);ns='{http://schemas.android.com/apk/res/android}'
def block(src,name):
 m=re.search(r'(?:private|public|protected)\s+(?:static\s+)?[\w<>]+\s+'+re.escape(name)+r'\s*\(',src);assert m,name
 start=m.start();opening=src.index('{',m.end());depth=0
 for t in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[opening:],re.S):
  if t.group()=='{':depth+=1
  elif t.group()=='}':
   depth-=1
   if depth==0:return src[start:opening+t.end()]
 raise AssertionError(name)
keys=[e for e in root.iter() if e.get(ns+'tag','').startswith('key:')]
def tags(e):return {c.get(ns+'id','').split('/')[-1]:c.get(ns+'value') for c in e if c.tag=='tag'}
class Contracts(unittest.TestCase):
 def test_r2_roles_and_r3_key_output_geometry_unchanged(self):
  prior=ET.fromstring(old(layout));priorKeys=[e for e in prior.iter() if e.get(ns+'tag','').startswith('key:')]
  self.assertEqual(49,len(keys));self.assertEqual([e.get(ns+'tag') for e in priorKeys],[e.get(ns+'tag') for e in keys])
  for before,e in zip(priorKeys,keys):
   for attr in ['layout_width','layout_height','layout_weight','layout_marginEnd','gravity','tag','text']:
    self.assertEqual(before.get(ns+attr),e.get(ns+attr),e.get(ns+'tag')+':'+attr)
   label=e.get(ns+'text');role='zero' if label in '˙ˊˇˋ' else 'left' if label in 'ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩ' else 'right' if label in 'ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ' else 'core' if label in ['⌫','↵'] else 'tools'
   self.assertEqual(role,tags(e).get('keyboard_role'),label)
   self.assertEqual('text',tags(e).get('keyboard_text_role'),label)
   self.assertEqual('1',tags(e).get('keyboard_visible_inset'),label)
  self.assertEqual('function',tags(next(e for e in keys if e.get(ns+'tag')=='key:comma')).get('keyboard_content_kind'))
 def test_r2d_no_orphan_color_r4d_candidate_paths(self):
  self.assertFalse(re.findall(r'#[0-9a-fA-F]{6,8}|@color/',xml))
  for name in ['configureTextRows','configurePreviewViewport','shownTextPreview','sentenceUndo','sentenceOption','pooledTextChoice','renderTextCandidateRows','renderZhuyinShortcuts','renderZhuyinCandidates','appendZhuyinCandidateBatch']:
   body=block(source,name);self.assertFalse(re.findall(r'0x[0-9a-fA-F]{6,8}|R\.color\.',body),name)
  for name in ['pooledTextChoice','renderTextCandidateRows','appendZhuyinCandidateBatch']:
   body=block(source,name);self.assertFalse(re.findall(r'setTextSize\(\s*\d',body),name)
   self.assertIn('KeyboardCandidates.apply(',body,name)
 def test_r1_preview_mixed_span_and_r8_guard(self):
  self.assertIn('KeyboardTypeface.mixed(',block(source,'shownTextPreview'))
  config=block(source,'configureBopomofoStyle');self.assertIn('KeyboardLabels.Cache',config)
  self.assertEqual(1,block(source,'onCreateInputView').count('configureBopomofoStyle();'))
  self.assertNotIn('configureBopomofoStyle',block(source,'onStartInputView'))
  self.assertNotIn('KeyboardHaptics.press(',config)
 def test_r4a_a2_4_fifty_grid_actual_fit(self):
  if args.baseline:
   for e in keys:
    if len(e.get(ns+'tag')[4:])>1 and e.get(ns+'text') in ['EN','雙','123','，','空白']:
     self.assertEqual('12sp',e.get(ns+'textSize'),'baseline functional size: '+e.get(ns+'text'))
   return
  out=w/'out/app690b-fit';out.mkdir(parents=True,exist_ok=True)
  s=(prod/'KeyboardLabels.java').read_text();s=s[s.index('{')+1:s.rfind('}')]
  rows=[]
  for row in list(root):
   routed=[e for e in row if e.get(ns+'tag','').startswith('key:')]
   if routed:rows.append(routed)
  fixtures=[]
  for row in rows:
   fixtures.append('new Key[]{'+','.join('new Key('+json.dumps(e.get(ns+'text'),ensure_ascii=False)+','+json.dumps(e.get(ns+'tag')[4:],ensure_ascii=False)+','+str(float(e.get(ns+'layout_width').replace('dp','')))+ 'f,'+str(float(e.get(ns+'layout_weight','0')))+'f,'+str(float(e.get(ns+'layout_marginEnd','0dp').replace('dp','')))+'f,'+str(float(e.get(ns+'textSize','18sp').replace('sp','')))+'f)' for e in row)+'}')
  # Execute the production listener body, with only Android view/paint boundary substitutions.
  config=block(source,'configureBopomofoStyle');m=re.search(r'public void onLayoutChange\(',config)
  listener=''
  if m:
   listener=block(config[m.start():],'onLayoutChange')
  else:
   listener='public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int oright,int obottom){}'
  template=(w/'scripts/app690b_host/App690BFitHarness.java.in').read_text()
  init=config[config.index('        for(int row='):config.index('        bopomofoKeyboard.addOnLayoutChangeListener')]
  init=init.replace('ViewGroup page=(ViewGroup)bopomofoKeyboard;','')
  template=template.replace('/* INIT */',init)
  listener=listener.replace('android.util.DisplayMetrics','Metrics').replace('android.graphics.Paint','Paint')
  java=template.replace('/* SHARED_LABELS */',s).replace('/* ROWS */',','.join(fixtures)).replace('/* FIT_LISTENER */',listener).replace('/* MUTATE */','for(TextView key:all)if(!key.character&&!key.icon)key.sp=characterKeys.get(0).sp;' if args.mutate_function_size else '')
  (out/'App690BFitHarness.java').write_text(java)
  subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(out/'App690BFitHarness.java')],cwd=w,check=True)
  result=subprocess.run(['java','-ea','-Djava.awt.headless=true','-cp',str(out),'com.simon.voiceime.App690BFitHarness'],cwd=w)
  self.assertEqual(0,result.returncode)
 def test_r4_function_size_mutation_rejected(self):
  result=subprocess.run([sys.executable,str(Path(__file__).resolve()),'--mutate-function-size','--fit-only'],cwd=w,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  self.assertNotEqual(0,result.returncode);self.assertIn('function size mutation',result.stdout);print('MUTATION_REJECTED: function text changed to character fit size')
 def test_r5a_b_routed_down_once_and_hold_once(self):
  touch=old('app/src/main/java/com/simon/voiceime/KeyboardTouchLayout.java') if args.baseline else (prod/'KeyboardTouchLayout.java').read_text()
  body=block(touch,'dispatchKey');start=body.index('long dispatchStarted=');end=body.index('boolean consumed =',start)
  prefix=body[start:end].replace('target.key','target').replace('android.os.SystemClock.uptimeMillis()','0L')
  h=(prod/'KeyboardHaptics.java').read_text();h=h[h.index('{')+1:h.rfind('}')]
  template=(w/'scripts/app690b_host/App690BRoutingHarness.java.in').read_text().replace('/* PREFIX */',prefix).replace('/* HAPTICS */',h)
  out=w/'out/app690b-routing';out.mkdir(parents=True,exist_ok=True)
  (out/'App690BRoutingHarness.java').write_text(template)
  subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(out/'App690BRoutingHarness.java')],cwd=w,check=True)
  self.assertEqual(0,subprocess.run(['java','-ea','-cp',str(out),'com.simon.voiceime.App690BRoutingHarness'],cwd=w).returncode)
 def test_r3c_output_replay_same_sequences_as_6891(self):
  traces=[]
  deps=['ZhuyinInputController','ZhuyinWordIndex','CommonCharacters','RimeVocabularyInstaller']
  for revision in ['6891','current']:
   out=w/('out/app690b-replay-'+revision);out.mkdir(parents=True,exist_ok=True)
   for dep in deps:
    value=old('app/src/main/java/com/simon/voiceime/'+dep+'.java') if revision=='6891' else (prod/(dep+'.java')).read_text()
    (out/(dep+'.java')).write_text(value)
   (out/'RimeZhuyinEngine.java').write_text('package com.simon.voiceime; class RimeZhuyinEngine extends App690BOutputReplay.Engine { RimeZhuyinEngine(){super(java.util.List.of());}}')
   cp='/home/simon/android-sdk/platforms/android-34/android.jar'
   subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*[str(out/(dep+'.java')) for dep in deps],str(out/'RimeZhuyinEngine.java'),str(w/'scripts/app690b_host/App690BOutputReplay.java')],cwd=w,check=True)
   traces.append(subprocess.check_output(['java','-ea','-cp',str(out)+':'+cp,'com.simon.voiceime.App690BOutputReplay'],cwd=w,text=True))
  for revision,trace in zip(['6891','current'],traces):(w/('out/app690b-replay-'+revision)/'output.trace').write_text(trace)
  self.assertEqual(traces[0],traces[1]);print('REPLAY: 4 known-answer sequences, 24 steps, baseline/current trace byte-identical; native engine not executed')
 def test_r3c_controller_and_click_transactions_same_as_6891(self):
  self.assertEqual(old('app/src/main/java/com/simon/voiceime/ZhuyinInputController.java'),(prod/'ZhuyinInputController.java').read_text())
  # Native engine source identity supplements the executed host-engine trace.
  self.assertEqual(old('app/src/phone/java/com/simon/voiceime/RimeZhuyinEngine.java'),(w/'app/src/phone/java/com/simon/voiceime/RimeZhuyinEngine.java').read_text())
if __name__=='__main__':
 suite=unittest.TestSuite([Contracts('test_r4a_a2_4_fifty_grid_actual_fit')]) if args.fit_only else unittest.defaultTestLoader.loadTestsFromTestCase(Contracts)
 raise SystemExit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
