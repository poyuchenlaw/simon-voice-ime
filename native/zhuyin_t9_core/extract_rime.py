"""Recover attested single-character readings from the exact bundled Rime table."""
from pathlib import Path
import os,subprocess,shutil,re,math,hashlib,json
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent
HOST=Path(os.environ.get('RIME_HOST_PREFIX','/home/simon/simon-voice-ime/evidence/rime_spike/host-install-octagram'))
STAGE=ROOT/'out/t9-rime-extract';STAGE.mkdir(parents=True,exist_ok=True)
source=ROOT/'app/src/phone/assets/rime/build/terra_pinyin.table.bin';table=STAGE/source.name;shutil.copy2(source,table)
subprocess.run([str(HOST/'bin/rime_table_decompiler'),str(table)],env={**os.environ,'LD_LIBRARY_PATH':str(HOST/'lib')},check=True,capture_output=True)
# Official rime-bopomofo spelling algebra, in precisely its published order.
rules=[(r'^m(\d)$',r'mu\1'),(r'^r5$',r'er5'),('iu','iou'),('ui','uei'),('ong','ung'),(r'^yi?','i'),(r'^wu?','u'),('iu','v'),(r'^([jqx])u',r'\1v'),(r'([iuv])n',r'\1en'),(r'^zhi?','Z'),(r'^chi?','C'),(r'^shi?','S'),(r'^([zcsr])i',r'\1'),('ai','A'),('ei','I'),('ao','O'),('ou','U'),('ang','K'),('eng','G'),('an','M'),('en','N'),('er','R'),('eh','E'),(r'([iv])e',r'\1E')]
tr=str.maketrans('bpmfdtnlgkhjqxZCSrzcsiuvaoeEAIOUMNKGR','ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ')
rows=set()
for line in table.with_name('terra_pinyin.dict.yaml').read_text().splitlines():
 p=line.split('\t')
 if len(p)<3 or len(p[0])!=1:continue
 spelling=p[1]
 for pattern,replacement in rules:spelling=re.sub(pattern,replacement,spelling)
 spelling=re.sub('[1-5]$','',spelling).translate(tr)
 weight=float(p[2]);freq=max(1,round(weight)) if weight>0 else 0
 rows.add((p[0],spelling,freq))
output='char\treading\tfrequency\n'+''.join(f'{c}\t{s}\t{f}\n' for c,s,f in sorted(rows))
(HERE/'rime_single_chars.tsv').write_text(output)
print(json.dumps({'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'extractor_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'rows':len(rows),'output_sha256':hashlib.sha256(output.encode()).hexdigest()}))
