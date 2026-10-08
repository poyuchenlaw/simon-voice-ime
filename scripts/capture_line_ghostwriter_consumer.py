#!/usr/bin/env python3
"""Read-only evidence capture after a reviewed consumer operation; never verdicts."""
import argparse
import hashlib
import json
import pathlib
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb-port', type=int, required=True)
p.add_argument('--case', choices=['authorization', 'success', 'unavailable', 'search', 'chat-switch', 'draft-change', 'content-change'], required=True)
p.add_argument('--phase', choices=['before', 'after'], required=True)
p.add_argument('--out', type=pathlib.Path, required=True)
p.add_argument('--adb', default='/home/simon/android-sdk/platform-tools/adb')
a = p.parse_args()
dest = a.out / a.case / a.phase
dest.mkdir(parents=True, exist_ok=False)
adb = [a.adb, '-P', str(a.adb_port), '-s', a.serial]
def read(*args):
    return subprocess.run(adb + list(args), check=True, capture_output=True, timeout=30).stdout

# No chat text dumps: the controlled synthetic fixture screen is the UI evidence.
apk_path = read('shell', 'pm', 'path', 'com.simon.voiceime').decode().strip().splitlines()
assert len(apk_path) == 1 and apk_path[0].startswith('package:'), 'ambiguous or missing installed APK'
installed_sha = read('shell', 'sha256sum', apk_path[0][8:]).decode().split()[0]
screen = read('exec-out', 'screencap', '-p')
assert screen.startswith(b'\x89PNG\r\n\x1a\n'), 'screenshot is not PNG'
(dest / 'screen.png').write_bytes(screen)
services = read('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services').decode().strip()
(dest / 'binding.json').write_text(json.dumps({'case': a.case, 'phase': a.phase,
    'serial': a.serial, 'adb_port': a.adb_port, 'installed_apk_sha256': installed_sha,
    'screen_sha256': hashlib.sha256(screen).hexdigest(),
    'enabled_accessibility_services': services, 'verdict': 'CAPTURE_ONLY_UNVERIFIED'}, indent=2))
print(dest)
