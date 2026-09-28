#!/usr/bin/env python3
"""Package validated Rime runtime assets and one selected Octagram model."""
from pathlib import Path
import re
import shutil
import os
import subprocess
import tempfile


SCHEMA_FILES = (
    "default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml",
    "bopomofo_t9_simon.schema.yaml", "terra_pinyin.schema.yaml",
    "bopomofo_express.schema.yaml", "stroke.schema.yaml",
)
BUILD_FILES = (
    "bopomofo_t9_simon.prism.bin", "bopomofo_express.prism.bin", "terra_pinyin.prism.bin",
    "terra_pinyin.table.bin", "terra_pinyin.reverse.bin", "stroke.prism.bin",
    "stroke.table.bin", "stroke.reverse.bin",
)
BUILD_SCHEMA_FILES = (
    "bopomofo_t9_simon.schema.yaml", "bopomofo_express.schema.yaml",
    "terra_pinyin.schema.yaml", "stroke.schema.yaml",
)


def prepare_assets(src: Path, build: Path, dst: Path, gram: str,
                   grammar_src: Path | None = None,
                   t9_schema_build: Path | None = None) -> int:
    if gram not in {"bgc", "bgw"}:
        raise ValueError("gram must be bgc or bgw")
    grammar_src = grammar_src or src
    t9_schema_build = t9_schema_build or build / "bopomofo_t9_simon.schema.yaml"
    sources = ([src / name for name in SCHEMA_FILES]
               + [build / name for name in BUILD_FILES]
               + [build / name for name in BUILD_SCHEMA_FILES if name != "bopomofo_t9_simon.schema.yaml"]
               + [t9_schema_build]
               + [grammar_src / "grammar.yaml", grammar_src / f"zh-hant-t-essay-{gram}.gram"])
    missing = [str(path) for path in sources if not path.is_file()]
    if not (src / "opencc").is_dir():
        missing.append(str(src / "opencc/"))
    if missing:
        raise FileNotFoundError("missing required Rime inputs: " + ", ".join(missing))

    schema_text = (src / "bopomofo_t9_simon.schema.yaml").read_text(encoding="utf-8")
    if "grammar:/hant?" not in schema_text:
        raise ValueError("T9 schema must import grammar:/hant? for Octagram to run")

    grammar = (grammar_src / "grammar.yaml").read_text(encoding="utf-8")
    grammar, replaced = re.subn(
        r"(?m)(^hant:\s*\n\s+grammar:\s*\n\s+language:\s*)zh-hant-t-essay-bg[cw]",
        rf"\g<1>zh-hant-t-essay-{gram}", grammar, count=1)
    if replaced != 1:
        raise ValueError("grammar.yaml has no unique hant language model setting")

    compiled_schema = t9_schema_build.read_text(encoding="utf-8")
    compiled_schema, replaced = re.subn(
        r'(?m)^(\s+language:\s*)["\']?zh-hant-t-essay-bg[cw]["\']?\s*$',
        rf'\g<1>"zh-hant-t-essay-{gram}"', compiled_schema, count=1)
    if replaced != 1:
        raise ValueError("compiled T9 schema must contain one Octagram language setting")

    dst.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{dst.name}.", dir=dst.parent) as temporary:
        temporary = Path(temporary)
        staged = temporary / "new"
        staged.mkdir()
        for name in SCHEMA_FILES:
            shutil.copy2(src / name, staged / name)
        (staged / "grammar.yaml").write_text(grammar, encoding="utf-8")
        shutil.copy2(grammar_src / f"zh-hant-t-essay-{gram}.gram",
                     staged / f"zh-hant-t-essay-{gram}.gram")
        (staged / "build").mkdir()
        for name in BUILD_FILES:
            shutil.copy2(build / name, staged / "build" / name)
        for name in BUILD_SCHEMA_FILES:
            if name == "bopomofo_t9_simon.schema.yaml":
                continue
            shutil.copy2(build / name, staged / "build" / name)
        (staged / "build/bopomofo_t9_simon.schema.yaml").write_text(
            compiled_schema, encoding="utf-8")
        shutil.copytree(src / "opencc", staged / "opencc")

        backup = temporary / "previous"
        has_previous = dst.exists()
        if has_previous:
            dst.rename(backup)
        try:
            staged.rename(dst)
        except OSError:
            if has_previous and backup.exists() and not dst.exists():
                backup.rename(dst)
            raise

    return sum(path.stat().st_size for path in dst.rglob("*") if path.is_file())


def compile_express_schema(root: Path, build: Path) -> None:
    """Compile the traditional page schema from source before packaging assets."""
    upstream = root / "evidence/rime_spike/octagram-data"
    deployer = Path(os.environ.get(
        "RIME_DEPLOYER",
        str(root / "evidence/rime_spike/host-install-octagram/bin/rime_deployer"),
    ))
    if not deployer.is_file():
        raise FileNotFoundError(f"Rime schema compiler not found: {deployer}")
    if not (upstream / "bopomofo.schema.yaml").is_file() or not (upstream / "terra_pinyin.dict.yaml").is_file():
        raise FileNotFoundError(f"Rime source schema/dictionary inputs missing: {upstream}")

    build.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="rime-express-") as directory:
        work = Path(directory)
        shared, user = work / "shared", work / "user"
        shared.mkdir(); user.mkdir()
        for path in upstream.iterdir():
            target = shared / path.name
            if path.is_dir():
                shutil.copytree(path, target)
            elif path.is_file():
                shutil.copy2(path, target)
        source = root / "app/src/phone/assets/rime"
        for name in ("bopomofo_express.schema.yaml", "grammar.yaml", "key_bindings.yaml",
                     "punctuation.yaml", "symbols.yaml", "zh-hant-t-essay-bgw.gram"):
            shutil.copy2(source / name, shared / name)
        environment = os.environ.copy()
        environment["LD_LIBRARY_PATH"] = str(deployer.parent.parent / "lib")
        subprocess.run(
            [str(deployer), "--compile", str(shared / "bopomofo_express.schema.yaml"),
             str(user), str(shared), str(build)],
            cwd=work, env=environment, check=True,
        )
    compiled = build / "bopomofo_express.schema.yaml"
    if not compiled.is_file():
        raise RuntimeError("Rime compiler completed without producing bopomofo_express.schema.yaml")
    text = compiled.read_text(encoding="utf-8")
    if re.search(r"(?m)^\s+grammar:\s*0\s*$", text) or "zh-hant-t-essay-bgw" not in text or "enable_user_dict: true" not in text:
        raise RuntimeError("compiled traditional Rime schema is missing Octagram or personal learning")


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="rime-build-") as directory:
        build = Path(directory) / "build"
        shutil.copytree(root / "app/src/phone/assets/rime/build", build)
        compile_express_schema(root, build)
        size = prepare_assets(root / "app/src/phone/assets/rime", build,
                              root / "app/src/phone/assets/rime", "bgw",
                              root / "app/src/phone/assets/rime",
                              root / "app/src/phone/assets/rime/build/bopomofo_t9_simon.schema.yaml")
    print(f"prepared {size} bytes of prebuilt Rime data")
