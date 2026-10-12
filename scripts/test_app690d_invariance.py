#!/usr/bin/env python3
"""Compare immutable routes, geometry and output bodies with the frozen baseline."""
import pathlib,re,subprocess,unittest,xml.etree.ElementTree as ET
w=pathlib.Path(__file__).resolve().parents[1];base='130361d2f405a1ff545d279c3311e6b9031a8ca2';a='{http://schemas.android.com/apk/res/android}'
def before(path):return subprocess.check_output(['git','show',base+':'+path],cwd=w,text=True)
def method(source,sig):
 start=source.index(sig);pos=source.index('{',start);depth=1;end=pos+1
 while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
class Invariance(unittest.TestCase):
 def test_routes_structure_and_all_geometry_attributes_unchanged(self):
  for name in ['keyboard_english','keyboard_numbers','clipboard_panel','commands_panel','clip_item','cmd_item']:
   path='app/src/main/res/layout/'+name+'.xml'
   def tree(source):
    root=ET.fromstring(source);return [(v.tag,{k:value for k,value in v.attrib.items() if k.startswith(a+'layout_') or k in [a+'tag',a+'id',a+'clickable',a+'focusable',a+'orientation',a+'gravity']}) for v in root.iter() if v.tag!='tag']
   self.assertEqual(tree(before(path)),tree((w/path).read_text()),name)
 def test_same_operation_outputs_are_identical_product_bodies(self):
  path='app/src/main/java/com/simon/voiceime/SimonIMEService.java';old=before(path);new=(w/path).read_text()
  for sig in ['private void onTypingKeyPressed(', 'private void updateLetterCase(', 'private void applyEnglishSuggestion(', 'private void commitSymbolFromPopup(', 'protected boolean pasteClipboardText(', 'private static class CmdAdapter ']:
   self.assertEqual(method(old,sig),method(new,sig),sig)
  for sig in ['private void showClipboardPanel()', 'private void showCommandsPanel()', 'private void showAddToCommandsInline(', 'private void showBatchAddToCommandsInline(List', 'private void showVocabListInline()']:
   pattern=r'(?:commandsHelper|clipboardHelper|markedClips|enrolledVocab)\.\w+\([^;\n]*'
   self.assertEqual(re.findall(pattern,method(old,sig)),re.findall(pattern,method(new,sig)),sig)
 def test_symbol_lists_and_gesture_source_identical(self):
  for path in ['app/src/main/java/com/simon/voiceime/SymbolPopupSpec.java','app/src/main/java/com/simon/voiceime/DualHandGesture.java']:
   self.assertEqual(before(path),(w/path).read_text(),path)
if __name__=='__main__':unittest.main(verbosity=2)
