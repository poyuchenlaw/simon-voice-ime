from pathlib import Path
import os,sys,subprocess,time,json,hashlib
root=Path('/home/simon/x1_test_farm/v671_z12');env={**os.environ,'HOME':str(root),'TMPDIR':str(root/'tmp'),'ANDROID_ADB_SERVER_PORT':'5041','ANDROID_USER_HOME':str(root/'android')}
a=['/home/simon/android-sdk/platform-tools/adb','-P','5041','-s','emulator-5860']
def run(args,check=True,timeout=90):
 r=subprocess.run(args,env=env,capture_output=True,timeout=timeout);out=r.stdout.decode(errors='replace')+r.stderr.decode(errors='replace')
 if check and r.returncode:raise RuntimeError(str(args)+': '+out)
 return out
app=root/sys.argv[1];observer=root/os.environ.get('Z12_OBSERVER','r2-observer.apk');label=sys.argv[2];methods=sys.argv[3:]
sha=hashlib.sha256(app.read_bytes()).hexdigest();dest=root/label;dest.mkdir(exist_ok=True)
for package in ['com.simon.voiceime.test','com.simon.voiceime']:run(a+['uninstall',package],False)
for apk in [app,observer,root/'testpad.apk']: (dest/(apk.name+'.install.log')).write_text(run(a+['install','--no-streaming','-r',str(apk)]))
for cmd in ['am wait-for-broadcast-idle','settings put system font_scale 1.15','pm grant com.simon.voiceime android.permission.RECORD_AUDIO','pm grant com.simon.voiceime android.permission.POST_NOTIFICATIONS','cmd uimode night no']:
 run(a+['shell',cmd],False)
time.sleep(10)
path=run(a+['shell','pm path com.simon.voiceime']).strip().split('package:')[-1];actual=run(a+['shell','sha256sum '+path]).split()[0];assert actual==sha
rows=[]
for method in methods:
 d=dest/method;d.mkdir(exist_ok=True);run(a+['shell','am force-stop com.simon.voiceime']);run(a+['shell','rm -rf /data/data/com.simon.voiceime/files/v666']);run(a+['logcat','-c'])
 (d/'binding.json').write_text(json.dumps({'apk_sha256':sha,'installed_apk_sha256':actual,'observer_sha256':hashlib.sha256(observer.read_bytes()).hexdigest(),'serial':'emulator-5860','adb_port':5041,'method':method},indent=2))
 started=time.monotonic();out=run(a+['shell','am','instrument','-w','-r','-e','injector','direct','-e','class',(method if '#' in method else 'com.simon.voiceime.Z12AndroidTest#'+method),'com.simon.voiceime.test/androidx.test.runner.AndroidJUnitRunner'],False,300)
 (d/'instrumentation.log').write_text(out);run(a+['pull','/data/data/com.simon.voiceime/files/v666',str(d/'capture')],False)
 (d/'logcat.txt').write_text(run(a+['logcat','-d'],False));(d/'ime-diagnostics.jsonl').write_text(run(a+['shell','cat /data/data/com.simon.voiceime/files/ime-diagnostics.jsonl'],False));screen=subprocess.run(a+['exec-out','screencap','-p'],env=env,capture_output=True);(d/'last-screen.png').write_bytes(screen.stdout)
 row={'method':method,'status':'PASS' if 'OK (1 test)' in out else 'FAIL','seconds':round(time.monotonic()-started,2),'apk_sha256':sha};rows.append(row);(dest/'receipt.json').write_text(json.dumps(rows,indent=2));print(json.dumps(row),flush=True)

if any(row["status"]!="PASS" for row in rows):sys.exit(1)
