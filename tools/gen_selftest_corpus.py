#!/usr/bin/env python3
"""
Generate the JVM self-test golden corpus for OnDeviceCorrector.

For each (simplified/mishear) INPUT we compute the EXPECTED output by running the SAME
deterministic pipeline the on-device corrector implements, but using the canonical reference
implementations so the test asserts true server-parity:
  step1 paraformer_legal_correct (paraformer_legal_hotwords)   [name rules excluded == on-device]
  step2 _remove_fillers (ported regexes — replicated here to match server verbatim)
  step3 OpenCC('s2tw').convert + legal_overrides (opencc_legal_overrides.txt) + 臺->台
  step4 legal_dictionary mishear (WHISPER+MAINLAND+STANDARD, name-excluded) w/ protected-term mask

Crucially, step1/step4 here use the SAME §37-filtered asset dicts the APK ships
(app/src/main/assets/correction/*.txt), NOT the raw server dicts, so expectations match exactly
what the on-device corrector will do. step3 uses real python-opencc s2tw as ground truth for the
glyph conversion the pure-Java OpenCcS2tw must reproduce.

Output: app/src/test/resources/selftest_corpus.tsv  (input\texpected, plus a few guard cases tagged)
"""
import re
from pathlib import Path

import opencc

REPO = Path("/home/simon/simon-voice-ime")
ASSETS = REPO / "app/src/main/assets/correction"
OUT = REPO / "app/src/test/resources/selftest_corpus.tsv"
OUT.parent.mkdir(parents=True, exist_ok=True)

cc = opencc.OpenCC("s2tw")


def load_pairs(path):
    pairs = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        if "\t" not in line:
            continue
        k, rest = line.split("\t", 1)
        v = re.split(r"[ \t]", rest, 1)[0]
        if k and v and k != v:
            pairs.append((k, v))
    return pairs


def load_lines(path):
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        out.append(t)
    return out


PARA = sorted(load_pairs(ASSETS / "paraformer_legal.txt"), key=lambda x: -len(x[0]))
OVR = sorted(load_pairs(ASSETS / "legal_overrides.txt"), key=lambda x: -len(x[0]))
MIS = sorted(load_pairs(ASSETS / "legal_dict_mishear.txt"), key=lambda x: -len(x[0]))
PROT = sorted(load_lines(ASSETS / "protected_terms.txt"), key=lambda x: -len(x))

# --- _remove_fillers verbatim ---
def remove_fillers(text):
    text = re.sub(r"<\s*sil\s*>", "", text)
    text = re.sub(r"[呃嗯額唔欸]{2,}", "", text)
    text = re.sub(r"[，,。.、！!？?\s]+[呃嗯額唔欸][，,。.、！!？?\s]*", "，", text)
    text = re.sub(r"[呃嗯額唔欸][，,。.、！!？?\s]+", "", text)
    text = re.sub(r"^[呃嗯額唔欸]+[，,。.]?\s*", "", text)
    text = re.sub(r"[，,。.]?\s*[呃嗯額唔欸]+$", "", text)
    text = re.sub(r"(?<=[一-鿿])[呃嗯額](?=[一-鿿])", "", text)
    text = re.sub(r"[，,、]{2,}", "，", text)
    text = re.sub(r"^[，,、。.]+", "", text)
    return text.strip()


def apply_pairs(text, pairs):
    for k, v in pairs:
        if k in text:
            text = text.replace(k, v)
    return text


def apply_mishear_protected(text):
    hits = []
    masked = text
    for term in PROT:
        if term and term in masked:
            sentinel = chr(0xE000 + (len(hits) & 0x18FF))
            hits.append(term)
            masked = masked.replace(term, sentinel)
    masked = apply_pairs(masked, MIS)
    for i, term in enumerate(hits):
        masked = masked.replace(chr(0xE000 + (i & 0x18FF)), term)
    return masked


def pipeline(text):
    t = apply_pairs(text, PARA)      # step 1
    t = remove_fillers(t)            # step 2
    t = cc.convert(t)                # step 3a (s2tw glyph)
    t = apply_pairs(t, OVR)          # step 3b overrides
    t = t.replace("臺", "台")         # 臺->台
    t = apply_mishear_protected(t)   # step 4
    return t


# ------------------------------------------------------------------
# Build inputs: cover 簡→繁, the legal overrides, mishear fixes, protected no-touch,
# filler removal, and the 數量詞「一」colloquial fixes. Expected is computed by pipeline().
# Inputs are SIMPLIFIED / mishear-laden, as SenseVoice would emit.
# ------------------------------------------------------------------
INPUTS = [
    # --- 簡→繁 conversion (clean simplified legal) ---
    "债权人对债务人有请求权",
    "担保物权包括抵押权和质权",
    "继承人对遗产有特留分及应继分",
    "原告主张被告应给付损害赔偿",
    "本件诉讼标的金额为五百万元",
    "法院裁定驳回上诉",
    "当事人声请调查证据",
    "消灭时效从请求权可行使时起算",
    "被告应连带给付原告法定迟延利息",
    "声请假扣押并提存担保金",
    "原告愿供担保请准宣告假执行",
    "确认两造间雇佣关系存在",
    "请求判决准予两造离婚",
    "两造共有土地应予分割",
    "被告无法律上之原因而受有利益",
    "侵权行为损害赔偿请求权",
    "诉之声明第一项",
    "本院定暂时状态之处分",
    "声请人请求法院释明",
    "辩论意旨状",
    # --- 法律 overrides (s2tw makes wrong form, override fixes) ---
    "系争不动产应办理所有权移转登记",   # 系争->係爭(s2tw)->系爭(override)
    "民事诉讼程序应予遵守",            # 程序 must stay 程序 not 程式
    "准予假扣押之裁定",               # 准/準
    "本件涉及正当法律程序",
    "管辖权限范围内之事项",
    # --- mishear corrections (simplified homophone, paraformer step1) ---
    "起素状已经提出",                 # 起素->起诉
    "本件已经判绝确定",               # 判绝->判决
    "债圈人主张权利",                 # 债圈->债权
    "被告应负赔常责任",               # 赔常->赔偿
    "路师代理当是人出庭",             # 路师->律师, 当是人->当事人
    "声情假处份",                     # 声情->声请, 假处份->假处分
    "本件涉及法国案之处理",           # 法国案->法顾案
    "移个被告应负责任",               # 移个->一个
    "进移步说明如下",                 # 进移步->进一步
    "移造辩论判决",                   # 移造->一造
    # --- 繁體 mishear (legal_dict_mishear traditional) ---
    "債圈人應向債務人請求",           # 債圈->債權
    "本件業經裁頂駁回",               # 裁頂->裁定
    "原告聲請假扣呀",                 # 假扣呀->假扣押 (paraformer simplified won't catch 繁體; mishear will)
    # --- protected-term no-touch (these traditional terms must survive unchanged) ---
    "債權人對債務人主張請求權",       # already correct; must stay identical
    "假扣押裁定已經確定",
    "言詞辯論終結",
    "原告負舉證責任",
    # --- filler removal ---
    "呃这个被告应该负责任",           # head filler removed
    "原告呃主张损害赔偿",             # between-CJK filler removed
    "被告嗯嗯应负责任",               # repeated filler removed
    "这个案件呃呃确实有问题嗯",       # multiple fillers
    # --- mixed real-world legal dictation ---
    "原告依民法第二百五十四条规定解除契约",
    "被告占用原告所有之系争土地受有相当于租金之不当得利",
    "请求撤销被告所为之诈害债权行为",
    "被告应将系争房屋腾空返还原告",
    "确认被告持有原告所签发之本票债权不存在",
]

rows = []
for inp in INPUTS:
    exp = pipeline(inp)
    rows.append(("CASE", inp, exp))

# --- explicit 改字-guard cases: handled in Java test directly (stubbed punctuator). ---
# We emit a couple of GUARD rows = (deterministic preGuard text) used by the test to feed a
# character-altering stub and assert fallback. Expected here is the deterministic step-4 output.
GUARD_INPUTS = [
    "债权人对债务人有请求权",
    "原告主张被告应给付损害赔偿",
]
for inp in GUARD_INPUTS:
    rows.append(("GUARD", inp, pipeline(inp)))

with OUT.open("w", encoding="utf-8") as f:
    f.write("# tag\tinput\texpected   (generated by tools/gen_selftest_corpus.py; server-parity golden)\n")
    for tag, inp, exp in rows:
        f.write(f"{tag}\t{inp}\t{exp}\n")

n_case = sum(1 for r in rows if r[0] == "CASE")
n_guard = sum(1 for r in rows if r[0] == "GUARD")
print(f"wrote {len(rows)} rows ({n_case} CASE + {n_guard} GUARD) -> {OUT}")
for tag, inp, exp in rows[:8]:
    print(f"  [{tag}] {inp!r} -> {exp!r}")
