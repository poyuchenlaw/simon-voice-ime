from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
ns='{http://schemas.android.com/apk/res/android}'
manifest=ET.parse(root/'app/src/phone/AndroidManifest.xml')
services=manifest.findall('.//service')
assert any(s.get(ns+'name')=='.LineContextAccessibilityService' and s.get(ns+'permission')=='android.permission.BIND_ACCESSIBILITY_SERVICE' for s in services), 'LINE accessibility service missing from application list'
src=(root/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
assert '"ghostwriter"' in src and '"chat_history"' in src, 'LINE REPLACE lacks ghostwriter request contract'
assert 'sendLineGhostwriterAudio(wavData, myGen)' in src, 'LINE voice still reaches destructive replace route'
print('ghostwriter manifest and request wiring PASS (source contract only; not device behavior)')
collector=(root/'app/src/main/java/com/simon/voiceime/LineContextAccessibilityService.java').read_text()
assert 'window.isActive()' not in collector and 'window.isFocused()' not in collector, 'IME focus must not exclude unique visible LINE window'
assert 'root!=null' in collector and 'candidate.isVisibleToUser()' in collector, 'ambiguous visible LINE window must be rejected'
assert 'AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED' in collector, 'normal LINE redraw must not invalidate epoch'
assert 'titleNoise(text)' in collector, 'toolbar labels cannot be used as chat title'
print('collector compatibility source contract PASS (device layout unverified)')

assert not any(s.get(ns+'name')=='.LineContextAccessibilityService' for s in ET.parse(root/'app/src/main/AndroidManifest.xml').findall('.//service')), 'LINE service must not be advertised to watch'
settings=(root/'app/src/main/java/com/simon/voiceime/SettingsActivity.java').read_text()
assert 'Intent.EXTRA_COMPONENT_NAME' in settings, 'Android detail authorization uses Intent.EXTRA_COMPONENT_NAME'
assert '本機對話脈絡無障礙設定' in settings, 'existing local-context authorization entry must remain'
assert 'sendAiCommandAudio(wavData,"",gen)' in src, 'ghostwriter must not inherit unrelated clipboard context'
assert 'freshLine.contentCurrent()' in src, 'fresh-to-commit content changes must reject delivery'
assert 'IME_ACTION_SEARCH' in src, 'LINE search editor must be refused before recording'

assert "catch(android.content.ActivityNotFoundException | SecurityException unavailable)" in settings, "protected Android details entry must fall back on SecurityException"
