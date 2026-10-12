#!/usr/bin/env python3
"""Executable source invariants; Android behavior is covered by the companion harness."""
import pathlib, re, unittest
W=pathlib.Path(__file__).resolve().parents[1]
MAIN=W/'app/src/main/java/com/simon/voiceime'
PHONE=W/'app/src/phone/java/com/simon/voiceime'
class Contract(unittest.TestCase):
 def test_single_font_holder(self):
  k=(PHONE/'DualHandKeyboard.java').read_text()
  self.assertNotIn('Typeface.createFromAsset',k)
  self.assertIn('KeyboardTypeface.get(service)',k)
 def test_factory_and_no_diamond_preserve_dot(self):
  k=(PHONE/'DualHandKeyboard.java').read_text()
  self.assertNotRegex(k,r'drawPath\(diamond|diamond=new Path')
  self.assertIn('KeyboardKeycaps.apply(',k)
  self.assertIn('ink.setColor(color("secondary"))',k)
  self.assertIn('ink.setAlpha(80)',k); self.assertIn('dp(1.5f),ink',k)
 def test_role_bridge_and_reopen_gate(self):
  t=(MAIN/'KeyboardTheme.java').read_text()
  self.assertNotIn('value==0x',t)
  self.assertIn('KeyboardRoles.apply',t)
  s=(MAIN/'SimonIMEService.java').read_text()
  self.assertEqual(2,s.count('KeyboardHaptics.sync(this,rootView)'))
 def test_touch_down_haptic_before_dispatch_not_repeat(self):
  t=(MAIN/'KeyboardTouchLayout.java').read_text()
  start=t.index('private void dispatchKey(');end=t.index('private static long diagnosticMinute',start)
  body=t[start:end]
  self.assertIn('action==MotionEvent.ACTION_DOWN',body)
  self.assertIn('KeyboardHaptics.press(target.key)',body)
  self.assertLess(body.index('KeyboardHaptics.press'),body.index('target.key.dispatchTouchEvent'))
 def test_common_fit_accepts_label_collection(self):
  k=(PHONE/'DualHandKeyboard.java').read_text()
  self.assertIn('fitCache.fit(',k)
  self.assertNotIn('for(float sp=24',k)
  label=(PHONE/'DualHandLabelView.java').read_text()
  self.assertIn('KeyboardLabels.extent',label); self.assertIn('KeyboardIcons.draw',label)
 def test_self_draw_parent_schema_and_preview_roles(self):
  k=(PHONE/'DualHandKeyboard.java').read_text()
  self.assertIn('new LinkedHashMap<>(KeyboardProbe.text(main',k,'keycap parent must expose the required typography fields')
  self.assertIn('root.setTag(R.id.keyboard_role',k,'root backdrop role absent')
  self.assertIn('bubbleLabel.setTag(R.id.keyboard_role',k,'bubble role absent')
 def test_settings_only_authorized_text(self):
  t=(MAIN/'SettingsActivity.java').read_text()
  self.assertIn('dualHaptics.setText(getString(R.string.keyboard_haptics_description))',t)
  self.assertIn('鍵盤觸覺回饋（各頁按下、滑動與拒絕）',(W/'app/src/main/res/values/strings.xml').read_text())
 def test_font_manifest_and_no_bitmap_factory(self):
  self.assertTrue((W/'app/src/phone/assets/fonts/SUBSET_MANIFEST.json').exists(),'generated subset manifest absent')
  self.assertTrue((MAIN/'KeyboardKeycaps.java').exists(),'factory absent')
  if (MAIN/'KeyboardKeycaps.java').exists(): self.assertNotRegex((MAIN/'KeyboardKeycaps.java').read_text(),r'Bitmap|BlurMaskFilter|setShadowLayer|diamond')
if __name__=='__main__': unittest.main(verbosity=2)
