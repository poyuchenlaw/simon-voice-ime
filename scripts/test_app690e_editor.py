import pathlib,re,subprocess
w=pathlib.Path(__file__).resolve().parents[1];o=w/'out/app690e-editor';o.mkdir(parents=True,exist_ok=True);s=(w/'app/src/main/java/com/simon/voiceime/CorrectionEditor.java').read_text()
def block(sig):
 start=s.index(sig);opening=s.index('{',start);depth=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',s[opening:],re.S):
  if m.group()=='{':depth+=1
  elif m.group()=='}':
   depth-=1
   if depth==0:return s[start:opening+m.end()]
 raise ValueError(sig)
names=['protected static final class Snapshot','protected Snapshot snapshot(','protected void guardPage(','protected void forgetPositions(','protected void validate(','protected void rebase(','public void committed(Page ','protected void validateBeforeCommit(','public void selection(Page ','protected List<DualHandTracks.Choice> choices(','protected void replaceCommitted(','protected void remember(']
t=(w/'scripts/app690e_host/App690EEditorHarness.java.in').read_text().replace('/* METHODS */','\n'.join(block(n) for n in names));(o/'App690EEditorHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar']);p=w/'app/src/main/java/com/simon/voiceime'
files=[p/(n+'.java') for n in ['DualHandTracks','DualHandMemory','DualHandState','DualHandGesture','DualHandTable','CorrectionRecords','CorrectionCounters']]
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(o),*map(str,files),str(o/'App690EEditorHarness.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(o)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690EEditorHarness'],cwd=w/'app').returncode)
