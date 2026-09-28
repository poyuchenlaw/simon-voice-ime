#!/usr/bin/env python3
import json, math
from collections import defaultdict
from pathlib import Path
BASE=Path(__file__).resolve().parent.parent/'evidence/proto3/eval'
def read(p):return [json.loads(x) for x in p.read_text(encoding='utf-8').splitlines()]
def edit(a,b):
 prev=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  cur=[i]
  for j,y in enumerate(b,1):cur.append(min(cur[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
  prev=cur
 return prev[-1]
def han(s):return ''.join(c for c in s if '\u3400'<=c<='\u9fff')
cases=read(BASE/'cases.jsonl');corpus=read(BASE/'corpus_g2pw.jsonl');pred={}
for line in (BASE/'proto3_chewing_results.tsv').read_text(encoding='utf-8').splitlines():
 p=line.split('\t');pred[(p[0],p[1])]=p[2]
by=defaultdict(list)
for c in cases:by[c['id']].append(c)
scores={};selfcheck=0
for mode in ('A','B'):
 n=exact=chars=edits=0
 for row in corpus:
  parts=sorted(by[row['id']],key=lambda x:x['run']);got=[];want=[]
  for c in parts:got.append(pred[(f"{c['id']}:{c['run']}",mode)]);want.append(c['target'])
  ref=han(row['text']);n+=1;chars+=len(ref);edits+=edit(''.join(map(han,got)),ref)
  exact+=(''.join(got)==row['text'])
  if mode=='A' and ''.join(want)==ref:selfcheck+=1
 scores[mode]={'sentences':n,'exact':exact,'exact_rate':exact/n,'han_chars':chars,'edit_distance':edits,'char_accuracy':max(0,1-edits/chars)}
out={'sentences':len(corpus),'cases':len(cases),'ground_truth_selfcheck_exact':selfcheck==len(corpus),'A':scores['A'],'B':scores['B']}
(BASE/'proto3_scores.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(out,ensure_ascii=False,indent=2))
if selfcheck!=len(corpus):raise SystemExit('ground-truth scoring self-check failed')
