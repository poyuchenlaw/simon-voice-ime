import pathlib,subprocess,os
w=pathlib.Path(__file__).resolve().parents[1];o=w/'out/app690e-records';o.mkdir(parents=True,exist_ok=True)
cp=':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])
p=w/'app/src/main/java/com/simon/voiceime';names=['DualHandTracks','DualHandMemory','CorrectionRecords','CorrectionCommitRecord','CorrectionPosition','CorrectionCounters']
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(o),*[str(p/(n+'.java')) for n in names],str(w/'app/src/testPhone/java/com/simon/voiceime/App690ERecordsTest.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(o)+':'+cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690ERecordsTest'],cwd=w/'app').returncode)
