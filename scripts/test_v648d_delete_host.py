#!/usr/bin/env python3
"""Run source-exact IME composition/delete callbacks with real host Rime.

Only Android UI/editor boundaries are doubled; controller and JNI are production.
The editor models replacement of the composing range, and finish removes only
its markers (never its text). Expected field values come from the work order.
"""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def method(text, name):
    match = re.search(r'private void ' + name + r'\s*\(', text)
    assert match, name
    opening = text.index('{', match.start())
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', text[opening:], re.S):
        if token.group() == '{':
            depth += 1
        elif token.group() == '}':
            depth -= 1
            if depth == 0:
                return text[match.start():opening + token.end()]
    raise AssertionError(name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', default='evidence/v648d/delete-host')
    parser.add_argument('--service-source', type=pathlib.Path)
    parser.add_argument('--assets', type=pathlib.Path, default=ROOT / 'app/src/phone/assets/rime')
    parser.add_argument('--cases', type=int, default=1000)
    args = parser.parse_args()
    out = ROOT / args.out
    out.mkdir(parents=True, exist_ok=True)
    service = (args.service_source or ROOT / 'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
    template = (ROOT / 'scripts/v648d_hostsrc/com/simon/voiceime/DeleteResidueHost.java.in').read_text()
    source = template.replace('SERVICE_METHODS', '\n'.join(method(service, n) for n in ['applyZhuyinState', 'setupBackspaceTouch']))
    java = out / 'DeleteResidueHost.java'
    java.write_text(source)
    # Reuse the existing word-index boundary stub; association lookup is unused.
    index = (ROOT / 'scripts/v639_hostsrc/com/simon/voiceime/ZhuyinWordIndex.java').read_text()
    index = index.replace('final class ZhuyinWordIndex {', 'final class ZhuyinWordIndex { List<String> continuations(String word){return Collections.emptyList();}')
    (out / 'ZhuyinWordIndex.java').write_text(index)
    stub = out / 'android/text/SpannableString.java'
    stub.parent.mkdir(parents=True, exist_ok=True)
    stub.write_text('''package android.text;
public class SpannableString implements CharSequence {
 private final String text; public SpannableString(String t){text=t;}
 public int length(){return text.length();} public char charAt(int i){return text.charAt(i);}
 public CharSequence subSequence(int s,int e){return text.subSequence(s,e);}
 public String toString(){return text;}
}''')
    jdk = pathlib.Path(os.environ.get('JAVA_HOME', '/home/simon/.local/jdk/jdk-17.0.2'))
    host = ROOT / 'evidence/rime_spike/host-install-octagram'
    sources = [java, out / 'ZhuyinWordIndex.java', stub,
               ROOT / 'scripts/v639_hostsrc/com/simon/voiceime/CompositionKeysHostTest.java',
               ROOT / 'app/src/main/java/com/simon/voiceime/ZhuyinInputController.java',
               ROOT / 'app/src/phone/java/com/simon/voiceime/RimeZhuyinEngine.java',
               ROOT / 'app/src/phone/java/com/simon/voiceime/ZhuyinKeyMap.java',
               ROOT / 'app/src/phone/java/com/simon/voiceime/RimeZhuyinNative.java']
    (out / 'source-binding.json').write_text(json.dumps({
        'service_sha256': hashlib.sha256(service.encode()).hexdigest(),
        'callbacks': {n: hashlib.sha256(method(service, n).encode()).hexdigest() for n in ['applyZhuyinState', 'setupBackspaceTouch']},
        'sources': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'assets': str(args.assets), 'seed': 648, 'cases': args.cases,
        'limitations': 'x86_64 Rime execution; Android editor/UI doubled; no device verification'
    }, indent=2) + '\n')
    classes, lib, user = out / 'classes', out / 'lib', out / 'user'
    for directory in [classes, lib, user]:
        directory.mkdir(exist_ok=True)
    subprocess.run([str(jdk / 'bin/javac'), '-cp', '/home/simon/android-sdk/platforms/android-34/android.jar',
                    '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([os.environ.get('CXX', 'g++'), '-std=c++17', '-O2', '-fPIC', '-shared',
                    '-I' + str(jdk / 'include'), '-I' + str(jdk / 'include/linux'),
                    '-I' + str(ROOT / 'evidence/rime_spike/android-prefix/include'),
                    str(ROOT / 'app/src/phone/cpp/rime_jni.cpp'), '-L' + str(host / 'lib'), '-lrime',
                    '-Wl,-rpath,' + str(host / 'lib'), '-o', str(lib / 'librime_jni.so')], check=True)
    env = dict(os.environ, LD_LIBRARY_PATH=str(host / 'lib') + ':' + str(lib))
    subprocess.run([str(jdk / 'bin/java'), '-Djava.library.path=' + str(lib) + ':' + str(host / 'lib'),
                    '-cp', str(classes), 'com.simon.voiceime.DeleteResidueHost',
                    str(args.assets), str(user), str(args.cases)], env=env, check=True)


if __name__ == '__main__':
    main()
