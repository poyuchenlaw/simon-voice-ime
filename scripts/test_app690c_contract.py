#!/usr/bin/env python3
"""C contract: independent spec values, immutable BASE behavior, exact voice ownership."""
import argparse, pathlib, re, subprocess, unittest, xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parents[1]);p.add_argument('--baseline-haptics',action='store_true');a=p.parse_args()
w=a.root;A='{http://schemas.android.com/apk/res/android}';path='app/src/main/java/com/simon/voiceime/SimonIMEService.java'
src=(w/path).read_text();xml=(w/'app/src/main/res/layout/keyboard_view.xml').read_text();root=ET.fromstring(xml)
base=subprocess.check_output(['git','-C',str(w),'show','130361d:'+path],text=True)
old=ET.fromstring(subprocess.check_output(['git','-C',str(w),'show','130361d:app/src/main/res/layout/keyboard_view.xml'],text=True))
def method(text,name):
 start=re.search(r'(?:private|public|protected) [^\n]+?\b'+name+r'\([^\n]*?\)\s*\{',text).start();opening=text.index('{',start);depth=0
 for t in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}]',text[opening:],re.S):
  if t.group()=='{':depth+=1
  elif t.group()=='}':
   depth-=1
   if depth==0:return text[start:opening+t.end()]
 raise AssertionError(name)
voice=next(n for n in root.iter() if n.get(A+'id')=='@+id/voiceKeyboard')
keys={n.get(A+'id')[5:]:n for n in voice.iter() if n.get(A+'text') is not None}
def tags(n):return {c.get(A+'id').split('/')[-1]:c.get(A+'value') for c in n if c.tag=='tag'}
class Contract(unittest.TestCase):
 def test_r1_r4_initialization(self):
  init=method(src,'onCreateInputView');self.assertIn('KeyboardTypeface.forLabel(this',init);self.assertIn('KeyboardLabels.functionLabel(',init);self.assertIn('KeyboardLabels.FUNCTION_TEXT_SP',init);self.assertIn('setSingleLine(true)',init);self.assertIn('setBackgroundTintList(null)',init)
  for name,n in keys.items():
   if n.get(A+'text') in ['全','半','追','注','，','。']:self.assertEqual('12sp',n.get(A+'textSize'),name)
 def test_r2_r6_no_legacy_colors(self):
  nodes=list(voice.iter())+[n for n in root if n.get(A+'id') in ['@+id/statusText','@+id/previewText','@+id/pendingVoiceCandidateNotice','@+id/notificationEntry']]
  hits=[(n.get(A+'id'),k,v) for n in nodes for k,v in n.attrib.items() if k in [A+'textColor',A+'background'] and (v.startswith('@color/') or v.startswith('#'))]
  self.assertEqual([],hits)
  for name in ['updateModeUI','cycleMode']:
   self.assertNotRegex(method(src,name),r'R.color|0x[0-9a-fA-F]+')
  for name in ['startRecording','finishStoppedRecordingAndSend']:
   self.assertNotRegex(method(src,name),r'btnMic.setBackgroundColor')
 def test_r2_r3_roles_and_inset(self):
  self.assertIn('key.setTag(R.id.keyboard_visible_inset, 0);',method(src,'onCreateInputView'))
  self.assertEqual(13,len(keys));allowed={'tools','core','background'}
  for n in voice.iter():
   if n.tag!='tag':self.assertIn(tags(n).get('keyboard_role'),allowed,n.tag)
  for name,n in keys.items():
   t=tags(n);self.assertEqual('0',t['keyboard_visible_inset']);self.assertEqual('core' if name in ['btnMic','btnEnter'] else 'tools',t['keyboard_role'])
  self.assertEqual('mode_append',tags(keys['btnMode'])['keyboard_text_role'])
 def test_r6_recording_behavior_unchanged(self):
  for name,role,oldRole in [('startRecording','recording','mic_active'),('finishStoppedRecordingAndSend','core','mic_idle')]:
   before=method(base,name);after=method(src,name)
   expected=f'KeyboardKeycaps.apply(btnMic, KeyboardRoles.dark(this), "{role}", 0);'
   self.assertIn(expected,after)
   self.assertEqual(before,after.replace(expected,f'btnMic.setBackgroundColor(getResources().getColor(R.color.{oldRole}, null));'))
 def test_r6_mode_sequence_and_text_unchanged(self):
  self.assertEqual(method(base,'cycleMode'),method(src,'cycleMode'))
  def without_colors(s):return re.sub(r'^.*(?:setTextColor|setTag\(R.id.keyboard_text_role).*\n','',s,flags=re.M)
  self.assertEqual(without_colors(method(base,'updateModeUI')),without_colors(method(src,'updateModeUI')))
 def test_r3_geometry_and_route_tags_unchanged(self):
  ignored={A+'background',A+'textColor',A+'textSize',A+'singleLine',A+'ellipsize'}
  def shape(n):return (n.tag,{k:v for k,v in n.attrib.items() if k not in ignored},[shape(c) for c in n if c.tag!='tag'])
  self.assertEqual(shape(old),shape(root))
  self.assertEqual([ET.tostring(n) for n in old if n.tag in ['include','FrameLayout']],[ET.tostring(n) for n in root if n.tag in ['include','FrameLayout']])
 def test_r5_one_shared_down_request(self):
  route=(w/'app/src/main/java/com/simon/voiceime/KeyboardTouchLayout.java').read_text()
  self.assertEqual(1,route.count('KeyboardHaptics.press(target.key)'))
  self.assertIn('if(action==MotionEvent.ACTION_DOWN){KeyboardKeycaps.press(target.key);KeyboardHaptics.press(target.key);}',route)
  self.assertNotIn('KeyboardHaptics.press(',method(src,'onCreateInputView'))
  for n in keys.values():self.assertTrue(n.tag=='Button' or n.get(A+'clickable')=='true')
 def test_r8_no_state_refit_or_view_rebuild(self):
  for name in ['updateModeUI','cycleMode','startRecording','finishStoppedRecordingAndSend']:
   self.assertNotRegex(method(src,name),r'KeyboardLabels.*fit|LayoutInflater|new (?:Button|TextView)')
  init=method(src,'onCreateInputView');anchor='        btnMic = rootView.findViewById(R.id.btnMic);';end='        btnMode = rootView.findViewById(R.id.btnMode);'
  at=init.index(anchor)+len(anchor);to=init.index(end)
  self.assertEqual(method(base,'onCreateInputView'),init[:at]+'\n'+init[to:])
 def test_a2_5_icon_class_and_sizes(self):
  for text,size in [('⚙','14sp'),('📋','14sp'),('⚡','14sp'),('␣','16sp'),('⌫','16sp'),('↵','16sp'),('🎤','28sp')]:
   self.assertEqual(size,next(n for n in keys.values() if n.get(A+'text')==text).get(A+'textSize'))
  manifest=__import__('json').loads((w/'app/src/phone/assets/fonts/SUBSET_MANIFEST.json').read_text())
  self.assertIn('␣',manifest['characters']);self.assertNotIn('␣',manifest['source_missing_characters'])
  for text in '🎤⏹📋⚡⚙⌫↵':self.assertIn(text,manifest['source_missing_characters'])
if __name__=='__main__':unittest.main(argv=['C-contract']+(['Contract.test_r5_one_shared_down_request'] if a.baseline_haptics else []),verbosity=2)
