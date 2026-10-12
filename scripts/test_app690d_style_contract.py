#!/usr/bin/env python3
"""D-line specification checks. XML assertions inspect actual inflated tag inputs.

No device or pixel claims; typography is initialized at the permitted inflate hook.
"""
import os
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(os.environ.get("APP690D_SOURCE_ROOT",pathlib.Path(__file__).resolve().parents[1]))
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
        self.assertTrue('private View buildChoiceGrid(' in SERVICE.read_text(),'symbol grid overload missing')
        body = method(SERVICE.read_text(), 'private View buildChoiceGrid(')
        self.assertIn('KeyboardCandidates.applyGrid(', body, '3x3 needs tools keycaps/system font/18sp')
        self.assertNotRegex(body, r'setTextSize\(\s*\d', 'R4-d forbids inline numeric text sizes')

    def test_r8_clip_holder_initialized_at_creation(self):
        source = SERVICE.read_text()
        start = source.index('private class ClipAdapter ')
        create = method(source[start:], '@Override public VH onCreateViewHolder(')
        self.assertIn('KeyboardTheme.apply(', create, 'R8: initialize style when holder is inflated')


    def test_r1_r3_r4_initialization_and_page_fit(self):
        source = SERVICE.read_text()
        body = method(source, 'public View onCreateInputView()')
        self.assertIn('styleDTypingPage(englishKeyboard)', body)
        self.assertIn('styleDTypingPage(numbersKeyboard)', body)
        fit = method(source, 'private void fitDTypingPage(')
        self.assertIn('cache.fit(', fit)
        self.assertIn('toUpperCase(java.util.Locale.ROOT)', fit)
        self.assertIn('KeyboardLabels.contentWidth(', fit)
        init = method(source, 'private void collectDTypingLabels(')
        self.assertIn('KeyboardTypeface.forLabel(', init)
        self.assertIn('KeyboardLabels.FUNCTION_TEXT_SP', init)
        self.assertIn('setSingleLine(true)', init)

    def test_r4d_owned_methods_have_no_numeric_text_sizes_or_colors(self):
        source = SERVICE.read_text()
        for sig in ('private void showClipboardPanel()', 'private void showCommandsPanel()',
                    'private void showAddToCommandsInline(', 'private void showBatchAddToCommandsInline(List',
                    'private void showVocabListInline()', 'private void showBatchAddToCommandsInline()', 'void dualHandPanel(', 'private void updateShiftUI()'):
            body = method(source, sig)
            self.assertNotRegex(body, r'setTextSize\(\s*\d|(?:0x|#)[0-9a-fA-F]{6,8}|R\.color\.|Color\.(?:WHITE|TRANSPARENT)')

    def test_r1_a2_5_custom_tab_and_bottom_dot(self):
        body = method(SERVICE.read_text(), 'private void showCommandsPanel()')
        self.assertIn('styleDButton(tab,false)', body)
        self.assertIn('styleDButton(clipboardTab,true)', body)
        for page in ('keyboard_english.xml','keyboard_numbers.xml'):
            root=ET.parse(LAYOUT/page).getroot()
            dot=next(v for v in root.iter() if v.get(ANDROID+'tag')=='key:.')
            self.assertEqual('12sp',dot.get(ANDROID+'textSize'))
            tags={t.get(ANDROID+'id'):t.get(ANDROID+'value') for t in dot.findall('tag')}
            self.assertEqual('tools',tags.get('@id/keyboard_role'))

    def test_r8_no_style_on_bind(self):
        source=SERVICE.read_text(); start=source.index('private class ClipAdapter ')
        bind=method(source[start:],'@Override public void onBindViewHolder(')
        self.assertNotRegex(bind,r'KeyboardTheme|KeyboardKeycaps|cache.fit|setBackground')
        self.assertIn('buttonTint', (LAYOUT/'clip_item.xml').read_text())
        self.assertNotRegex((LAYOUT/'clip_item.xml').read_text(),r'#[0-9a-fA-F]{6,8}')

    def test_r5_down_entry_is_shared_only(self):
        source=(ROOT/'app/src/main/java/com/simon/voiceime/KeyboardTouchLayout.java').read_text()
        prefix=source[source.index('long dispatchStarted='):source.index('boolean consumed = target.key.dispatchTouchEvent')]
        self.assertEqual(1,prefix.count('KeyboardHaptics.press(target.key)'))
        self.assertIn('if(action==MotionEvent.ACTION_DOWN)',prefix)
        for sig in ('private void styleDTypingPage(', 'private void collectDTypingLabels('):
            self.assertTrue(sig in SERVICE.read_text(),sig)
            self.assertNotIn('KeyboardHaptics.press',method(SERVICE.read_text(),sig))

if __name__ == '__main__':
    unittest.main(verbosity=2)
