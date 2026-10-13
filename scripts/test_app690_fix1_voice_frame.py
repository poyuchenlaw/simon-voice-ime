#!/usr/bin/env python3
"""Check the selected frame against the independently measured X1 size sweep."""
from pathlib import Path
import argparse,json,re
p=argparse.ArgumentParser();p.add_argument('measurement',type=Path);a=p.parse_args()
w=Path(__file__).resolve().parents[1];source=(w/'app/src/main/java/com/simon/voiceime/SimonIMEService.java').read_text()
sp=float(re.search(r'VOICE_MIC_ICON_SP\s*=\s*([\d.]+)',source)[1]);rows=json.loads(a.measurement.read_text())
for icon,baseline in [('🎤',91),('⏹',87)]:
 q=next(q for q in rows if q['sp']==sp and q['text']==icon)
 print(icon,'selected_sp=',sp,'visible_px=',q['visible_px'],'baseline_px=',baseline)
 assert q['visible_px']>=baseline and not q['clipped'], 'selected frame must preserve baseline visible ink'
print('FIX1 voice frame: both icons preserve baseline, 0 clipping in calibrated reference')
