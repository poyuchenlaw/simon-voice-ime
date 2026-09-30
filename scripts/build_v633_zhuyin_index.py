#!/usr/bin/env python3
"""Build phone-only initial-symbol index and personal phrase seed from upstream sources."""
import csv
import sqlite3
import sys
import subprocess
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/libchewing/data/dict/chewing/tsi.csv"
ASSETS = ROOT / "app/src/phone/assets"
DB = ASSETS / "zhuyin_initials.db"
SEED = ASSETS / "personal_seed.csv"
FAILED = ROOT / "evidence/v633/g2pw-unresolved.txt"
SYMBOLS = set("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ")
# Public-only sources.  Never add a server custom/user/clipboard list here.
# These are Simon/office terms that intentionally form the small bundled
# personal tier.  Judicial terms remain public and preserve length matching.
PERSONAL = {"陳柏諭", "廣信法律事務所"}
JUDICIAL = Path("/home/simon/whisper-to-input-proxy/judicial_glossary_data/judicial_terms_dict_37182.csv")
TONE_MARKS = {"1": "", "2": "ˊ", "3": "ˇ", "4": "ˋ", "5": "˙"}

def normalize_g2pw(syllables):
    out = []
    for syllable in syllables or []:
        if syllable and syllable[-1] in TONE_MARKS:
            syllable = syllable[:-1] + TONE_MARKS[syllable[-1]]
        out.append(syllable)
    return out

def initial_key(pronunciation):
    syllables = pronunciation.split()
    chars = []
    for syllable in syllables:
        first = next((ch for ch in syllable if ch in SYMBOLS), None)
        if first is None:
            return ""
        chars.append(first)
    return "".join(chars)

def is_simplified_only(value):
    try:
        from opencc import OpenCC
        return OpenCC("s2t").convert(value) != value
    except Exception:
        # Conservative fallback for common Simplified-only code points.  Build
        # reports this degraded detector rather than silently claiming certainty.
        return any(ch in "报诉讼审讼证据务权利义务" for ch in value)

def public_terms():
    terms = set(PERSONAL)
    protected = ASSETS / "correction/protected_terms.txt"
    if protected.is_file():
        terms.update(line.strip() for line in protected.read_text(encoding="utf-8").splitlines()
                     if line.strip() and not line.lstrip().startswith("#"))
    if JUDICIAL.is_file():
        with JUDICIAL.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.reader(stream):
                if row and row[0].strip(): terms.add(row[0].strip())
    clean = {term for term in terms if not is_simplified_only(term)}
    return clean, len(terms) - len(clean)

def main():
    if not SOURCE.is_file():
        raise SystemExit(f"missing upstream source: {SOURCE}")
    ASSETS.mkdir(parents=True, exist_ok=True)
    rows = {}
    with SOURCE.open(encoding="utf-8", newline="") as stream:
        for word, frequency, pronunciation in csv.reader(stream):
            if word.startswith("#") or not pronunciation or not any("\u3400" <= c <= "\u9fff" for c in word):
                continue
            key = initial_key(pronunciation)
            if key:
                ident = (key, word, pronunciation)
                rows[ident] = max(rows.get(ident, 0), int(frequency))
    terms, simplified_dropped = public_terms()
    missing = sorted(word for word in terms if word not in {r[1] for r in rows})
    unresolved = []
    seeds = []
    if missing:
        from pypinyin import lazy_pinyin, Style
        for word in missing:
            try:
                # pypinyin is phrase-aware and processes the legal corpus without
                # invoking a neural model once per term.  Explicit public-owner
                # readings already in tsi.csv retain their reviewed source value.
                syllables = lazy_pinyin(word, style=Style.BOPOMOFO, strict=False)
                pronunciation = " ".join(part.strip() for part in (syllables or []) if part and part.strip())
                key = initial_key(pronunciation)
                if key:
                    seeds.append((word, pronunciation))
                    rows[(key, word, pronunciation)] = 2**63 - 1
                else:
                    unresolved.append(word)
            except Exception as exc:
                unresolved.append(f"{word}\t{type(exc).__name__}: {exc}")
    temp = DB.with_suffix(".db.tmp")
    if temp.exists(): temp.unlink()
    with sqlite3.connect(temp) as conn:
        conn.execute("CREATE TABLE words(initial_key TEXT NOT NULL, word TEXT NOT NULL, pronunciation TEXT NOT NULL, frequency INTEGER NOT NULL, personal INTEGER NOT NULL DEFAULT 0)")
        conn.execute("CREATE TABLE syllables(spelling TEXT PRIMARY KEY)")
        conn.execute("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        source_commit = subprocess.check_output(["git", "-C", str(ROOT / "third_party/libchewing"), "rev-parse", "HEAD"], text=True).strip()
        version = hashlib.sha256(("\n".join(sorted(terms)) + "\n--personal--\n" + "\n".join(sorted(PERSONAL))).encode()).hexdigest()
        conn.executemany("INSERT INTO metadata VALUES (?,?)", (("source", str(SOURCE.relative_to(ROOT))), ("source_commit", source_commit), ("generator", "scripts/build_v633_zhuyin_index.py"), ("public_vocab_version", version), ("public_terms", str(len(terms))), ("simplified_dropped", str(simplified_dropped))))
        conn.executemany("INSERT INTO words VALUES (?,?,?,?,?)", ((k,w,p,f,int(w in PERSONAL)) for (k,w,p),f in rows.items()))
        syllables = sorted({"".join(ch for ch in syllable if ch in SYMBOLS) for _, _, pronunciation in rows
                            for syllable in pronunciation.split() if any(ch in SYMBOLS for ch in syllable)})
        conn.executemany("INSERT INTO syllables VALUES (?)", ((syllable,) for syllable in syllables))
        conn.execute("CREATE INDEX words_initial ON words(initial_key, personal DESC, frequency DESC)")
        conn.execute("CREATE INDEX words_word ON words(word, frequency DESC)")
        conn.commit()
    temp.replace(DB)
    seed_rows = sorted(set(seeds) | {("陳柏諭", "ㄔㄣˊ ㄅㄛˊ ㄩˋ")})
    with SEED.open("w", encoding="utf-8", newline="") as out:
        writer = csv.writer(out, lineterminator="\n"); writer.writerow(("word", "pronunciation")); writer.writerows(seed_rows)
    FAILED.parent.mkdir(parents=True, exist_ok=True)
    FAILED.write_text("\n".join(unresolved) + ("\n" if unresolved else ""), encoding="utf-8")
    print(f"public_terms={len(terms)} simplified_dropped={simplified_dropped} uncertain={len(unresolved)} version={version}")
    print(f"source={SOURCE} rows={sum(1 for _ in rows)} sqlite={DB} bytes={DB.stat().st_size}")
    print(f"seed={SEED} count={len(seed_rows)} g2pw_unresolved={len(unresolved)}")
    print(f"source_commit={source_commit}")

if __name__ == "__main__": main()
