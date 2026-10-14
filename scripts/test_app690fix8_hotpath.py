import pathlib,re,subprocess
w=pathlib.Path(__file__).resolve().parents[1];o=w/'out/app690fix8-hotpath';o.mkdir(parents=True,exist_ok=True);s=(w/'app/src/main/java/com/simon/voiceime/CorrectionEditor.java').read_text()
def block(sig):
 start=s.index(sig);opening=s.index('{',start);depth=0
 for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]',s[opening:],re.S):
  if m.group()=='{':depth+=1
  elif m.group()=='}':
   depth-=1
   if depth==0:return s[start:opening+m.end()]
 raise ValueError(sig)
names=['protected static final class Snapshot','protected Snapshot snapshot(','protected void guardPage(','protected void forgetPositions(','protected void validate(','protected void rebase(','public void committed(Page ','protected void validateBeforeCommit(','public void selection(Page ','protected List<DualHandTracks.Choice> choices(','protected void replaceCommitted(','protected void remember(']
template=(w/'scripts/app690e_host/App690EEditorHarness.java.in').read_text()
# Additional check derives from FIX8: an active composing range already forbids a capsule.
# Existing harness assertions stay byte-for-byte unchanged; blocked() counts preview reads.
assert template.count('boolean blocked(){return blocked;}')==1
template=template.replace('boolean blocked(){return blocked;}','int blockedReads;boolean blocked(){blockedReads++;return blocked;}')
extra=''' @Test public void activeCompositionCancelsWithoutReadingNativePreview(){markers=1;page.blocked=false;selection(page,2,2,0,3);ui.run();assertEquals("known composing range must skip the native preview query",0,page.blockedReads);assertEquals(0,markers);assertEquals(0,grids);}
 @Test public void nonComposingSelectionStillChecksPageBlocking(){markers=1;page.blocked=true;selection(page,2,2,-1,-1);ui.run();assertEquals(1,page.blockedReads);assertEquals(0,markers);assertEquals(0,grids);}
'''
pos=template.rfind('}');template=template[:pos]+extra+template[pos:]
t=template.replace('/* METHODS */','\n'.join(block(n) for n in names));(o/'App690EEditorHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar']);p=w/'app/src/main/java/com/simon/voiceime'
files=[p/(n+'.java') for n in ['DualHandTracks','DualHandMemory','DualHandState','DualHandGesture','DualHandTable','CorrectionRecords','CorrectionCounters']]
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(o),*map(str,files),str(o/'App690EEditorHarness.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(o)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690EEditorHarness'],cwd=w/'app').returncode)
