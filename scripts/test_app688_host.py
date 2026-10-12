#!/usr/bin/env python3
"""Execute unchanged keyboard/editor method bodies with bounded Android boundary fakes.
Not a device/layout simulation; actual Android geometry remains an X1 check.
"""
import argparse, pathlib, re, subprocess, hashlib
p=argparse.ArgumentParser();p.add_argument('--out',required=True);p.add_argument('--clipboard-baseline');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=pathlib.Path(a.out).resolve();out.mkdir(parents=True,exist_ok=True)
prod=root/'app/src/main/java/com/simon/voiceime';phone=root/'app/src/phone/java/com/simon/voiceime'
def block(src,signature):
 start=src.index(signature);opening=src.index('{',start);depth=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',src[opening:],re.S):
  if m.group()=='{':depth+=1
  elif m.group()=='}':
   depth-=1
   if depth==0:return src[start:opening+m.end()]
 raise ValueError(signature)
k=(phone/'DualHandKeyboard.java').read_text();s=(prod/'SimonIMEService.java').read_text();e=(phone/'DualHandEditor.java').read_text()
methods='\n'.join(block(k,x) for x in ['private void renderLabels(', 'private void functionLabels(', 'private static String suffix(', 'private static String vertical(', 'private void function(', 'private LinearLayout half(', 'private TextView text(', 'private int dp(', 'private float fitLabelSize(', 'private int[] columnWidths(', 'private float keyContentWidth(', 'private String shortFunctionLabel(', 'private String output('])
methods+='\n'+re.search(r'    private static final float LEFT_WIDTH_FRACTION=.*?;',k).group()+re.search(r'    private static final float\[\] BOTTOM_WIDTH_WEIGHTS=.*?;',k).group()+re.search(r'    private static final String\[\] BOTTOM_KEYS=.*?;',k).group()
selection=block((prod/'CorrectionEditor.java').read_text(),'public void selection(Page ').replace('Page page','App688Harness page')
# Exercise precisely the production list selection/layout statements; adapter framework is Android-only.
clip=s[s.index('        List<String> history = clipboardHelper.getHistory();'):s.index('        ClipAdapter adapter =',s.index('private void showClipboardPanel('))]
clip+='\nreturn items;'
# Current and future label measurement implementations remain separate Android integration checks.
template=(root/'scripts/app688_host/App688Harness.java.in').read_text()
label=(phone/'DualHandLabelView.java').read_text()
label_methods='\n'.join(block(label,x) for x in ['static float[] extent(', 'static boolean fits(']).replace('Paint paint','android.graphics.Paint paint')
# Shared fitting code is extracted intact, with only Android boundary type substitution.
shared=(prod/'KeyboardLabels.java').read_text()
shared=shared[shared.index('{')+1:shared.rfind('}')].replace('Paint paint','android.graphics.Paint paint').replace('Typeface face','Object face')
label_methods+='} static class KeyboardLabels {'+shared+'} static class App690ExtractionBoundary {'
methods+='\n'+re.search(r'    private final KeyboardLabels.Cache fitCache=.*?;',k).group()

java=template.replace('/* LABEL */',label_methods).replace('/* KEYBOARD */',methods).replace('/* SELECTION */',selection).replace('/* CLIPLIST */',clip)
(out/'App688Harness.java').write_text(java)
helper=subprocess.check_output(['git','show',a.clipboard_baseline+':app/src/main/java/com/simon/voiceime/ClipboardHelper.java'],cwd=root,text=True) if a.clipboard_baseline else (prod/'ClipboardHelper.java').read_text();helper=re.sub(r'^import android[^;]+;\n','',helper,flags=re.M);(out/'ClipboardHelper.java').write_text(helper)
# Theme resolution is tested as production code, without invoking Android UI methods.
if (prod/'KeyboardTheme.java').exists():
 theme=(prod/'KeyboardTheme.java').read_text();theme=block(theme,'public static boolean isDark(')+'\n'+block(theme,'public static int color(')
 java=java.replace('/* THEME */',theme)
else:java=java.replace('/* THEME */','public static boolean isDark(String mode,boolean system){return system;} public static int color(String mode,boolean system,String role){return 0;}')
(out/'App688Harness.java').write_text(java)
(out/'extraction.sha256').write_text('\n'.join(hashlib.sha256(x.read_bytes()).hexdigest()+' '+str(x) for x in [phone/'DualHandKeyboard.java',phone/'DualHandEditor.java',prod/'ClipboardHelper.java',prod/'SimonIMEService.java'])+'\n')
cp=':'.join(str(root/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
json=next((root/'out/gradle-home/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar'));cp+=':'+str(json)
files=[out/'App688Harness.java',out/'ClipboardHelper.java',prod/'DualHandGesture.java',prod/'DualHandTable.java',prod/'DualHandState.java',prod/'DualHandVisuals.java']
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*map(str,files)],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(out)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App688Harness']).returncode)
