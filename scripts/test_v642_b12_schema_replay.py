#!/usr/bin/env python3
"""XFAIL diagnostic: B12 behavior is intentionally reverted in v6.43.

The sequence is the symbol-only portion extracted from the authorised telemetry
incident.  It intentionally contains no device, session, person, or document
data.  The assertion is against the packaged schema, not a reimplementation
of its spelling algebra.
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = ROOT / "evidence/rime_spike/octagram-data"
RUNNER = ROOT / "evidence/rime_spike/build/rime_eval_octagram"
ASSETS = ROOT / "app/src/phone/assets/rime/build"
KEYS = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"
SYMS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"
PHYSICAL = dict(zip(SYMS, KEYS))
EXPECTED_FAILURE_REASON = "v6.43 restores v6.41 abbreviation behavior after B12 caused raw-key passthrough"


def physical(symbols: str) -> str:
    return "".join(" " if symbol == " " else PHYSICAL[symbol] for symbol in symbols)


def run_case(shared: Path, user: Path, ident: str, symbols: str) -> str:
    row = f"{ident}\tA\t{physical(symbols)}\n"
    completed = subprocess.run(
        [str(RUNNER), "rime", str(shared), str(user), "--top-k", "20"],
        input=row,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
        env=os.environ | {"OMP_NUM_THREADS": "4", "OPENBLAS_NUM_THREADS": "4"},
    )
    fields = completed.stdout.strip().split("\t")
    if len(fields) < 3:
        raise AssertionError(f"unexpected Rime replay output: {completed.stdout!r}")
    return fields[2].split("\x1f")


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="v642-b12-package-") as directory:
        root = Path(directory)
        shared, user = root / "shared", root / "user"
        shutil.copytree(UPSTREAM, shared)
        (shared / "build").mkdir()
        user.mkdir()
        schema = ASSETS / "bopomofo_express.schema.yaml"
        prism = ASSETS / "bopomofo_express.prism.bin"
        for source in (schema, prism):
            if not source.is_file():
                raise AssertionError(f"missing packaged Rime asset: {source}")
        # The app loads the compiled schema; placing it at the Rime shared-data
        # root is the documented host-runner convention.  The prism remains in
        # build/ beside the other generated data.
        shutil.copy2(schema, shared / schema.name)
        shutil.copy2(prism, shared / "build" / prism.name)
        cases = {
            "telemetry_suffix": ("ㄎㄨㄢˇㄒㄧㄤˋㄧ ㄐㄩˋㄐㄧㄝˊㄙㄨㄦㄉㄧㄠˋ", "掉"),
            "toneless_dian": ("ㄙㄨㄦㄉㄧㄢˋ", "店"),
            "toneless_jiao": ("ㄙㄨㄦㄐㄧㄠˋ", "叫"),
            "toneless_tiao": ("ㄙㄨㄦㄊㄧㄠˊ", "條"),
            "toneless_lv": ("ㄙㄨㄦㄌㄩˋ", "率"),
        }
        observed = {
            name: run_case(shared, user, "b12_" + name, symbols)
            for name, (symbols, _) in cases.items()
        }
    regressions = []
    for name, (_, expected) in cases.items():
        candidates = observed[name]
        if not any(expected in candidate for candidate in candidates):
            regressions.append(f"{name}: {candidates[:5]!r}")
    if not regressions:
        raise AssertionError("XPASS B12 diagnostic: " + EXPECTED_FAILURE_REASON)
    print("XFAIL B12 packaged-schema replay:", EXPECTED_FAILURE_REASON)
    print("XFAIL cases=", len(regressions))


if __name__ == "__main__":
    main()
