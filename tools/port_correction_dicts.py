#!/usr/bin/env python3
"""
Port server-side deterministic correction dictionaries into on-device APK assets.

Source of truth (READ-ONLY):
  /home/simon/whisper-to-input-proxy/paraformer_legal_hotwords.py  (PARAFORMER_LEGAL_CORRECTIONS)
  /home/simon/whisper-to-input-proxy/legal_dictionary.py            (WHISPER_LEGAL_CORRECTIONS,
                                                                      MAINLAND_TO_TAIWAN,
                                                                      STANDARD_LEGAL_CHARS,
                                                                      PROTECTED_LEGAL_TERMS)

Outputs (APK assets, tab-separated  wrong<TAB>correct  ; '#' line = comment/section):
  app/src/main/assets/correction/paraformer_legal.txt   (step 1, SIMPLIFIED, generic legal only)
  app/src/main/assets/correction/legal_dict_mishear.txt (step 4, mishear + CN->TW + standard chars)
  app/src/main/assets/correction/protected_terms.txt    (step 4, one term per line, never-touch)

§37 EXCLUSIONS (client / client-specific / proper-noun person & company names): every
excluded entry is logged to tools/port_correction_dicts_exclusions.log so the orchestrator
can audit exactly what was left out. When unsure, the entry is EXCLUDED.

This script is import-free of the server runtime: it parses the dicts via importlib so we get
the literal dict objects (verbatim), then filters.
"""
import importlib.util
import re
import sys
from pathlib import Path

SERVER = Path("/home/simon/whisper-to-input-proxy")
ASSETS = Path("/home/simon/simon-voice-ime/app/src/main/assets/correction")
EXCL_LOG = Path("/home/simon/simon-voice-ime/tools/port_correction_dicts_exclusions.log")

ASSETS.mkdir(parents=True, exist_ok=True)
excl_lines = []


def load(modname, path):
    spec = importlib.util.spec_from_file_location(modname, path)
    mod = importlib.util.module_from_spec(spec)
    # paraformer_legal_hotwords / legal_dictionary import only stdlib (re, logging, typing)
    spec.loader.exec_module(mod)
    return mod


# CJK ideograph detector (person/company names are all-CJK proper nouns)
CJK = re.compile(r'^[一-鿿]+$')

# ---------------------------------------------------------------------------
# §37: explicit client / party / firm-staff / proper-noun blocklist.
# These are the *correct* (RHS) forms of name corrections in the server dicts.
# Any rule whose target OR source contains one of these is dropped.
# Sourced from the server dicts' own "客戶/當事人/社交圖譜人名/本所人名" sections.
# ---------------------------------------------------------------------------
NAME_BLOCK_TARGETS = {
    # firm staff / partners / Simon himself (proper nouns -> exclude to be safe)
    "陈柏谕", "陳柏諭", "陳柏翰", "柏閔", "温柏閔", "芯宸", "吳得宏", "趙容",
    # active-case parties (client-specific)
    "吳勳忠", "蔡富紘", "劉昆山", "劉泳駐", "陳玫蓉", "劉吉仁", "陳鎮",
    "李沛欣", "鄭明岳", "王翊甄", "張政哲", "旭新科技",
    # other clients / parties
    "鄭秀娟", "陳笛豪", "陳小梅", "陳明輝",
    # peers / legal circle / contacts
    "楊凱雯", "黃華駿", "洪茂松", "王浩", "劉元好", "劉建國", "伍芳儀",
    "孫國昌", "宋美姬", "林冠宇", "林志維", "林雅婷", "許哲維", "謝小蓁",
    "趙中勇", "邱瑞銘", "阮瑞源", "盧尚政", "陳泰源", "陳柏年", "王威傑",
    "顏歆時", "黃柏雅", "黃小兔", "蔡岳儒", "吳昱鋒",
    # firm/company-specific proper nouns
    "廣信", "謙理", "辣杯杯", "棨兆", "東尚", "三本",
}

def is_name(key: str, val: str) -> bool:
    if val in NAME_BLOCK_TARGETS or key in NAME_BLOCK_TARGETS:
        return True
    return False


def write_dict(path: Path, header: str, items, kept_pred, dropped_reason_fn):
    lines = [f"# {header}", "# format: <wrong>\\t<correct>  ('#' = comment)"]
    kept = 0
    for k, v in items:
        if k == v:
            continue  # identity rules are no-ops
        if not kept_pred(k, v):
            excl_lines.append(f"[{path.name}] DROP  {k}\t{v}\t# {dropped_reason_fn(k, v)}")
            continue
        lines.append(f"{k}\t{v}")
        kept += 1
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return kept


def main():
    para = load("paraformer_legal_hotwords", SERVER / "paraformer_legal_hotwords.py")
    legal = load("legal_dictionary", SERVER / "legal_dictionary.py")

    # --- step 1: paraformer_legal.txt (SIMPLIFIED) ---
    # Ported VERBATIM. The ONLY §37 line is client/party/firm-staff proper-noun NAMES.
    # Everything else (legal terms, 數量詞「一」colloquial fixes, generic domain vocab)
    # is hand-curated, non-client-specific, and kept as-is — we do NOT invent a
    # "legal vs non-legal" cut (that risked dropping real legal items like 定暫時狀態).
    def para_keep(k, v):
        return not is_name(k, v)

    def para_reason(k, v):
        return "§37 name/proper-noun"

    n_para = write_dict(
        ASSETS / "paraformer_legal.txt",
        "Step 1 — Paraformer legal homophone correction (SIMPLIFIED, before s2tw). "
        "Ported VERBATIM from paraformer_legal_hotwords.PARAFORMER_LEGAL_CORRECTIONS; "
        "ONLY §37 client/party/staff proper-noun names excluded.",
        para.PARAFORMER_LEGAL_CORRECTIONS.items(),
        para_keep, para_reason,
    )

    # --- step 4a: legal_dict_mishear.txt (TRADITIONAL mishear + CN->TW + std chars) ---
    # WHISPER_LEGAL_CORRECTIONS minus name sections; the dict is one flat dict but the
    # name corrections all have CJK-only proper-noun targets in NAME_BLOCK_TARGETS, OR are
    # in the social-graph/active-party sections. We drop by target/source name membership.
    def mishear_keep(k, v):
        if is_name(k, v):
            return False
        return True

    def mishear_reason(k, v):
        return "§37 name/proper-noun"

    merged = []
    merged += list(legal.WHISPER_LEGAL_CORRECTIONS.items())
    merged += list(legal.MAINLAND_TO_TAIWAN.items())
    merged += list(legal.STANDARD_LEGAL_CHARS.items())
    n_mishear = write_dict(
        ASSETS / "legal_dict_mishear.txt",
        "Step 4 — legal_dictionary exact-match corrections (TRADITIONAL, after s2tw). "
        "Ported VERBATIM from legal_dictionary WHISPER_LEGAL_CORRECTIONS + MAINLAND_TO_TAIWAN "
        "+ STANDARD_LEGAL_CHARS; §37 client/party/staff proper-noun names excluded.",
        merged, mishear_keep, mishear_reason,
    )

    # --- step 4b: protected_terms.txt (never-touch) ---
    prot_lines = [
        "# Step 4 — protected legal terms (never modified). "
        "Ported from legal_dictionary.PROTECTED_LEGAL_TERMS; §37 names excluded.",
    ]
    kept_prot = 0
    for term in legal.PROTECTED_LEGAL_TERMS:
        if term in NAME_BLOCK_TARGETS or (CJK.match(term) and term in NAME_BLOCK_TARGETS):
            excl_lines.append(f"[protected_terms.txt] DROP  {term}\t# §37 name/proper-noun")
            continue
        # Also drop any term that is a person/company proper noun present in name block.
        if term in NAME_BLOCK_TARGETS:
            continue
        prot_lines.append(term)
        kept_prot += 1
    (ASSETS / "protected_terms.txt").write_text("\n".join(prot_lines) + "\n", encoding="utf-8")

    EXCL_LOG.write_text("\n".join(excl_lines) + "\n", encoding="utf-8")

    print(f"paraformer_legal.txt   : kept {n_para}")
    print(f"legal_dict_mishear.txt : kept {n_mishear}")
    print(f"protected_terms.txt    : kept {kept_prot}")
    print(f"exclusions logged      : {len(excl_lines)} -> {EXCL_LOG}")


if __name__ == "__main__":
    sys.exit(main())
