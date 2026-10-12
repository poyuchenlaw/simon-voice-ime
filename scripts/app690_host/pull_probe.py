#!/usr/bin/env python3
"""Stage 2 only: retrieve diagnostic data after the relay grants X1 to F."""
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--dest',type=pathlib.Path,required=True);p.add_argument('--lock',type=pathlib.Path,default=pathlib.Path('/home/simon/.claude/audit/ime_dual_hand_20261011/app690/X1_LOCK'));p.add_argument('--receipt',type=pathlib.Path,required=True);a=p.parse_args()
lock=a.lock.read_text().strip();receipt=a.receipt.read_text().strip()
assert lock==receipt and lock.split()[0]=='F','relay F lock and receipt must match; do not create your own lock'
a.dest.mkdir(parents=True,exist_ok=True)
r=subprocess.run(['adb','-s',a.serial,'pull','/sdcard/Android/data/com.simon.voiceime/files/app690F/',str(a.dest)],check=True)
assert any(a.dest.rglob('style-probe.json')),'pull finished without the required probe JSON'
print('RETRIEVED',a.dest)
