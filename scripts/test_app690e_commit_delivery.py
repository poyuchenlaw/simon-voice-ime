import pathlib,re,subprocess
w=pathlib.Path(__file__).resolve().parents[1];o=w/'out/app690e-delivery';o.mkdir(parents=True,exist_ok=True)
s=(w/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text();start=s.index('    private boolean commitZhuyinText(');end=s.index('    private boolean textLayoutSelected(',start)
helper=s[s.index('    void correctionSelfWrite('):s.index('    @Override public void onFinishInput()',s.index('    void correctionSelfWrite('))]
t=(w/'scripts/app690e_host/App690ECommitDeliveryHarness.java.in').read_text().replace('/* METHOD */',s[start:end]+helper);(o/'App690ECommitDeliveryHarness.java').write_text(t)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar']);p=w/'app/src/main/java/com/simon/voiceime'
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(o),*[str(p/(n+'.java')) for n in ['CorrectionCommitRecord','CorrectionRecords','DualHandTracks','DualHandMemory','CursorTapGuard']],str(o/'App690ECommitDeliveryHarness.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(o)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690ECommitDeliveryHarness'],cwd=w/'app').returncode)
