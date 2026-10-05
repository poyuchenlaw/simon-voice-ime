#!/usr/bin/env python3
"""WO_D1_R2 paired acceptance; retains strict-zero replay failures as known deficits."""
import json,re,sys,hashlib
from pathlib import Path
def parse(path):
 text=Path(path).read_text();summary=dict((k,int(v)) for k,v in re.findall(r'(cases|observations|split|unmapped|commit_leaks)=(\d+)',text.split('SUMMARY')[-1]));failed={'split':set(),'unmapped':set()}
 for n,s,u in re.findall(r'CASE (\d+) split=(true|false) unmapped=(true|false)',text):
  if s=='true':failed['split'].add(int(n))
  if u=='true':failed['unmapped'].add(int(n))
 return summary,failed
base,b=parse(sys.argv[1]);previous,p=parse(sys.argv[2]);actual,a=parse(sys.argv[3])
receipt={'baseline':base,'previous_enabled':previous,'actual':actual,'new_split_vs_baseline':sorted(a['split']-b['split']),'new_split_vs_previous':sorted(a['split']-p['split']),'new_unmapped_vs_baseline':sorted(a['unmapped']-b['unmapped']),'new_unmapped_vs_previous':sorted(a['unmapped']-p['unmapped']),'retained_split_cases':sorted(a['split']),'corpus_sha256':hashlib.sha256(Path(sys.argv[4]).read_bytes()).hexdigest(),'strict_zero_verdict':'FAIL (known residuals retained)'}
receipt['verdict']='PASS' if actual['cases']==2074 and actual['split']<=6 and actual['commit_leaks']==0 and all(not receipt[k] for k in receipt if k.startswith('new_')) else 'FAIL'
print(json.dumps(receipt,indent=2));raise SystemExit(0 if receipt['verdict']=='PASS' else 1)
