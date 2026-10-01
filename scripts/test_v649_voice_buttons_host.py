#!/usr/bin/env python3
"""Compile and execute the actual voice-page listener with an Android View double.
Expected targets and row order come from WORKORDER_v649, not implementation.
"""
from pathlib import Path
import re, subprocess, tempfile, unittest, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
SOURCE=(ROOT/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
class VoiceButtons(unittest.TestCase):
    def run_listener(self, sequence):
        listener=re.search(r'btnSwitchIME\.setOnClickListener\([^;]+;', SOURCE).group()
        code='''public class VoiceButtonHost {
 enum KeyboardMode {VOICE,BOPOMOFO,ENGLISH,NUMBERS}
 KeyboardMode page=KeyboardMode.VOICE;
 interface Click {void click(View v);} static class View {Click c;void setOnClickListener(Click c){this.c=c;}void press(){c.click(this);}}
 void switchKeyboard(KeyboardMode m){page=m;}
 void run(){View btnSwitchIME=new View();LISTENER
 SEQUENCE
 if(page!=KeyboardMode.BOPOMOFO)throw new AssertionError("expected BOPOMOFO, got "+page);}
 public static void main(String[] a){new VoiceButtonHost().run();}
}'''.replace('LISTENER',listener).replace('SEQUENCE',sequence)
        with tempfile.TemporaryDirectory(dir=ROOT/'out') as d:
            p=Path(d)/'VoiceButtonHost.java';p.write_text(code)
            subprocess.run(['javac','-d',d,str(p)],check=True)
            result=subprocess.run(['java','-cp',d,'VoiceButtonHost'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
    def test_voice_bottom_left_goes_to_zhuyin(self):self.run_listener('btnSwitchIME.press();')
    def test_round_trip_does_not_remember_english(self):
        self.run_listener('btnSwitchIME.press();switchKeyboard(KeyboardMode.ENGLISH);switchKeyboard(KeyboardMode.VOICE);btnSwitchIME.press();')
    def test_voice_rows_and_label(self):
        a='{http://schemas.android.com/apk/res/android}'
        tree=ET.parse(ROOT/'app/src/main/res/layout/keyboard_view.xml');rows=[]
        for node in tree.iter('LinearLayout'):
            ids=[n.get(a+'id','').split('/')[-1] for n in node if n.tag=='TextView']
            if 'btnSettings' in ids or 'btnSwitchIME' in ids:rows.append(ids)
        self.assertEqual(rows,[['btnSettings','btnFullWidth','btnHalfWidth','btnClipboard','btnCommands','btnMode'],['btnSwitchIME','btnSpace','btnComma','btnPeriod','btnBackspace','btnEnter']])
        switch=next(n for n in tree.iter() if n.get(a+'id')=='@+id/btnSwitchIME')
        self.assertEqual(switch.get(a+'text'),'注')
    def test_long_press_picker_preserved(self):
        self.assertRegex(SOURCE,r'btnSwitchIME.setOnLongClickListener\([\s\S]*?imm.showInputMethodPicker\(\)')
if __name__=='__main__':
    (ROOT/'out').mkdir(exist_ok=True);unittest.main(verbosity=2)
