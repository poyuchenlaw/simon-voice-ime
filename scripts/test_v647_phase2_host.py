#!/usr/bin/env python3
"""Source-exact keyboard callbacks + real controller, with only editor/Android boundaries faked."""
import argparse, pathlib, re, subprocess, tempfile, hashlib, json, os
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--space',action='store_true');p.add_argument('--out',default='evidence/v647/phase2-host');a=p.parse_args()
out=ROOT/a.out;out.mkdir(parents=True,exist_ok=True)
service=(ROOT/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
telemetry=(ROOT/'app/src/main/java/com/simon/voiceime/ImeTelemetry.java').read_text()
def method(text,name):
    m=re.search(r'(?:private|protected|public|static)?\s*(?:static\s+)?(?:void|boolean)\s+'+name+r'\s*\(',text);assert m,name
    start=m.start();opening=text.index('{',start);level=0
    for token in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',text[opening:],re.S):
        if token.group()=='{':level+=1
        if token.group()=='}':
            level-=1
            if level==0:return text[start:opening+token.end()]
    raise AssertionError(name)
parts=[method(service,n) for n in ['onTypingKeyPressed','recordBopomofoKeyOutcome','commitPunctuation','recordCandidateEvent']]
source='''package com.simon.voiceime;
import java.util.*;import org.json.*;
public class Phase2KeyboardHost {
 static class InputConnection {String text="";}
 static class EditorInfo {static final int IME_FLAG_NO_ENTER_ACTION=1;}
 static final String TAG="test";static class Log {static void w(String t,String m,Exception e){throw new RuntimeException(e);}}
 static class SystemClock {static long elapsedRealtime(){return 123;}}
 enum KeyboardMode {VOICE,BOPOMOFO,ENGLISH,NUMBERS}
 static class Engine implements ZhuyinInputController.Engine {
  String text="現在回辨識",commit="";
  public void key(String k){text+=k;}public void backspace(){}public void enter(){commit=text;text="";}
  public void space(){commit=text;text="";}public void choose(int i){}public void moveCursor(String d){}
  public int cursorPosition(){return text.length();}public String composingText(){return text;}
  public List<String> candidates(){return Arrays.asList("現在回辨識");}public String takeCommit(){String t=commit;commit="";return t;}public void clear(){text="";}
 }
 static class Telemetry {
  final List<JSONObject> events=new ArrayList<>();void noteInput(){}
  void record(String type,String page,JSONObject fields,boolean protect){try{if(!protect)events.add(fields.put("type",type).put("page",page));}catch(Exception e){throw new RuntimeException(e);}}
 TELEMETRY_METHODS
 }
 static class BopomofoKeyTouch {
  String key;float x=1.25f,y=-2.5f,centerX=100,centerY=200;JSONObject shadow=new JSONObject();
  BopomofoKeyTouch(String k){key=k;}
 }
 static class ShadowBoundary {
  final TouchModel model=new TouchModel(Arrays.asList(new TouchModel.Key("outer","ㄅ",0,0,90,90),new TouchModel.Key("outer","ㄆ",90,0,90,90)));
  final TouchModelShadow core=new TouchModelShadow(model);
  void confirmChoice(){core.confirmChoice();}void discardTrace(){core.discardTrace();}
 }
 private final ShadowBoundary touchShadow=new ShadowBoundary();
 private Telemetry imeTelemetry=new Telemetry();private BopomofoKeyTouch pendingBopomofoKeyTouch;
 private boolean protectedInputField,shiftActive,capsLock;private KeyboardMode currentKeyboardMode=KeyboardMode.BOPOMOFO;
 private StringBuilder enWordBuffer=new StringBuilder();private final InputConnection editor=new InputConnection();
 private ZhuyinInputController zhuyinInput=new ZhuyinInputController(new Engine());
 private InputConnection getCurrentInputConnection(){return editor;}
 private void applyZhuyinState(ZhuyinInputController.State s){if(!s.commitText.isEmpty())commitTextProgrammatically(editor,s.commitText);}
 private boolean commitTextProgrammatically(InputConnection ic,String text){ic.text+=text;return true;}
 private boolean commitTextSafely(String text){return commitTextProgrammatically(editor,text);}
 private void toggleShift(){}private void learnEnglishWord(){}private void clearEnWordBuffer(){}
 private void handleEnterKey(){}private void switchKeyboard(KeyboardMode mode){}private void refreshEnglishSuggestions(){}private void updateShiftUI(){}
 private static boolean isBopomofoSymbol(String k){return "ㄅ".equals(k);}
 SERVICE_METHODS
 public static void main(String[] args)throws Exception {
  Phase2KeyboardHost h=new Phase2KeyboardHost();h.commitPunctuation("，");
  check(h.editor.text.equals("現在回辨識，"),"punctuation must commit sentence once: "+h.editor.text);
  System.out.println("PASS production punctuation callback inserts prefix/replacement/suffix exactly once");
  h=new Phase2KeyboardHost();h.touchShadow.core.press("outer","ㄅ",20,0);
  h.recordCandidateEvent("bopomofo",Arrays.asList("字","詞"),0);
  h.touchShadow.core.press("outer","ㄆ",90,0);
  check(h.touchShadow.model.parameters("outer","ㄅ").count==1,"top-1 explicit candidate confirmed before continue/commit");
  check(h.touchShadow.model.parameters("outer","ㄆ").count==0,"new key not auto-confirmed");
  h.recordCandidateEvent("bopomofo",Arrays.asList("字","詞"),1);check(h.touchShadow.core.confirmChoice()==0,"corrective choice cannot invent intended-key labels");
  h=new Phase2KeyboardHost();h.touchShadow.core.press("outer","ㄅ",20,0);h.protectedInputField=true;
  h.recordCandidateEvent("bopomofo",Arrays.asList("字"),0);check(h.touchShadow.model.parameters("outer","ㄅ").count==0,"protected choice never trains");
  System.out.println("PASS production candidate acceptance: continued typing, corrective choices and protected-field learning");
  if(args.length>0){
   h=new Phase2KeyboardHost();h.pendingBopomofoKeyTouch=new BopomofoKeyTouch("space");h.onTypingKeyPressed("space");
   check(h.imeTelemetry.events.size()==1,"Space must produce one key event; got "+h.imeTelemetry.events);
   JSONObject e=h.imeTelemetry.events.get(0);check(e.getString("type").equals("key")&&e.getString("key").equals("space"),"Space type/key");
   check(e.getDouble("x")==1.25&&e.getDouble("y")==-2.5&&e.getDouble("key_center_x")==100&&e.has("key_to_candidate_ms"),"physical geometry and latency");
   check(h.editor.text.equals("現在回辨識"),"logging never changes Space result");
   h=new Phase2KeyboardHost();h.onTypingKeyPressed("space");
   check(h.imeTelemetry.events.size()==1&&h.imeTelemetry.events.get(0).getString("type").equals("key_outcome"),"programmatic Space stays distinct");
   h=new Phase2KeyboardHost();h.protectedInputField=true;h.pendingBopomofoKeyTouch=new BopomofoKeyTouch("space");h.onTypingKeyPressed("space");check(h.imeTelemetry.events.isEmpty(),"protected Space has no key content");
   System.out.println("PASS physical and programmatic Space, original commit result, protected field");
  }
 }
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
}'''.replace('SERVICE_METHODS','\n'.join(parts)).replace('TELEMETRY_METHODS','\n'.join(method(telemetry,n) for n in ['bopomofoKey','keyOutcome']))
(out/'Phase2KeyboardHost.java').write_text(source)
(out/'source-binding.json').write_text(json.dumps({n:hashlib.sha256(v.encode()).hexdigest() for n,v in [('service',service),('telemetry',telemetry),('harness',source)]},indent=2))
jdk=pathlib.Path(os.environ.get('JAVA_HOME','/home/simon/.local/jdk/jdk-17.0.2'))
json_jars=sorted(pathlib.Path('/home/simon/.gradle/caches').rglob('json-*.jar'));assert json_jars
with tempfile.TemporaryDirectory() as tmp:
 cp=str(json_jars[-1]);files=[out/'Phase2KeyboardHost.java',ROOT/'app/src/main/java/com/simon/voiceime/TouchModel.java',ROOT/'app/src/main/java/com/simon/voiceime/TouchModelShadow.java',ROOT/'app/src/main/java/com/simon/voiceime/ZhuyinInputController.java',ROOT/'scripts/v639_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java',ROOT/'app/src/phone/java/com/simon/voiceime/RimeZhuyinEngine.java',ROOT/'app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java',ROOT/'app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java']
 cp+=':/home/simon/android-sdk/platforms/android-34/android.jar'
 subprocess.run([str(jdk/'bin/javac'),'-encoding','UTF-8','-cp',cp,'-d',tmp,*map(str,files)],check=True)
 result=subprocess.run([str(jdk/'bin/java'),'-cp',tmp+':'+cp,'com.simon.voiceime.Phase2KeyboardHost',*(['space'] if a.space else [])]);raise SystemExit(result.returncode)
