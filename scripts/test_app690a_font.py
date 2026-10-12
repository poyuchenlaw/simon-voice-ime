#!/usr/bin/env python3
import pathlib, hashlib, json, subprocess, unittest, sys, io
from fontTools.ttLib import TTFont
W=pathlib.Path(__file__).resolve().parents[1];F=W/'app/src/phone/assets/fonts'
def missing(required,font,fallback): return set(required)-set(map(chr,font.getBestCmap()))-set(fallback)
def outline(font,name):
 g=font['glyf'][name]
 if g.isComposite():
  from fontTools.pens.recordingPen import DecomposingRecordingPen
  p=DecomposingRecordingPen(font.getGlyphSet());font.getGlyphSet()[name].draw(p);return p.value
 if g.numberOfContours==0:return ()
 coords,end,flags=g.getCoordinates(font['glyf']);return (list(coords),list(end),list(flags))
class FontTest(unittest.TestCase):
 def test_generated_required_coverage_and_injection(self):
  self.assertTrue((F/'SUBSET_MANIFEST.json').exists(),'690 generated manifest absent')
  m=json.loads((F/'SUBSET_MANIFEST.json').read_text());font=TTFont(F/'NotoSansTC-Medium-subset.ttf')
  self.assertEqual(set(),missing(m['characters'],font,m['source_missing_characters']))
  self.assertEqual({'𪚥'},missing(m['characters']+'𪚥',font,m['source_missing_characters']))
  self.assertEqual(hashlib.sha256((F/'NotoSansTC-Medium-subset.ttf').read_bytes()).hexdigest(),m['output_sha256'])
  self.assertLessEqual((F/'NotoSansTC-Medium-subset.ttf').stat().st_size,300*1024)
  print('FONT_COUNT',m['character_count'],'FONT_BYTES',(F/'NotoSansTC-Medium-subset.ttf').stat().st_size)
 def test_original_glyph_contours_metrics_and_pair_positions(self):
  new=TTFont(F/'NotoSansTC-Medium-subset.ttf');old=TTFont(io.BytesIO(subprocess.check_output(['git','show','7df786ee26292ed829d72247395872ed6011579f:app/src/phone/assets/fonts/NotoSansTC-Medium-subset.ttf'],cwd=W)))
  if not (F/'SUBSET_MANIFEST.json').exists(): self.fail('new subset absent')
  oc,nc=old.getBestCmap(),new.getBestCmap()
  for cp,name in oc.items():
   self.assertIn(cp,nc);self.assertEqual(outline(old,name),outline(new,nc[cp]),chr(cp));self.assertEqual(old['hmtx'][name],new['hmtx'][nc[cp]],chr(cp))
  # PairPos semantic expansion to original glyph names, not renumbered glyph ids.
  def pairs(font,cmap):
   result={};names={cmap[cp] for cp in oc};gpos=font.get('GPOS')
   if gpos is None:return result
   def val(v):return None if v is None else tuple(sorted(vars(v).items()))
   for lookup in gpos.table.LookupList.Lookup:
    for table in lookup.SubTable:
     if lookup.LookupType==9:table=table.ExtSubTable
     if not hasattr(table,'PairSet') and not hasattr(table,'Class1Record'):continue
     for a in names:
      if a not in table.Coverage.glyphs:continue
      if table.Format==1:
       for pair in table.PairSet[table.Coverage.glyphs.index(a)].PairValueRecord:
        if pair.SecondGlyph in names:result[a,pair.SecondGlyph]=val(pair.Value1),val(pair.Value2)
      else:
       for b in names:
        row=table.Class1Record[table.ClassDef1.classDefs.get(a,0)].Class2Record[table.ClassDef2.classDefs.get(b,0)]
        if row.Value1 or row.Value2:result[a,b]=val(row.Value1),val(row.Value2)
   byname={v:chr(k) for k,v in cmap.items() if k in oc};return {(byname[a],byname[b]):v for (a,b),v in result.items()}
  self.assertEqual(pairs(old,oc),pairs(new,nc));print('A2-7 original cmap',len(oc),'glyphs + hmtx + GPOS pairs equal')
if __name__=='__main__':
 if sys.argv[1:]==['--inject-missing']:
  m=json.loads((F/'SUBSET_MANIFEST.json').read_text());gaps=missing(m['characters']+'𪚥',TTFont(F/'NotoSansTC-Medium-subset.ttf'),m['source_missing_characters']);print('INJECTED_COVERAGE_MISSING',sorted(gaps));raise SystemExit(1 if gaps else 0)
 unittest.main(verbosity=2)

