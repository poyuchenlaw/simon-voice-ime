"""Generate SPEC_dual_hand_v1 §2/§3/§5 offline assets from bundled dictionaries."""
import argparse
import collections
import csv
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import sys

import numpy as np
import scipy
from scipy.optimize import Bounds, LinearConstraint, milp
from scipy.sparse import coo_matrix

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'native/zhuyin_t9_core'
ASSETS = ROOT / 'app/src/phone/assets'
OUT = ASSETS / 'dual_hand'
INITIALS = 'ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ'
MEDIALS = 'ㄧㄨㄩ'
FINALS = 'ㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ'
TONES = 'ˊˇˋ˙ˉ '
STATES = [*INITIALS, 'Ø', *MEDIALS]
GROUPS = dict(A='ㄚ', B='ㄛㄜㄝ', C='ㄞㄟ', D='ㄠㄡ', E='ㄢㄣ', F='ㄤㄥ', Ø='')
DIRS = ['tap', 'left', 'up', 'right', 'down']
LABELS = dict(zip(DIRS, ['點', '←', '↑', '→', '↓']))
LEFT = [list('ㄅㄇㄆ') + ['','ㄈ'], list('ㄉㄋㄊ') + ['','ㄌ'],
        ['ㄍ','ㄏ','ㄎ','ㄨ','Ø'], ['ㄐ','ㄒ','ㄑ','ㄧ','ㄩ'],
        list('ㄓㄕㄔ') + ['','ㄖ'], list('ㄗㄙㄘ') + ['','']]
HARD = {('ㄉ','C','right'): 'ㄉㄨㄟ', ('Ø','D','down'): 'ㄧㄡ',
        ('ㄍ','F','up'): 'ㄍㄨㄥ', ('ㄍ','F','right'): 'ㄍㄨㄤ',
        ('ㄒ','E','left'): 'ㄒㄧㄣ'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rows(path):
    with path.open(encoding='utf-8', newline='') as f:
        return list(csv.DictReader(f, delimiter='\t'))


def write_tsv(path, header, data):
    with path.open('w', encoding='utf-8', newline='') as f:
        w = csv.writer(f, delimiter='\t', lineterminator='\n')
        w.writerow(header)
        w.writerows(data)


def structural(s):
    i = 0
    if s and s[0] in INITIALS:
        i += 1
    if i < len(s) and s[i] in MEDIALS:
        i += 1
    if i < len(s) and s[i] in FINALS:
        i += 1
    return bool(s) and i == len(s) and (len(s) != 1 or s not in INITIALS or s in 'ㄓㄔㄕㄖㄗㄘㄙ')


def rime(s):
    return s[1:] if s[0] in INITIALS else s


def natural_state(s):
    return s[0] if s[0] in INITIALS + MEDIALS else 'Ø'


def key_for(s):
    return next((k for k, bases in GROUPS.items() if s[-1] in bases), 'Ø')


def load_sources():
    sources = [ASSETS / 'zhuyin_initials.db', CORE / 'rime_single_chars.tsv',
               CORE / 'generated/legal_syllables.tsv', CORE / 'generated/code_to_syllables.tsv',
               CORE / 'generated/MANIFEST.json', CORE / 'generate.py', CORE / 'extract_rime.py',
               ASSETS / 'rime/build/terra_pinyin.table.bin']
    hashes = {str(p.relative_to(ROOT)): digest(p) for p in sources}
    manifest = json.loads(sources[4].read_text())
    for field, source in [('source_sha256', sources[0]), ('rime_extraction_sha256', sources[1]),
                          ('generator_sha256', sources[5]), ('extractor_sha256', sources[6]),
                          ('rime_table_sha256', sources[7])]:
        assert digest(source) == manifest[field], field
    for source in sources[2:4]:
        assert digest(source) == manifest['artifacts'][source.name]
    db = sqlite3.connect(sources[0].as_uri() + '?mode=ro', uri=True)
    chars = collections.defaultdict(collections.Counter)
    original = set()
    for ch, reading, freq in db.execute('SELECT word,pronunciation,frequency FROM words WHERE length(word)=1 ORDER BY word,pronunciation'):
        s = ''.join(c for c in reading if c not in TONES)
        if structural(s) and freq > 0:
            original.add(ch)
            chars[s][ch] += freq
    for row in rows(sources[1]):
        ch, s, freq = row['char'], row['reading'], int(row['frequency'])
        if ch not in original and structural(s) and freq > 0:
            chars[s][ch] += freq
    legal = {r['syllable']: int(r['n_chars']) for r in rows(sources[2])}
    assert {s: len(c) for s, c in chars.items()} == legal
    assert len(legal) == 415
    freq = {s: sum(c.values()) for s, c in chars.items()}
    for row in rows(sources[3]):
        for s, score in zip(row['syllables'].split(), row['scores'].split(), strict=True):
            assert abs(math.log1p(freq[s]) - float(score)) < 1e-9
    words = collections.defaultdict(collections.Counter)
    inventory = dict(db.execute('SELECT length(word),count(*) FROM words GROUP BY length(word)'))
    rejected = collections.Counter()
    accepted = 0
    for word, reading, f in db.execute('SELECT word,pronunciation,frequency FROM words WHERE length(word) BETWEEN 2 AND 4 ORDER BY word,pronunciation'):
        seq = tuple(''.join(c for c in s if c not in TONES) for s in reading.split())
        if f <= 0:
            rejected['nonpositive_frequency'] += 1
        elif len(seq) != len(word) or any(s not in legal for s in seq):
            rejected['invalid_or_unattested_sequence'] += 1
        else:
            words[seq][word] += f
            accepted += 1
    db.close()
    return sources, hashes, legal, chars, freq, words, dict(inventory=inventory, accepted_rows=accepted, rejected=dict(rejected))


def prepare(legal, freq, hard=HARD):
    cutoff = sorted(freq.values())[int((len(freq) - 1) * .1)]
    fixed, free, omitted = {}, {}, []
    for state in STATES:
        for key, bases in GROUPS.items():
            prefix = '' if state == 'Ø' else state
            candidates = sorted(s for s in legal if key_for(s) == key and
                                (natural_state(s) == state or (state == 'Ø' and s[0] not in INITIALS)))
            slots = {}
            if state in MEDIALS:
                choices = {'tap': prefix} if key == 'Ø' else {'tap': prefix + bases[0]}
                if len(bases) > 1:
                    choices['left'] = prefix + bases[1]
                if key == 'B':
                    choices = {'tap': prefix + ('ㄛ' if state == 'ㄨ' else 'ㄝ')}
                    if state == 'ㄧ':
                        choices['left'] = 'ㄧㄛ'
            elif key == 'Ø':
                choices = {'tap': prefix, 'left': 'ㄦ' if state == 'Ø' else '',
                           **{d: prefix + m for d, m in zip(DIRS[2:], MEDIALS)}}
            else:
                choices = {'tap': prefix + bases[0],
                           **{d: prefix + m + bases[0] for d, m in zip(DIRS[2:], MEDIALS)}}
                if len(bases) > 1:
                    choices['left'] = prefix + bases[1]
                if key == 'B':
                    opens = sorted((prefix + b for b in bases if prefix + b in legal), key=lambda s: (-freq[s], s))
                    choices.pop('tap', None)
                    choices.pop('left', None)
                    choices.update(zip(DIRS[:2], opens[:2]))
            slots.update({d: s for d, s in choices.items() if s in legal})
            for (hs, hk, d), s in hard.items():
                if (state, key) == (hs, hk):
                    assert d not in slots or slots[d] == s, (state, key, d, slots[d], s)
                    slots[d] = s
            if state in 'ㄐㄑㄒ' and candidates and 'tap' not in slots:
                slots['tap'] = min(candidates, key=lambda s: (-freq[s], s))
            if state in INITIALS:
                excess = max(0, len(candidates) - 5)
                rare = sorted((s for s in candidates if freq[s] <= cutoff), key=lambda s: (freq[s], s))
                assert len(rare) >= excess, ('capacity_conflict', state, key, candidates)
                dropped = rare[:excess]
                assert not set(dropped) & set(hard.values()), ('rare_hard_constraint_conflict', state, key)
                # §3.3-5 permits omitting an otherwise legal anchor in an overfull cell.
                slots = {d:s for d,s in slots.items() if s not in dropped}
                omitted.extend(dropped)
                remaining = [s for s in candidates if s not in slots.values() and s not in dropped]
            elif state == 'Ø':
                # Medial-start states cover alternatives; only bare missing rimes need this state.
                remaining = [s for s in candidates if s[0] not in MEDIALS and s not in slots.values()]
            else:
                remaining = [s for s in candidates if s not in slots.values()]
                assert not remaining, ('start_state_conflict', state, key, remaining)
            holes = [d for d in DIRS if d not in slots]
            assert len(remaining) <= len(holes), ('anchor_capacity_conflict', state, key, slots, remaining)
            fixed.update({(state, key, d): s for d, s in slots.items()})
            if remaining:
                free[state, key] = (remaining, holes)
    assert set(omitted) == {'ㄋㄨㄣ', 'ㄌㄩㄢ'}
    return fixed, free, omitted, cutoff


def optimize(fixed, free, freq):
    # Each syllable contributes its frequency once, split equally among its actual paths.
    paths = collections.Counter(fixed.values())
    paths.update(s for ss, _ in free.values() for s in ss)
    scale = math.lcm(*paths.values())
    weights = {s: freq[s] * scale // n for s, n in paths.items()}
    finals = sorted({rime(s) for s in paths})
    xkeys = [(state, key, d, s) for (state, key), (ss, ds) in free.items() for s in ss for d in ds]
    ykeys = [(r, d) for r in finals for d in DIRS]
    x = {k: i for i, k in enumerate(xkeys)}
    y = {k: len(x) + i for i, k in enumerate(ykeys)}
    z = {k: len(x) + len(y) + i for i, k in enumerate(xkeys)}
    n = len(x) + len(y) + len(z)
    objective = np.zeros(n)
    for (_, _, d), s in fixed.items():
        objective[y[rime(s), d]] -= weights[s]
    for k in xkeys:
        objective[z[k]] = -weights[k[3]]
    constraints, lower, upper = [], [], []

    def add(coefs, lo, hi):
        constraints.append(coefs)
        lower.append(lo)
        upper.append(hi)

    for (state, key), (ss, ds) in free.items():
        for s in ss:
            add({x[state, key, d, s]: 1 for d in ds}, 1, 1)
        for d in ds:
            add({x[state, key, d, s]: 1 for s in ss}, 0, 1)
    for r in finals:
        add({y[r, d]: 1 for d in DIRS}, 1, 1)
    for k in xkeys:
        add({z[k]: 1, x[k]: -1}, -np.inf, 0)
        add({z[k]: 1, y[rime(k[3]), k[2]]: -1}, -np.inf, 0)
        add({z[k]: 1, x[k]: -1, y[rime(k[3]), k[2]]: -1}, -1, np.inf)
    rr, cc, vv = [], [], []
    for i, cs in enumerate(constraints):
        for j, v in cs.items():
            rr.append(i); cc.append(j); vv.append(v)
    matrix = coo_matrix((vv, (rr, cc)), shape=(len(constraints), n)).tocsc()
    result = milp(objective, integrality=np.ones(n), bounds=Bounds(0, 1),
                  constraints=LinearConstraint(matrix, lower, upper), options={'mip_rel_gap': 0})
    assert result.status == 0 and result.mip_gap == 0, (result.status, result.message)
    mapping = dict(fixed)
    for k, i in x.items():
        if result.x[i] > .5:
            mapping[k[:3]] = k[3]
    stats = dict(solver='scipy.optimize.milp/HiGHS', scipy=scipy.__version__,
                 status=result.status, mip_gap=result.mip_gap, variables=n,
                 constraints=len(constraints), objective_scaled=round(-result.fun), weight_scale=scale)
    return mapping, stats


def consistency(mapping, freq):
    counts = collections.Counter(mapping.values())
    weights = collections.defaultdict(collections.Counter)
    for (_, _, d), s in mapping.items():
        weights[rime(s)][d] += freq[s] / counts[s]
    data = []
    for r, ds in sorted(weights.items()):
        mode = min(ds, key=lambda d: (-ds[d], DIRS.index(d)))
        data.append([r or '空韻', len(ds), ' '.join(d for d in DIRS if d in ds), mode,
                     sum(ds.values()), ds[mode], ds[mode] / sum(ds.values()), *[ds[d] for d in DIRS]])
    total = sum(r[4] for r in data)
    return data, sum(r[5] for r in data) / total, total


def validate(mapping, legal, omitted, chars, words, cutoff, freq):
    assert len(STATES) == 25 and len(set(mapping)) == len(mapping)
    assert set(mapping.values()) == set(legal) - set(omitted)
    assert all(freq[s] <= cutoff for s in omitted)
    for slot, s in mapping.items():
        state, key, _ = slot
        assert s in legal and key_for(s) == key
        assert (natural_state(s) == state or (state == 'Ø' and s[0] not in INITIALS))
    examples = {**HARD, ('ㄋ','Ø','up'): 'ㄋㄧ', ('ㄅ','Ø','right'): 'ㄅㄨ',
                ('ㄑ','Ø','down'): 'ㄑㄩ', ('ㄕ','Ø','tap'): 'ㄕ', ('ㄗ','Ø','tap'): 'ㄗ',
                ('Ø','Ø','left'): 'ㄦ', ('ㄧ','E','tap'): 'ㄧㄢ', ('ㄧ','E','left'): 'ㄧㄣ',
                ('ㄨ','E','tap'): 'ㄨㄢ', ('ㄨ','E','left'): 'ㄨㄣ',
                ('ㄩ','E','tap'): 'ㄩㄢ', ('ㄩ','E','left'): 'ㄩㄣ',
                ('Ø','E','tap'): 'ㄢ', ('Ø','E','left'): 'ㄣ', ('ㄨ','B','tap'): 'ㄨㄛ',
                ('ㄧ','D','tap'): 'ㄧㄠ', ('ㄩ','B','tap'): 'ㄩㄝ'}
    for slot, s in examples.items():
        assert mapping.get(slot) == s, (slot, s, mapping.get(slot))
    for state in 'ㄐㄑㄒ':
        for key in GROUPS:
            if any(st == state and k == key for st, k, _ in mapping):
                assert (state, key, 'tap') in mapping
        assert mapping[state,'Ø','tap'] == mapping[state,'Ø','up'] == state + 'ㄧ'
    assert '確認' in words['ㄑㄩㄝ','ㄖㄣ']
    clauses = sorted(words['ㄊㄧㄠ','ㄎㄨㄢ'].items(), key=lambda p: (-p[1], p[0]))
    clause_first = bool(clauses and clauses[0][0] == '條款')
    assert all(len(c) >= 1 for c in chars.values())
    return dict(coverage='PASS: 415 - 2 = 413', examples=f'PASS: {len(examples)} specified paths / 21 example characters',
                unique_slots='PASS', jqx_tap='PASS', confirmation='PASS: 確認 present',
                clauses_first='PASS' if clause_first else 'FAIL: source ranking retained', clauses=clauses)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--audit-dir', type=Path, required=True)
    args = parser.parse_args()
    audit = args.audit_dir.resolve()
    audit.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(exist_ok=True)
    sources, hashes, legal, chars, freq, words, word_stats = load_sources()
    fixed, free, omitted, cutoff = prepare(legal, freq)
    mapping, solver = optimize(fixed, free, freq)
    checks = validate(mapping, legal, omitted, chars, words, cutoff, freq)
    relaxed_fixed, relaxed_free, _, _ = prepare(legal, freq, hard={})
    relaxed, relaxed_solver = optimize(relaxed_fixed, relaxed_free, freq)
    assert set(relaxed.values()) == set(legal) - set(omitted)
    _, relaxed_ratio, _ = consistency(relaxed, freq)
    write_tsv(OUT / 'dual_hand_map.tsv', ['state','key','gesture','syllable'],
              ([s,k,d,mapping.get((s,k,d),'')] for s in STATES for k in GROUPS for d in DIRS))
    write_tsv(OUT / 'left_hand.tsv', ['key','gesture','state'],
              ([i,d,s] for i, states in enumerate(LEFT, 1) for d,s in zip(DIRS,states,strict=True)))
    ordered = {s: sorted(c, key=lambda ch: (-c[ch], ch)) for s,c in chars.items()}
    write_tsv(OUT / 'draft_chars.tsv', ['syllable','char'], ([s, ordered[s][0]] for s in sorted(chars)))
    write_tsv(OUT / 'grid_chars.tsv', ['syllable', *[f'char{i}' for i in range(1,10)]],
              ([s, *ordered[s][:9], *[''] * max(0, 9-len(ordered[s]))] for s in sorted(chars)))
    word_rows = [[ ' '.join(seq), word, f, rank] for seq in sorted(words)
                 for rank, (word,f) in enumerate(sorted(words[seq].items(), key=lambda p: (-p[1],p[0])),1)]
    write_tsv(OUT / 'phrase_words.tsv', ['syllables','word','frequency','rank'], word_rows)
    data, ratio, total = consistency(mapping, freq)
    assert abs(sum(r[5] for r in data) * solver['weight_scale'] - solver['objective_scaled']) < .01
    write_tsv(audit / 'consistency.tsv', ['rime','direction_count','directions','modal_direction','weight','modal_weight','ratio',*DIRS],data)
    left_lookup = {s:(str(i),d) for i, states in enumerate(LEFT,1) for d,s in zip(DIRS,states) if s}
    left_weight = collections.Counter()
    right_weight = collections.Counter()
    routes = []
    for s in sorted(set(mapping.values())):
        st = natural_state(s)
        k = key_for(s)
        d = next(d for d in DIRS if mapping.get((st,k,d)) == s)
        lk, ld = left_lookup[st]
        left_weight['tap' if ld == 'tap' else 'slide'] += freq[s]
        right_weight['tap' if d == 'tap' else 'slide'] += freq[s]
        routes.append([s,freq[s],lk,ld,st,k,d])
    write_tsv(audit / 'canonical_routes.tsv', ['syllable','frequency','left_key','left_gesture','state','right_key','right_gesture'], routes)
    metrics = dict(legal=415, reachable=413, omitted={s:freq[s] for s in omitted}, rare_cutoff=cutoff,
                   mapping_slots=875, populated_slots=len(mapping), consistency=ratio, weighted_total=total,
                   solver=solver, checks=checks, word_source='zhuyin_initials.db', word_inventory=word_stats,
                   without_four_examples_consistency=relaxed_ratio,
                   four_examples_cost_percentage_points=(relaxed_ratio-ratio)*100,
                   without_four_examples_solver=relaxed_solver,
                   phrase_rows=len(word_rows), phrase_sequences=len(words),
                   left=dict(left_weight), right=dict(right_weight),
                   left_tap=left_weight['tap']/sum(left_weight.values()),
                   right_tap=right_weight['tap']/sum(right_weight.values()))
    # Separate actual hand paths from right-direct alternatives in the consistency appendix.
    natural = {slot:s for slot,s in mapping.items() if slot[0] == natural_state(s)}
    _, metrics['natural_state_consistency'], _ = consistency(natural, freq)
    consonants = {slot:s for slot,s in mapping.items() if slot[0] in INITIALS}
    _, metrics['consonant_consistency'], _ = consistency(consonants, freq)
    lines = ['# 雙手滑動完整對照表', '', '手勢欄位：點、↑、←、↓、→；—＝空／不輸出。所有 25 狀態、7 鍵、5 手勢均列出。', '',
             '左手每鍵依序為點、↑、←、↓、→：', '', '|鍵|點|↑|←|↓|→|', '|---|---|---|---|---|---|']
    display_dirs = ['tap','up','left','down','right']
    for i, states in enumerate(LEFT,1):
        lookup = dict(zip(DIRS,states))
        lines.append(f'|{i}|'+'|'.join(lookup[d] or '—' for d in display_dirs)+'|')
    for state in STATES:
        lk, ld = left_lookup[state]
        lines += ['', f'## 狀態 {state}（左手 {lk}{LABELS[ld]}）', '', '|右手鍵|點|↑|←|↓|→|', '|---|---|---|---|---|---|']
        lines += [f'|{key}|'+'|'.join(mapping.get((state,key,d),'—') for d in display_dirs)+'|' for key in GROUPS]
    (audit / 'MAP_TABLE.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    metrics['source_hashes_unchanged'] = hashes == {str(p.relative_to(ROOT)):digest(p) for p in sources}
    assert metrics['source_hashes_unchanged']
    manifest = dict(generator_sha256=digest(Path(__file__)), python=sys.version.split()[0],
                    scipy=scipy.__version__, sources=hashes,
                    artifacts={p.name:dict(sha256=digest(p),bytes=p.stat().st_size) for p in sorted(OUT.glob('*.tsv'))})
    (OUT / 'MANIFEST.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    (audit / 'metrics.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2)+'\n')
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
