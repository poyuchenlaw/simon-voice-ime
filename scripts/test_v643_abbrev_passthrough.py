#!/usr/bin/env python3
"""Regression replay for initial abbreviations followed by a non-syllable final.

The expected candidates are user-observable phrases from the authorised
v6.42 incident.  The B12 comparison uses the exact v6.42 packaged blobs from
the repository, not a reimplementation of Rime spelling algebra.
"""
import shutil
import subprocess
import tempfile
from pathlib import Path

from test_v642_b12_schema_replay import ASSETS, UPSTREAM, run_case


ROOT = Path(__file__).resolve().parents[1]
B12_COMMIT = "67fb119"
B12_ASSETS = (
    "app/src/phone/assets/rime/build/bopomofo_express.schema.yaml",
    "app/src/phone/assets/rime/build/bopomofo_express.prism.bin",
)
CASES = {
    "simon_sentence": ("ㄨㄛㄖㄨㄍㄧㄠㄍㄣㄋㄧㄕㄨㄛ", "我如果要跟你說"),
    "simon_clause": ("ㄖㄨㄍㄧㄠ", "如果要"),
}
TELEMETRY_DROP = ("ㄎㄨㄢˇㄒㄧㄤˋㄧ ㄐㄩˋㄐㄧㄝˊㄙㄨㄦㄉㄧㄠˋ", "掉")


def write_git_asset(destination: Path, source: str) -> None:
    completed = subprocess.run(
        ["git", "show", f"{B12_COMMIT}:{source}"],
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    destination.write_bytes(completed.stdout)


def replay(
    schema: Path, prism: Path, prefix: str, cases: dict[str, tuple[str, str]] = CASES
) -> dict[str, list[str]]:
    with tempfile.TemporaryDirectory(prefix=f"{prefix}-") as directory:
        root = Path(directory)
        shared, user = root / "shared", root / "user"
        shutil.copytree(UPSTREAM, shared)
        (shared / "build").mkdir()
        user.mkdir()
        shutil.copy2(schema, shared / schema.name)
        shutil.copy2(prism, shared / "build" / prism.name)
        return {
            name: run_case(shared, user, f"{prefix}_{name}", symbols)
            for name, (symbols, _) in cases.items()
        }


def main() -> None:
    restored = replay(
        ASSETS / "bopomofo_express.schema.yaml",
        ASSETS / "bopomofo_express.prism.bin",
        "v643_restored",
    )
    with tempfile.TemporaryDirectory(prefix="v643-b12-") as directory:
        directory_path = Path(directory)
        schema, prism = (directory_path / Path(source).name for source in B12_ASSETS)
        for destination, source in zip((schema, prism), B12_ASSETS):
            write_git_asset(destination, source)
        b12 = replay(schema, prism, "v643_b12")
    telemetry = replay(
        ASSETS / "bopomofo_express.schema.yaml",
        ASSETS / "bopomofo_express.prism.bin",
        "v643_telemetry_drop",
        {"telemetry_drop": TELEMETRY_DROP},
    )

    failures = []
    for name, (_, expected) in CASES.items():
        if not any(expected in candidate for candidate in restored[name]):
            failures.append(f"restored {name}: {restored[name][:5]!r}")
        if any(expected in candidate for candidate in b12[name]):
            failures.append(f"B12 unexpectedly accepts {name}: {b12[name][:5]!r}")
    if failures:
        raise AssertionError("; ".join(failures))
    print("PASS v6.43 abbreviation replay; restored and B12 comparison cases=", len(CASES))
    print("INFO restored telemetry_drop top5=", telemetry["telemetry_drop"][:5])


if __name__ == "__main__":
    main()
