#!/usr/bin/env python3
"""SDK-only independent Layout host. Existing androidTest manifest stays intact."""
from pathlib import Path
import os,re,subprocess,zipfile
w=Path(__file__).resolve().parents[2];out=w/'out/app690F-host';out.mkdir(parents=True,exist_ok=True)
sdk=Path('/home/simon/android-sdk');bt=sdk/'build-tools/34.0.0';android=sdk/'platforms/android-34/android.jar';src=Path(__file__).parent/'host';classes=out/'classes';dex=out/'dex';classes.mkdir(exist_ok=True);dex.mkdir(exist_ok=True)
env=dict(os.environ,TMPDIR=str(w/'out/tmp'),JAVA_TOOL_OPTIONS='-Djava.io.tmpdir='+str(w/'out/tmp')+' -XX:-UsePerfData')
config=(w/'app/build.gradle').read_text();env['APP690F_KS_PASS']=re.search(r'storePassword "([^"]+)"',config)[1];env['APP690F_KEY_PASS']=re.search(r'keyPassword "([^"]+)"',config)[1];alias=re.search(r'keyAlias "([^"]+)"',config)[1]
def run(cmd):
 print('COMMAND:', ' '.join(map(str,cmd)),flush=True);r=subprocess.run(list(map(str,cmd)),env=env,cwd=w);print('EXIT='+str(r.returncode),flush=True);r.check_returncode()
run(['javac','-encoding','UTF-8','-source','17','-target','17','-cp',android,'-d',classes,*src.glob('*.java'),w/'app/src/androidTest/java/com/simon/voiceime/DualHand689SandboxActivity.java'])
run([bt/'d8','--lib',android,'--min-api','26','--output',dex,*classes.rglob('*.class')])
run([bt/'aapt2','link','-I',android,'--manifest',src/'AndroidManifest.xml','-o',out/'resources.apk'])
with zipfile.ZipFile(out/'resources.apk') as original,zipfile.ZipFile(out/'unsigned.apk','w') as output:
 for item in original.infolist():output.writestr(item,original.read(item.filename))
 output.writestr('classes.dex',(dex/'classes.dex').read_bytes())
run([bt/'zipalign','-f','4',out/'unsigned.apk',out/'aligned.apk'])
run([bt/'apksigner','sign','--ks',w/'release.keystore','--ks-pass','env:APP690F_KS_PASS','--key-pass','env:APP690F_KEY_PASS','--ks-key-alias',alias,'--out',out/'app690F-layout-host-diagF.apk',out/'aligned.apk'])
run([bt/'apksigner','verify','--print-certs',out/'app690F-layout-host-diagF.apk'])
print('CWD='+str(w));print('HOST_APK='+str(out/'app690F-layout-host-diagF.apk'))
