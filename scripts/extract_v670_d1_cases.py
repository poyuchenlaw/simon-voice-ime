#!/usr/bin/env python3
"""Extract all initial + complete dictionary syllable key runs (10/2--5).
Inventory is independently dumped from the packaged prism's normal spellings.
Edits/choices/commits end an append-only run; their altered state cannot be
reconstructed from key telemetry alone. Their key runs are retained separately.
"""
import json,sys,hashlib
from pathlib import Path
inventory,output=map(Path,sys.argv[1:3])
physical='1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'
glyphs='ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ ˊˇˋ˙'
translate=dict(zip(physical,glyphs));valid={''.join(translate[c] for c in code) for code in inventory.read_text().splitlines()}
initials=set(glyphs[:21]);alphabet=set(glyphs);rows=[];runs=[];files=[]
for day in range(2,6):
 p=Path('/home/simon/ime-telemetry/data/2026-10-%02d.jsonl'%day);files.append({'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()});current=[];session=None
 def flush(reason):
  if current:runs.append((p.name,list(current),reason));current.clear()
 for line,text in enumerate(p.read_text().splitlines(),1):
  r=json.loads(text)
  if r.get('session_id')!=session:flush('session');session=r.get('session_id')
  t=r.get('type');key=r.get('key');key=' ' if key=='space' else key
  if t=='key' and r.get('page')=='bopomofo':
   if key in alphabet:current.append((line,key))
   else:flush('non-phonetic key')
  elif t in ('commit','exit') or t=='correction' and r.get('via') in ('backspace','candidate') or t=='cursor' and r.get('action') in ('edit','choose'):
   flush('mutation:'+t)
 flush('eof')
for source,events,end_reason in runs:
 keys=''.join(k for _,k in events)
 for i,k in enumerate(keys):
  if k not in initials:continue
  matches=[keys[i:i+n] for n in (2,3) if keys[i:i+n] in valid]
  if not matches:continue
  syllable=max(matches,key=len);end=i+len(syllable)
  # Include the actual tone if present, without inventing one.
  if end<len(keys) and keys[end] in ' ˊˇˋ˙':end+=1;syllable=keys[i:end]
  rows.append({'source':source,'line':events[i][0],'keys':keys,'start':i,'end':end,'syllable':syllable,'run_end':end_reason})
output.write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
summary={'cases':len(rows),'runs':len(runs),'toneless':sum(not r['syllable'].endswith(tuple(' ˊˇˋ˙')) for r in rows),'files':files,'inventory_sha256':hashlib.sha256(inventory.read_bytes()).hexdigest(),'boundary':'all detected contiguous key runs; cursor choice/edit state is not inferred'}
output.with_suffix('.summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in summary.items() if k!='files'},ensure_ascii=False))
