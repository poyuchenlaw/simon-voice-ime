import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare_t9_rime_assets.py")
SPEC = importlib.util.spec_from_file_location("prepare_t9_rime_assets", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PrepareT9RimeAssetsTest(unittest.TestCase):
    def make_fixture(self, root):
        source, build, destination = root / "source", root / "build", root / "destination"
        source.mkdir()
        build.mkdir()
        for name in ("default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml",
                     "terra_pinyin.schema.yaml", "bopomofo_express.schema.yaml", "stroke.schema.yaml",
                     "zh-hant-t-essay-bgc.gram", "zh-hant-t-essay-bgw.gram"):
            (source / name).write_text(name, encoding="utf-8")
        (source / "bopomofo_t9_simon.schema.yaml").write_text(
            "schema:\n  schema_id: bopomofo_t9_simon\n__patch:\n  - grammar:/hant?\n",
            encoding="utf-8")
        (source / "grammar.yaml").write_text(
            "hant:\n  grammar:\n    language: zh-hant-t-essay-bgw\n"
            "hant_char:\n  grammar:\n    language: zh-hant-t-essay-bgc\n",
            encoding="utf-8")
        (source / "opencc").mkdir()
        for name in ("terra_pinyin.table.bin", "bopomofo_t9_simon.prism.bin",
                     "bopomofo_express.prism.bin", "terra_pinyin.prism.bin",
                     "terra_pinyin.reverse.bin", "stroke.prism.bin", "stroke.table.bin",
                     "stroke.reverse.bin", "bopomofo_t9_simon.schema.yaml",
                     "bopomofo_express.schema.yaml", "terra_pinyin.schema.yaml", "stroke.schema.yaml"):
            if name == "bopomofo_t9_simon.schema.yaml":
                (build / name).write_text('grammar:\n  language: "zh-hant-t-essay-bgw"\n', encoding="utf-8")
            else:
                (build / name).write_bytes(b"compiled")
        return source, build, destination

    def test_packages_selected_octagram_grammar_and_runtime_config(self):
        with tempfile.TemporaryDirectory() as temp:
            source, build, destination = self.make_fixture(Path(temp))
            for gram in ("bgc", "bgw"):
                with self.subTest(gram=gram):
                    MODULE.prepare_assets(source, build, destination, gram)
                    other = "bgw" if gram == "bgc" else "bgc"
                    self.assertIn(f"language: zh-hant-t-essay-{gram}",
                                  (destination / "grammar.yaml").read_text())
                    self.assertIn("grammar:/hant?",
                                  (destination / "bopomofo_t9_simon.schema.yaml").read_text())
                    self.assertIn(f"zh-hant-t-essay-{gram}",
                                  (destination / "build/bopomofo_t9_simon.schema.yaml").read_text())
                    self.assertTrue((destination / f"zh-hant-t-essay-{gram}.gram").is_file())
                    self.assertFalse((destination / f"zh-hant-t-essay-{other}.gram").exists())

    def test_missing_input_preserves_previous_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            source, build, destination = self.make_fixture(Path(temp))
            destination.mkdir()
            sentinel = destination / "previous-version.txt"
            sentinel.write_text("keep", encoding="utf-8")
            (source / "symbols.yaml").unlink()
            with self.assertRaises(FileNotFoundError):
                MODULE.prepare_assets(source, build, destination, "bgw")
            self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))

    def test_unpatched_t9_schema_is_rejected_without_replacing_assets(self):
        with tempfile.TemporaryDirectory() as temp:
            source, build, destination = self.make_fixture(Path(temp))
            destination.mkdir()
            sentinel = destination / "previous-version.txt"
            sentinel.write_text("keep", encoding="utf-8")
            (source / "bopomofo_t9_simon.schema.yaml").write_text("schema:\n  schema_id: t9\n",
                                                                  encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.prepare_assets(source, build, destination, "bgw")
            self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
