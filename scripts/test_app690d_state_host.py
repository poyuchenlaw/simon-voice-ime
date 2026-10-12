#!/usr/bin/env python3
"""Reuse A's OS-boundary fakes; run real D Shift/grid/candidate bodies and routed DOWN."""
import pathlib,subprocess,os
w=pathlib.Path(__file__).resolve().parents[1];src=pathlib.Path(os.environ.get('APP690D_SOURCE_ROOT',w));out=w/'out/app690d-state';out.mkdir(parents=True,exist_ok=True)
s=(src/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
def body(source,sig):
 start=source.index(sig);pos=source.index('{',start);depth=1;end=pos+1
 while depth:depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
t=(w/'scripts/app690a_host/App690ASharedHarness.java.in').read_text().replace('App690ASharedHarness','App690DStateHarness')
source=(src/'app/src/main/java/com/simon/voiceime/KeyboardTouchLayout.java').read_text()
start=source.index('            long dispatchStarted=');end=source.index('            boolean consumed = target.key.dispatchTouchEvent',start)
prefix=source[start:end].replace('target.key','target').replace('android.os.SystemClock.uptimeMillis()','0L')
t=t.replace('/* ROUTED_PRESS */','void dispatchPrefix(int action,View target){'+prefix+'}')
labels=(w/'app/src/main/java/com/simon/voiceime/KeyboardLabels.java').read_text();start=labels.index('    public static String labelClass(');end=labels.index('    public static String functionLabel(',start)
t=t.replace('/* CLASSIFY */','static class KeyboardLabels {'+labels[start:end]+'}')
for name,cls in [('FONT','KeyboardTypeface'),('HAPTICS','KeyboardHaptics'),('ROLES','KeyboardRoles'),('CANDIDATES','KeyboardCandidates')]:
 code=(w/'app/src/main/java/com/simon/voiceime'/(cls+'.java')).read_text();t=t.replace('/* '+name+' */','static class '+cls+' {'+code[code.index('{')+1:code.rfind('}')]+'}')
t=t.replace('String getText(){return "詞格";}','String value="詞格";String getText(){return value;}void setText(String text){value=text;}')
t=t.replace('int getChildCount(){return children.size();}', 'View findViewWithTag(String tag){for(View child:children)if(tag.equals(child.tag))return child;return null;}int getChildCount(){return children.size();}')
# Real Shift body, replacing only Android Service context/color boundary.
shift=body(s,'private void updateShiftUI()').replace('keyboardColor(R.color.key_text)','dColor("text")')
t=t.replace('/* CLASSIFY */','')
t=t[:-2]+'''\n Context context=new Context();ViewGroup englishKeyboard=new ViewGroup(context);boolean capsLock,shiftActive;int caseUpdates;
 void updateLetterCase(View v){caseUpdates++;}
 int dColor(String role){return DualHandVisuals.color(KeyboardRoles.dark(context),role);}
 '''+shift+'''
 static double lum(int color){double sum=0;double[] weights={.2126,.7152,.0722};for(int i=0;i<3;i++){double v=((color>>(16-8*i))&255)/255.;sum+=weights[i]*(v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));}return sum;}
 @Test public void a2_11_shiftThreeStatesBothThemes(){TextView shift=new TextView(context);shift.tag="key:shift";englishKeyboard.children.add(shift);
  for(boolean dark:new boolean[]{false,true})for(int state=0;state<3;state++){context.r.conf.uiMode=dark?32:16;capsLock=state==2;shiftActive=state>0;updateShiftUI();String expected=state==2?"accent":state==1?"hint":"text";assertEquals(DualHandVisuals.color(dark,expected),shift.fg);double a=lum(shift.fg),b=lum(DualHandVisuals.color(dark,"tools"));assertTrue((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5);assertEquals("⬆",shift.value);System.out.println("SHIFT "+dark+" "+state+" "+expected);}
  assertEquals(6,caseUpdates);
 }
 @Test public void a2_5_a2_10_gridSeparateContentKinds(){for(boolean dark:new boolean[]{false,true})for(boolean fixed:new boolean[]{false,true}){TextView v=new TextView(context);v.value=fixed?"★":"𪚥銀行";KeyboardCandidates.applyGrid(v,dark,fixed);assertEquals(18,v.sp,0);assertTrue(v.singleLine);assertEquals(fixed?"symbol":"candidate",v.getTag(R.id.keyboard_content_kind));assertEquals(DualHandVisuals.color(dark,"text"),v.fg);assertEquals(DualHandVisuals.color(dark,"tools"),v.color);if(!fixed)assertSame(Typeface.DEFAULT,v.face);}}
}
'''
# Old Shift literal colors need no invented expected role; the spec assertion is unchanged.
(out/'App690DStateHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])+':'+str(next((w/'out/gradle-home/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar')))
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(out/'App690DStateHarness.java'),str(w/'app/src/main/java/com/simon/voiceime/DualHandVisuals.java')],cwd=w,check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690DStateHarness'],cwd=w).returncode)
