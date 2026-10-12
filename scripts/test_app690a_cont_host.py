#!/usr/bin/env python3
"""Run actual popup/visibility methods at Android boundaries; not a GPU/performance test.

Baseline has no warmup or Surface callbacks, so its inherited callbacks do nothing.
No product method is replaced when present. L1 executes configureLayout's predicate
and layout dimensions, with literals derived from the F receipt and SDK contract.
"""
import argparse
import pathlib
import re
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--out', default='out/app690a-cont-host')
p.add_argument('--source-commit')
a = p.parse_args()
w = pathlib.Path(__file__).resolve().parents[1]
out = w / a.out
out.mkdir(parents=True, exist_ok=True)
path = 'app/src/phone/java/com/simon/voiceime/DualHandKeyboard.java'
k = subprocess.check_output(['git', 'show', a.source_commit+':'+path], cwd=w, text=True) if a.source_commit else (w/path).read_text()


def block(src, sig):
    start = src.index(sig)
    opening = src.index('{', start)
    depth = 0
    for m in re.finditer(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[{}]', src[opening:], re.S):
        if m.group() == '{':
            depth += 1
        elif m.group() == '}':
            depth -= 1
            if depth == 0:
                return src[start:opening+m.end()]
    raise ValueError(sig)


methods = '\n'.join(block(k, sig) for sig in [
    'private void feedback(', 'private void releaseCap(', 'private void cancelTouches(',
    'public void cursorAnchor(', 'public void close(', 'private String output(', 'private int dp('])
for name in ['createBubble', 'scheduleBubbleWarmup', 'stopBubbleWarmup', 'dismissBubble']:
    sig = 'private void '+name+'('
    if sig in k:
        methods += '\n'+block(k, sig)
fields = '\n'.join(m.group() for m in re.finditer(r'    private [^\n;]+\b(?:bubbleUsed|bubbleParked|bubbleWarmupIdle|bubbleWarmupContent|bubbleWarmupDraw)\b[^;]*;', k))
surface = '\n'.join(block(k, sig) for sig in [
    'protected void onLayout(', 'protected void onVisibilityChanged(',
    'protected void onWindowVisibilityChanged(', 'protected void onDetachedFromWindow('] if sig in k)
layout = block(k, 'private void configureLayout(')
predicate = layout[layout.index('boolean desired='):layout.index(';', layout.index('boolean desired='))+1]
palette = block(k, 'private void applyPalette(')
padding = palette[palette.index('root.setPadding('):palette.index(';', palette.index('root.setPadding('))+1]
top = layout[layout.index('topBar.setLayoutParams('):layout.index(';', layout.index('topBar.setLayoutParams('))+1]
row = layout[layout.index('rowDp=split?'):layout.index(';', layout.index('rowDp=split?'))+1]
insets = (w/'app/src/main/java/com/simon/voiceime/KeyboardInputLayout.java').read_text()
owned = (w/'app/src/main/java/com/simon/voiceime/KeyboardInsets.java').read_text()
parent_inset = block(insets, 'private void applyInsets(')
parent_owned = block(owned, 'public static boolean ownedByParent(').replace('ViewParent p', 'View p')
java = (w/'scripts/app690a_host/App690AContHarness.java.in').read_text()
for name, body in [('METHODS', methods), ('FIELDS', fields), ('SURFACE', surface),
                   ('PREDICATE', predicate), ('PADDING', padding), ('TOP', top),
                   ('ROW', row), ('PARENT_INSET', parent_inset), ('PARENT_OWNED', parent_owned)]:
    java = java.replace('/* '+name+' */', body)
(out/'App690AContHarness.java').write_text(java)
cp = ':'.join(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar', 'hamcrest-core-1.3.jar'])
prod = w/'app/src/main/java/com/simon/voiceime'
subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', cp, '-d', str(out),
                str(out/'App690AContHarness.java'), *[str(prod/(n+'.java')) for n in
                ['DualHandGesture', 'DualHandTable', 'DualHandVisuals']]], cwd=w, check=True)
raise SystemExit(subprocess.run(['java', '-ea', '-cp', str(out)+':'+cp,
                    'org.junit.runner.JUnitCore', 'com.simon.voiceime.App690AContHarness'], cwd=w).returncode)
