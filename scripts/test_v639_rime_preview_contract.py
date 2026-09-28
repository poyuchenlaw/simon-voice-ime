#!/usr/bin/env python3
"""Regression checks for the v6.39 traditional Rime and two-row preview path."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class V639RimePreviewContractTest(unittest.TestCase):
    def test_express_schema_uses_octagram_and_personal_dictionary(self):
        schema = (ROOT / "app/src/phone/assets/rime/bopomofo_express.schema.yaml").read_text()
        self.assertIn("grammar:/hant?", schema)
        self.assertRegex(schema, r"(?m)^\s+enable_user_dict:\s*true\s*$")

    def test_build_task_recompiles_express_schema_and_release_checks_all_compiled_schemas(self):
        build = (ROOT / "app/build.gradle").read_text()
        generator = (ROOT / "scripts/prepare_t9_rime_assets.py").read_text()
        release = (ROOT / "scripts/release_phone.sh").read_text()
        self.assertIn("prepare_t9_rime_assets.py", build)
        self.assertIn("rime_deployer", generator)
        self.assertIn("check_phone_rime_assets.py", release)

    def test_bopomofo_page_has_independent_stream_preview_above_candidates(self):
        tree = ET.parse(ROOT / "app/src/main/res/layout/keyboard_bopomofo.xml")
        ids = [element.get(ANDROID + "id", "") for element in tree.iter()]
        preview = next(i for i, value in enumerate(ids) if value.endswith("/boStreamPreview"))
        candidates = next(i for i, value in enumerate(ids) if value.endswith("/boCandidateBar"))
        self.assertLess(preview, candidates)

    def test_production_zhuyin_engine_is_rime_backed(self):
        service = (ROOT / "app/src/main/java/com/simon/voiceime/SimonIMEService.java").read_text()
        factory = service.split("private ZhuyinInputController.Engine createZhuyinEngine()", 1)[1]
        self.assertIn("RimeZhuyinEngine", factory)
        self.assertLess(factory.index("RimeZhuyinEngine"), factory.index("ChewingEngine"))


if __name__ == "__main__":
    unittest.main()
