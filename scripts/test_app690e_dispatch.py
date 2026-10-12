import pathlib,re,subprocess,os,argparse
w=pathlib.Path(__file__).resolve().parents[1];o=w/'out/app690e-dispatch';o.mkdir(parents=True,exist_ok=True)
p=argparse.ArgumentParser();p.add_argument('--baseline');a=p.parse_args()
path='app/src/main/java/com/simon/voiceime/SimonIMEService.java'
s=subprocess.check_output(['git','show',a.baseline+':'+path],cwd=w,text=True) if a.baseline else (w/path).read_text();start=s.index('    @Override public void onUpdateCursorAnchorInfo(');end=s.index('    private void toggleDualHand(',start)
method=s[start:end].replace('android.view.inputmethod.CursorAnchorInfo','Object').replace('@Override ','')
t=(w/'scripts/app690e_host/App690EDispatch.java.in').read_text().replace('/* METHOD */',method)
(o/'App690EDispatch.java').write_text(t)
subprocess.run(['javac','-d',str(o),str(o/'App690EDispatch.java')],check=True)
raise SystemExit(subprocess.run(['java','-ea','-cp',str(o),'App690EDispatch']).returncode)
