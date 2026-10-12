#!/usr/bin/env python3
"""Run the existing baseline extraction, then R4's independent bottom geometry check."""
import argparse
import pathlib
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--out', required=True)
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
out = pathlib.Path(a.out).resolve()
subprocess.run(['python3', 'scripts/test_app688_host.py', '--out', str(out)], cwd=root, check=True)
cp = ':'.join([str(out), *(str(root / 'out/test-libs' / name) for name in ('junit-4.13.2.jar', 'hamcrest-core-1.3.jar')),
               str(next((root / 'out/gradle-home/caches/modules-2/files-2.1/org.json/json').glob('*/*/*.jar')))])
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', cp, '-d', str(out),
                str(root / 'scripts/app690a_host/App690ABottomLabelsTest.java')], cwd=root, check=True)
raise SystemExit(subprocess.run(['java', '-ea', '-cp', cp, 'org.junit.runner.JUnitCore',
                                'com.simon.voiceime.App690ABottomLabelsTest'], cwd=root).returncode)
