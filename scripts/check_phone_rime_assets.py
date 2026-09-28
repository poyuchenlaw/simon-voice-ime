#!/usr/bin/env python3
"""Fail a phone release when its compiled Rime schemas are stale or ungrammatical."""
from pathlib import Path
import re
import sys
import argparse


def check(root: Path, assets: Path | None = None, compiled_only: bool = False) -> None:
    assets = assets or root / "app/src/phone/assets/rime"
    build = assets / "build"
    schemas = ("bopomofo_express", "bopomofo_t9_simon", "terra_pinyin", "stroke")
    for name in schemas:
        compiled = build / f"{name}.schema.yaml"
        source = assets / f"{name}.schema.yaml"
        if not compiled.is_file() or (not compiled_only and not source.is_file()):
            raise ValueError(f"missing Rime schema source or compiled asset: {name}")
        if not compiled_only and compiled.stat().st_mtime_ns < source.stat().st_mtime_ns:
            raise ValueError(f"compiled Rime schema is older than its source: {name}")
    for name in ("bopomofo_express", "bopomofo_t9_simon"):
        path = build / f"{name}.schema.yaml"
        text = path.read_text(encoding="utf-8")
        if re.search(r"(?m)^\s+grammar:\s*0\s*$", text) or "zh-hant-t-essay-bgw" not in text:
            raise ValueError(f"compiled Rime schema lacks Octagram grammar: {name}")
        if name == "bopomofo_express" and "enable_user_dict: true" not in text:
            raise ValueError("compiled traditional Rime schema lacks personal learning")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("assets", nargs="?", type=Path)
    parser.add_argument("--compiled-only", action="store_true")
    args = parser.parse_args()
    try:
        check(Path(__file__).resolve().parents[1], args.assets, args.compiled_only)
    except Exception as error:
        print(f"Rime asset guard: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("Rime asset guard: compiled schemas are fresh; required Octagram/user dictionaries are enabled")
