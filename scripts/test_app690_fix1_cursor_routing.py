#!/usr/bin/env python3
from pathlib import Path
import subprocess
w=Path(__file__).resolve().parents[1];out=w/'out/app690-fix1-routing';out.mkdir(parents=True,exist_ok=True)
source=(w/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
start=source.index('@Override public void onUpdateCursorAnchorInfo(');end=source.index('    private void toggleDualHand(',start)
body=source[start:end].replace('android.view.inputmethod.CursorAnchorInfo','Object')
java='''class Base {public void onUpdateCursorAnchorInfo(Object info){}}
public class Fix1CursorRouting extends Base {
 enum KeyboardMode {BOPOMOFO,VOICE,ENGLISH,NUMBERS}
 static class Receiver {int calls;void cursorAnchor(Object info){calls++;}}
 boolean dualHandSelected;KeyboardMode currentKeyboardMode;Receiver dualHandPage=new Receiver(),shared=new Receiver();
 boolean correctionEnabled(){return currentKeyboardMode==KeyboardMode.VOICE||currentKeyboardMode==KeyboardMode.BOPOMOFO&&!dualHandSelected;}
 Receiver correctionEditor(){return shared;}
 BODY
 public static void main(String[] args){
  for(KeyboardMode mode:KeyboardMode.values())for(boolean pref:new boolean[]{false,true}){
   Fix1CursorRouting s=new Fix1CursorRouting();s.currentKeyboardMode=mode;s.dualHandSelected=pref;s.onUpdateCursorAnchorInfo(new Object());
   int dual=mode==KeyboardMode.BOPOMOFO&&pref?1:0,shared=mode==KeyboardMode.VOICE||mode==KeyboardMode.BOPOMOFO&&!pref?1:0;
   System.out.println(mode+" remembered_dual="+pref+" dual="+s.dualHandPage.calls+" shared="+s.shared.calls);
   assert s.dualHandPage.calls==dual&&s.shared.calls==shared:"cursor anchor must reach only the active correction page "+mode+"/"+pref;
  }
 }
}
'''.replace('BODY',body)
(out/'Fix1CursorRouting.java').write_text(java)
subprocess.run(['javac','-encoding','UTF-8','-d',str(out),str(out/'Fix1CursorRouting.java')],cwd=w,check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out),'Fix1CursorRouting'],cwd=w).returncode)
