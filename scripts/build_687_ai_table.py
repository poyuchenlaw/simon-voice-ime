#!/usr/bin/env python3
"""6.87 §1.2: locked W1F3b projection, with the shipped W1D blank-cell fallback."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
source = Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(source).hexdigest() == '991ff50bc1ca7a45c2410001902f5c64420bee9ed047000de625175409ad3be8', 'W1F3b source changed'
previous = subprocess.check_output(['git', 'show', 'e14d44e:app/src/phone/assets/dual_hand/draft_chars_ai.tsv'], cwd=root)
assert hashlib.sha256(previous).hexdigest() == '5c1f6f3386a6317fffa96259feff0798566d737c0ba6ced5f7efe654c2643d49', 'W1D source changed'
fallback = dict(row.split('\t') for row in previous.decode().splitlines()[1:])
rows = [row.split('\t') for row in source.decode().splitlines()[1:]]
assert len(rows) == len({r[0] for r in rows}) == 415
assert sum(not r[1] for r in rows) == 34
assert all(r[1] or 'NO_QUALIFIED' in r for r in rows)
assets = root / 'app/src/phone/assets/dual_hand'
text = ['syllable\tchar']
provenance = ['syllable\tchar\tsource']
for row in rows:
    sound, char = row[:2]
    origin = 'W1F3b' if char else 'W1D_FALLBACK'
    char = char or fallback[sound]
    assert len(char) == 1
    text.append(f'{sound}\t{char}')
    provenance.append(f'{sound}\t{char}\t{origin}')
manifest = json.loads((assets / 'MANIFEST.json').read_text())
for name, data in [('draft_chars_ai.tsv', text), ('draft_chars_ai.provenance.tsv', provenance)]:
    content = ('\n'.join(data) + '\n').encode()
    (assets / name).write_bytes(content)
    sha = hashlib.sha256(content).hexdigest()
    manifest['artifacts'][name] = {'sha256': sha, 'bytes': len(content)}
    print(f'{sha}  {name}; rows=415')
manifest['ai_table_sources'] = {'W1F3b': hashlib.sha256(source).hexdigest(), 'W1D_FALLBACK': hashlib.sha256(previous).hexdigest(), 'fallback_commit': 'e14d44e', 'fallback_rows': 34}
(assets / 'MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
