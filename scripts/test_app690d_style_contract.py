#!/usr/bin/env python3
"""D-line specification checks. XML assertions inspect actual inflated tag inputs.

No device or pixel claims; initial typography still needs a permitted inflate hook.
"""
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
LAYOUT = ROOT / 'app/src/main/res/layout'
SERVICE = ROOT / 'app/src/main/java/com/simon/voiceime/SimonIMEService.java'


def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    pos = opening + 1
    # These inspected methods have no brace characters inside string literals.
    while depth:
        depth += (source[pos] == '{') - (source[pos] == '}')
        pos += 1
    return source[start:pos]


class App690DStyleContract(unittest.TestCase):
    def test_r2_character_and_function_roles_preserve_routing_tags(self):
        for page in ('keyboard_english.xml', 'keyboard_numbers.xml'):
            root = ET.parse(LAYOUT / page).getroot()
            keys = [v for v in root.iter() if v.get(ANDROID + 'tag', '').startswith('key:')]
            self.assertTrue(keys, page)
            for key in keys:
                route = key.get(ANDROID + 'tag')[4:]
                text = key.get(ANDROID + 'text', '')
                character = len(route) == 1 and text != '.'
                expected = 'right' if character else 'core' if route in ('enter', 'backspace') else 'tools'
                tags = {t.get(ANDROID + 'id'): t.get(ANDROID + 'value') for t in key.findall('tag')}
                self.assertEqual(expected, tags.get('@id/keyboard_role'), f'{page} key:{route}')

    def test_r2d_no_literal_or_keyboard_color_resource_in_owned_xml(self):
        for page in ('keyboard_english.xml', 'keyboard_numbers.xml', 'clipboard_panel.xml', 'commands_panel.xml'):
            source = (LAYOUT / page).read_text()
            violations = re.findall(r'#[0-9a-fA-F]{6,8}\b|@color/\w+', source)
            self.assertEqual([], violations, page)

    def test_run1_2_path_c_uses_candidate_helper(self):
        body = method(SERVICE.read_text(), 'private void updateSuggestionBar()')
        self.assertIn('KeyboardCandidates.apply(', body, 'Path C must use 19sp/system-font candidate API')

    def test_a2_10_grid_uses_grid_helper(self):
        body = method(SERVICE.read_text(), 'View buildChoiceGrid(')
        self.assertIn('KeyboardCandidates.applyGrid(', body, '3x3 needs tools keycaps/system font/18sp')
        self.assertNotRegex(body, r'setTextSize\(\s*\d', 'R4-d forbids inline numeric text sizes')

    def test_r8_clip_bind_does_not_apply_style(self):
        source = SERVICE.read_text()
        start = source.index('private class ClipAdapter ')
        bind = method(source[start:], '@Override public void onBindViewHolder(')
        self.assertNotIn('KeyboardTheme.apply(', bind, 'Style belongs in onCreateViewHolder')


if __name__ == '__main__':
    unittest.main(verbosity=2)
