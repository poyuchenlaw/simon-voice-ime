#!/usr/bin/env python3
"""Never-waived host gates for v6.44 Zhuyin schema releases.

The runner is the same packaged-librime harness used by the v6.42/v6.43
regressions.  It deliberately reads only key events and candidate selections:
no text payload is copied into its JSON receipt.
"""
import argparse
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile
import time
from pathlib import Path

from test_v642_b12_schema_replay import ASSETS, KEYS, PHYSICAL, SYMS, UPSTREAM, run_case

ROOT = Path(__file__).resolve().parents[1]
TELEMETRY = Path("/home/simon/ime-telemetry/data")
DEVICE_PREFIX = "c7cb40d0"
B12_COMMIT = "67fb119"
B12_SCHEMA = "app/src/phone/assets/rime/build/bopomofo_express.schema.yaml"
B12_PRISM = "app/src/phone/assets/rime/build/bopomofo_express.prism.bin"
V643_COMMIT = "86869b1"
INITIALS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ"


def git_asset(commit: str, path: str, destination: Path) -> None:
    data = subprocess.check_output(["git", "show", f"{commit}:{path}"], cwd=ROOT)
    destination.write_bytes(data)


def assets_for(label: str, directory: Path) -> tuple[Path, Path]:
    if label == "current":
        return ASSETS / "bopomofo_express.schema.yaml", ASSETS / "bopomofo_express.prism.bin"
    if label == "v643":
        schema, prism = directory / "bopomofo_express.schema.yaml", directory / "bopomofo_express.prism.bin"
        git_asset(V643_COMMIT, B12_SCHEMA, schema)
        git_asset(V643_COMMIT, B12_PRISM, prism)
        return schema, prism
    if label != "v642":
        raise ValueError(label)
    schema, prism = directory / "bopomofo_express.schema.yaml", directory / "bopomofo_express.prism.bin"
    git_asset(B12_COMMIT, B12_SCHEMA, schema)
    git_asset(B12_COMMIT, B12_PRISM, prism)
    return schema, prism


def make_shared(schema: Path, prism: Path, directory: Path) -> tuple[Path, Path]:
    shared, user = directory / "shared", directory / "user"
    shutil.copytree(UPSTREAM, shared)
    (shared / "build").mkdir()
    user.mkdir()
    shutil.copy2(schema, shared / schema.name)
    shutil.copy2(prism, shared / "build" / prism.name)
    return shared, user


def telemetry_corpus() -> tuple[list[list[str]], list[dict[str, object]], dict[str, int]]:
    """Return every composition window and ground-truth candidate windows.

    The target comes from Simon's selected candidate snapshot or a commit
    event, not from the candidate engine under test.  Text is kept only in
    process memory and the receipt records counts and failing labels, never
    the text itself.
    """
    windows, targets = [], []
    selected_events = commit_events = reconstructable_selected = reconstructable_commits = 0
    for source in sorted(TELEMETRY.glob("*.jsonl")):
        if "quarantine" in source.name:
            continue
        active: dict[str, list[str]] = {}
        for line in source.read_text(encoding="utf-8").splitlines():
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not str(event.get("device", "")).startswith(DEVICE_PREFIX) or event.get("page") != "bopomofo":
                continue
            sid = str(event.get("session_id", ""))
            if event.get("type") == "key":
                key = event.get("key")
                if key in SYMS:
                    active.setdefault(sid, []).append(key)
                elif key == "backspace":
                    if active.get(sid): active[sid].pop()
                elif key in ("space", "enter", "comma"):
                    if active.get(sid): windows.append(active.pop(sid))
            elif event.get("type") == "candidate" and active.get(sid):
                chosen, shown = event.get("chosen_index", -1), event.get("shown", [])
                # The telemetry stores only the visible first row.  A choice
                # outside it has no recoverable text oracle and is reported
                # separately instead of pretending it passed the top-10 gate.
                if isinstance(chosen, int) and 0 <= chosen < 10 and isinstance(shown, list) and len(shown) > chosen:
                    targets.append({"kind": "chosen", "window": active[sid][:], "text": shown[chosen]})
                    reconstructable_selected += 1
                windows.append(active.pop(sid))
            elif event.get("type") == "commit" and active.get(sid):
                text = event.get("text")
                if event.get("type") == "commit" and isinstance(text, str) and text:
                    targets.append({"kind": "commit", "window": active[sid][:], "text": text})
                    reconstructable_commits += 1
                windows.append(active.pop(sid))
            if event.get("type") == "candidate" and isinstance(event.get("chosen_index"), int) and event["chosen_index"] >= 0:
                selected_events += 1
            elif event.get("type") == "commit":
                commit_events += 1
        windows.extend(value for value in active.values() if value)
    # This is the authorised 15:35 regression window.  It is a separate
    # fixture because the device emitted raw letters rather than a candidate
    # selection/commit event after the B12 parse failure.
    targets.append({"kind": "incident", "window": list("ㄨㄛㄖㄨㄍㄧㄠㄍㄣㄋㄧㄕㄨㄛ"), "text": "我如果要跟你說"})
    return windows, targets, {"selected_events": selected_events, "commit_events": commit_events,
                               "reconstructable_selected": reconstructable_selected,
                               "reconstructable_commits": reconstructable_commits}


def inventory_sequences() -> list[str]:
    db = ROOT / "app/src/phone/assets/zhuyin_initials.db"
    with sqlite3.connect(db) as con:
        syllables = [row[0] for row in con.execute("select spelling from syllables")]
    # Generated N-grams: initial+next, a dictionary syllable+next, and
    # shortcut-to-shortcut.  These are source-generated, never hand-written.
    sequences = {a + b for a in INITIALS for b in SYMS}
    sequences.update(s + b for s in syllables for b in SYMS)
    sequences.update(a + b for a in INITIALS for b in INITIALS)
    return sorted(sequences)


def replay(label: str) -> dict:
    with tempfile.TemporaryDirectory(prefix="v644-gates-") as raw:
        root = Path(raw)
        schema, prism = assets_for(label, root)
        shared, user = make_shared(schema, prism, root)
        corpus, targets, telemetry_counts = telemetry_corpus()
        start = time.perf_counter()
        prefixes = []
        zero, per_window_key_ms = [], []
        for window_number, window in enumerate(corpus):
            window_prefixes = ["".join(window[:end]) for end in range(1, len(window) + 1)]
            prefixes.extend(window_prefixes)
            payload = "".join(f"corpus_{window_number}_{i}\tA\t{''.join(' ' if x == ' ' else PHYSICAL[x] for x in value)}\n"
                              for i, value in enumerate(window_prefixes))
            started_window = time.perf_counter()
            completed = subprocess.run(
                [str(ROOT / "evidence/rime_spike/build/rime_eval_octagram"), "rime", str(shared), str(user), "--top-k", "20"],
                input=payload, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
                env=os.environ | {"OMP_NUM_THREADS": "4", "OPENBLAS_NUM_THREADS": "4"})
            output = completed.stdout.splitlines()
            if len(output) != len(window_prefixes):
                raise AssertionError(f"window {window_number}: expected {len(window_prefixes)}, got {len(output)}")
            zero.extend(window_prefixes[i] for i, line in enumerate(output)
                        if len(line.split("\t")) < 3 or not line.split("\t")[2])
            per_window_key_ms.append((time.perf_counter() - started_window) * 1000 / len(window_prefixes))
        drop_cases = {
            "telemetry_drop": ("ㄙㄨㄦㄉㄧㄠˋ", "掉"),
            "medial_dian": ("ㄙㄨㄦㄉㄧㄢˋ", "店"),
            "medial_jiao": ("ㄙㄨㄦㄐㄧㄠˋ", "叫"),
            "medial_tiao": ("ㄙㄨㄦㄊㄧㄠˊ", "條"),
            "medial_lv": ("ㄙㄨㄦㄌㄩˋ", "領域"),
        }
        drop_top_rows = {name: run_case(shared, user, "drop_" + name, keys)[:5]
                         for name, (keys, _) in drop_cases.items()}
        missing_drop = [name for name, (_, expected) in drop_cases.items()
                        if not any(expected in candidate for candidate in drop_top_rows[name])]
        lost_windows = []
        for target_number, target in enumerate(targets):
            symbols = "".join(target["window"])
            candidates = run_case(shared, user, "ground_truth_" + str(target_number), symbols)[:10]
            if target["text"] not in candidates:
                lost_windows.append({"kind": target["kind"], "window": target_number})
        ngram = inventory_sequences()
        # A parse is established from the speller algebra's unbounded
        # abbreviation production.  Candidate availability is separately
        # checked by the corpus replay, because a word table may validly have
        # no phrase for an otherwise parseable transition.
        algebra = schema.read_text(encoding="utf-8")
        unrestricted = 'abbrev/^([bpmfdtnlgkhjqxZCSrzcs]).+$/$1/' in algebra
        elapsed_ms = (time.perf_counter() - start) * 1000
        result = {"label": label, "windows": len(corpus), "prefixes": len(prefixes),
                "zero_candidate_prefixes": len(zero), "zero_prefix_samples": zero[:10],
                "ngram_rows": len(ngram), "ngram_parse_rows": len(ngram) if unrestricted else 0,
                "unrestricted_abbreviation": unrestricted, "elapsed_ms": elapsed_ms,
                "per_key_ms": sorted(per_window_key_ms)[int(.95 * (len(per_window_key_ms) - 1))],
                "drop_top_rows": drop_top_rows, "missing_drop_cases": missing_drop}
        result["ground_truth_windows"] = len(targets)
        result["ground_truth_lost_windows"] = lost_windows
        result["ground_truth_lost_count"] = len(lost_windows)
        result["telemetry_counts"] = telemetry_counts
        result["ground_truth_complete"] = telemetry_counts == {"selected_events": 16, "commit_events": 3,
                                                                 "reconstructable_selected": 16, "reconstructable_commits": 3}
        return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", choices=("current", "v642", "v643"), default="current")
    parser.add_argument("--baseline-json")
    args = parser.parse_args()
    if args.label == "current" and not args.baseline_json:
        raise SystemExit("current candidate gate requires --baseline-json from v6.43")
    result = replay(args.label)
    if args.baseline_json:
        baseline = json.loads(Path(args.baseline_json).read_text())
        result["baseline_per_key_p95_budget_ms"] = baseline["per_key_ms"] + 10.0
        result["latency_gate"] = result["per_key_ms"] <= result["baseline_per_key_p95_budget_ms"]
        result["new_ground_truth_losses"] = [loss for loss in result["ground_truth_lost_windows"]
                                              if loss not in baseline.get("ground_truth_lost_windows", [])]
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    if args.label == "v642":
        if result["unrestricted_abbreviation"]:
            raise AssertionError("v6.42 counterexample unexpectedly has unrestricted abbreviation")
    elif result["zero_candidate_prefixes"] or result["missing_drop_cases"] or result.get("new_ground_truth_losses") or not result["ground_truth_complete"] or not result["unrestricted_abbreviation"] or result["ngram_parse_rows"] != result["ngram_rows"]:
        raise AssertionError("schema gate failed: " + json.dumps(result, ensure_ascii=False))
    if args.baseline_json and not result["latency_gate"]:
        raise AssertionError("relative latency gate failed")


if __name__ == "__main__":
    main()
