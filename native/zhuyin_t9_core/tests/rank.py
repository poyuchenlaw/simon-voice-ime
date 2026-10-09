"""Independent Level A oracle from positive-frequency single-character dictionary rows."""
from pathlib import Path
import sqlite3,collections,hashlib,subprocess
root=Path(__file__).resolve().parents[1]
groups=['ㄅㄆㄇㄈ','ㄉㄊㄋㄌ','ㄍㄎㄏ','ㄐㄑㄒ','ㄓㄔㄕㄖ','ㄗㄘㄙ','ㄧㄨㄩ','ㄚㄛㄜㄝ','ㄞㄟㄠㄡ','ㄢㄣㄤㄥㄦ']
order={c:i for i,c in enumerate(''.join(groups))}
freq=collections.Counter();original=set()
with sqlite3.connect('file:'+str(root.parents[1]/'app/src/phone/assets/zhuyin_initials.db')+'?mode=ro',uri=True) as db:
 for ch,reading,f in db.execute('select word,pronunciation,frequency from words where length(word)=1 and frequency>0'):
  original.add(ch);freq[''.join(c for c in reading if c not in 'ˊˇˋ˙ˉ ')]+=f
for line in (root/'rime_single_chars.tsv').read_text().splitlines()[1:]:
 ch,s,f=line.split('\t')
 if ch not in original:freq[s]+=int(f)
for line in (root/'generated/code_to_syllables.tsv').read_text().splitlines()[1:]:
 code,syllables,*_=line.split('\t'); actual=syllables.split()
 expected=sorted(actual,key=lambda s:(-freq[s],tuple(order[c] for c in s)))
 assert actual==expected,(code,actual[:3],expected[:3])
before={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'generated').iterdir()}
subprocess.run(['python3',str(root/'generate.py')],check=True,stdout=subprocess.DEVNULL)
after={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'generated').iterdir()}
assert before==after,'generation not hash stable'
print('Level A dictionary-frequency order and hash-stable generation PASS')
