"""Reproducible APK-dictionary inventory; no network or hand-written syllable list."""
from pathlib import Path
import hashlib, json, sqlite3, struct, collections, math
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'app/src/phone/assets/zhuyin_initials.db'
OUT = Path(__file__).resolve().parent / 'generated'
RIME_TABLE = ROOT / 'app/src/phone/assets/rime/build/terra_pinyin.table.bin'
SUPPLEMENT = Path(__file__).resolve().parent / 'rime_single_chars.tsv'
GROUPS = ['ㄢㄣㄤㄥㄦ','ㄅㄆㄇㄈ','ㄉㄊㄋㄌ','ㄍㄎㄏ','ㄐㄑㄒ','ㄓㄔㄕㄖ','ㄗㄘㄙ','ㄧㄨㄩ','ㄚㄛㄜㄝ','ㄞㄟㄠㄡ']
KEYS = {c:str(k) for k,g in enumerate(GROUPS) for c in g}
INITIALS='ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ'
MEDIALS='ㄧㄨㄩ'; FINALS='ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ'; TONES='ˊˇˋ˙ˉ '
def structural(s):
    i=0
    if s and s[0] in INITIALS:i+=1
    if i<len(s) and s[i] in MEDIALS:i+=1
    if i<len(s) and s[i] in FINALS:i+=1
    return bool(s) and i==len(s) and (len(s)!=1 or s not in INITIALS or s in 'ㄓㄔㄕㄖㄗㄘㄙ')
def generate():
    OUT.mkdir(exist_ok=True)
    db=sqlite3.connect('file:'+str(SOURCE)+'?mode=ro',uri=True)
    by_syllable=collections.defaultdict(set); by_char=collections.defaultdict(dict); rejected=set(); frequencies=collections.Counter()
    for ch,reading,freq in db.execute('SELECT word,pronunciation,frequency FROM words WHERE length(word)=1 ORDER BY word,pronunciation'):
        s=''.join(c for c in reading if c not in TONES)
        reason='structurally_invalid' if not structural(s) else ('nonpositive_frequency' if freq<=0 else '')
        if reason: rejected.add((ch,reading,str(freq),reason));continue
        code=int(''.join(KEYS[c] for c in s))
        by_syllable[s].add(ch); frequencies[s]+=freq; by_char[ch][s]=max(freq,by_char[ch].get(s,0))
    # APK Rime table fills characters absent from the initial-key dictionary.
    original_chars=set(by_char)
    for line in SUPPLEMENT.read_text().splitlines()[1:]:
        ch,s,f=line.split('\t');freq=int(f)
        if ch in original_chars:continue
        if not structural(s) or freq<=0:
            rejected.add((ch,s,f,'rime_structurally_invalid' if not structural(s) else 'nonpositive_frequency'));continue
        code=int(''.join(KEYS[c] for c in s));by_syllable[s].add(ch);frequencies[s]+=freq;by_char[ch][s]=freq
    # Keep syllables table anomalies visible even if unattested in positive-frequency rows.
    for (s,) in db.execute('SELECT spelling FROM syllables ORDER BY spelling'):
        if s not in by_syllable:rejected.add(('',s,'','structurally_invalid' if not structural(s) else 'no_positive_single_character_attestation'))
    lines=['syllable\tcode\tn_chars']
    grouped=collections.defaultdict(list)
    for s,chars in sorted(by_syllable.items()):
        code=''.join(KEYS[c] for c in s); lines.append(f'{s}\t{code}\t{len(chars)}');grouped[code].append(s)
    (OUT/'legal_syllables.tsv').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    order={c:i for i,c in enumerate(INITIALS+MEDIALS+FINALS)}
    for v in grouped.values():v.sort(key=lambda s:(-frequencies[s],tuple(order[c] for c in s)))
    (OUT/'code_to_syllables.tsv').write_text('code\tsyllables\tscores\n'+''.join(k+'\t'+' '.join(v)+'\t'+' '.join(format(math.log1p(frequencies[s]),'.12f') for s in v)+'\n' for k,v in sorted(grouped.items())),encoding='utf-8')
    (OUT/'excluded_readings.tsv').write_text('char\treading\tfrequency\treason\n'+''.join('\t'.join(x)+'\n' for x in sorted(rejected)),encoding='utf-8')
    indices={s:i for i,s in enumerate(sorted(by_syllable))}
    data=bytearray(b'T9C2')
    for ch,readings in sorted(by_char.items()):
        ordered=sorted(readings,key=lambda s:(-readings[s],s))
        data+=struct.pack('<IB',ord(ch),len(ordered))
        for s in ordered:data+=struct.pack('<HI',indices[s],readings[s])
    (OUT/'char_readings.bin').write_bytes(data)
    manifest={'source':'app/src/phone/assets/zhuyin_initials.db','source_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'rime_table_sha256':hashlib.sha256(RIME_TABLE.read_bytes()).hexdigest(),'rime_extraction_sha256':hashlib.sha256(SUPPLEMENT.read_bytes()).hexdigest(),'extractor_sha256':hashlib.sha256((Path(__file__).resolve().parent/'extract_rime.py').read_bytes()).hexdigest(),'generator_version':3,'generator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),'syllables':len(by_syllable),'codes':len(grouped),'characters':len(by_char),'excluded_rows':len(rejected),'artifacts':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.name!='MANIFEST.json'}}
    (OUT/'MANIFEST.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(manifest,ensure_ascii=False))
if __name__=='__main__':generate()
