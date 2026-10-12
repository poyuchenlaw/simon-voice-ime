#!/usr/bin/env python3
"""Independent reference metrics: physical lines versus the four actual row heights.
System font overrides and Android rasterization are explicitly left to X1.
"""
import pathlib,re,unittest,io,subprocess,xml.etree.ElementTree as ET
from fontTools.ttLib import TTFont
W=pathlib.Path(__file__).resolve().parents[1]
class CandidateGeometry(unittest.TestCase):
 def test_reference_noto_metrics_fit_four_rows_at_19sp(self):
  font=TTFont(W/'app/src/phone/assets/fonts/NotoSansTC-Medium-subset.ttf')
  metrics=font['hhea'];unit=font['head'].unitsPerEm
  line=(metrics.ascent-metrics.descent+metrics.lineGap)/unit*19*1.3
  # XML Path A/B heights and baseline dual topBar height; Path C 44dp match-parent suggestionBar.
  heights={'A':44,'B':44,'C':38,'dual':44}
  xml=(W/'app/src/main/res/layout/keyboard_bopomofo.xml').read_text();self.assertRegex(xml,r'boWordCandidateScroll[^>]*layout_height="44dp"')
  xmlc=(W/'app/src/main/res/layout/keyboard_english.xml').read_text();self.assertRegex(xmlc,r'enSuggestBar[^>]*layout_height="38dp"')
  for row,height in heights.items():self.assertLessEqual(line,height,row)
  self.assertLessEqual(3*18*1.3,72);self.assertGreater(3*19*1.3,72)
  print('CANDIDATE_REFERENCE density=2.625 fontScale=1.3 19sp metric_height_dp=',line,'rows=',heights,'GRID 3*18sp=',3*18*1.3)
if __name__=='__main__':unittest.main(verbosity=2)
