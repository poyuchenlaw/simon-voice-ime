#!/usr/bin/env python3
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--mutate-cache',action='store_true');a=p.parse_args()
w=pathlib.Path(__file__).resolve().parents[1];out=w/'out/app690a-fit';out.mkdir(parents=True,exist_ok=True)
subprocess.run(['python3','scripts/test_app688_host.py','--out',str(out)],check=True)
java=out/'App688Harness.java'
if a.mutate_cache:
 s=java.read_text();s=s.replace('if(!current.equals(last)||font!=face){','if(true){');java.write_text(s)
cp=':'.join([str(out),*(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar']),str(next((w/'out/gradle-home/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar')))])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(java),str(w/'scripts/app690a_host/App690AFitTest.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',cp,'org.junit.runner.JUnitCore','com.simon.voiceime.App690AFitTest']).returncode)
