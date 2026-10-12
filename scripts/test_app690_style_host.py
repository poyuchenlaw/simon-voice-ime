#!/usr/bin/env python3
"""F shared-fit XML crosscheck. Independent layout inputs, A fitting, no new libraries."""
import argparse,base64,os,pathlib,subprocess,xml.etree.ElementTree as ET,unicodedata
p=argparse.ArgumentParser();p.add_argument('--out',default='out/app690F-host-tests');p.add_argument('--tools',type=pathlib.Path,default=pathlib.Path('/home/simon/.claude/audit/ime_dual_hand_20261011/app690/tools'));p.add_argument('--skip-tools',action='store_true');p.add_argument('--measure-real-grid',action='store_true');a=p.parse_args()
w=pathlib.Path(__file__).resolve().parents[1];out=(w/a.out).resolve();out.mkdir(parents=True,exist_ok=True)
ns='{http://schemas.android.com/apk/res/android}';strings={e.attrib['name']:''.join(e.itertext()) for e in ET.parse(w/'app/src/main/res/values/strings.xml').getroot() if e.tag=='string'}
def attr(e,n,default=''):return e.get(ns+n,default)
def px(value,d,default=0):return int(float(value[:-2])*d+.5) if value.endswith('dp') else default
def measure(e,d):
 h=attr(e,'layout_height');fixed=px(h,d,-1)
 if fixed>=0:return fixed
 children=[c for c in e if attr(c,'visibility')!='gone' and c.tag!='tag']
 if not children:return 0
 heights=[measure(c,d)+px(attr(c,'layout_marginTop'),d)+px(attr(c,'layout_marginBottom'),d) for c in children]
 return (sum(heights) if attr(e,'orientation','vertical')=='vertical' else max(heights))+px(attr(e,'paddingTop'),d)+px(attr(e,'paddingBottom'),d)
def group(text,tag,page):
 plain=text.replace('\ufe0f','');single=len(plain)==1
 if tag.startswith('key:') and len(tag[4:])==1 and not (page=='bopomofo' and text=='，') and not (page in {'english','numbers'} and text=='.'):return 'character'
 if single and unicodedata.category(plain)=='So':return 'icon'
 return 'function'
def layout(e,width,height,d,page,rows):
 tag=attr(e,'tag');text=attr(e,'text');text=strings.get(text[8:],text) if text.startswith('@string/') else text
 if text and e.tag in {'TextView','Button'}:
  rows.append((text,group(text,tag,page),width,height,0 if page=='voice' else 1));return
 children=[c for c in e if c.tag!='tag' and attr(c,'visibility')!='gone']
 if not children:return
 left=px(attr(e,'paddingStart',attr(e,'paddingLeft',attr(e,'padding'))),d);right=px(attr(e,'paddingEnd',attr(e,'paddingRight',attr(e,'padding'))),d);top=px(attr(e,'paddingTop',attr(e,'padding')),d);bottom=px(attr(e,'paddingBottom',attr(e,'padding')),d);width-=left+right;height-=top+bottom
 horizontal=attr(e,'orientation','vertical')=='horizontal'
 if e.tag.endswith('FrameLayout'):
  for c in children:layout(c,px(attr(c,'layout_width'),d,width),px(attr(c,'layout_height'),d,height),d,page,rows)
  return
 major='layout_width' if horizontal else 'layout_height';size=width if horizontal else height
 weights=[float(attr(c,'layout_weight','0')) for c in children]
 margins=[px(attr(c,'layout_marginStart',attr(c,'layout_marginLeft',attr(c,'layout_margin'))),d)+px(attr(c,'layout_marginEnd',attr(c,'layout_marginRight',attr(c,'layout_margin'))),d) if horizontal else px(attr(c,'layout_marginTop'),d)+px(attr(c,'layout_marginBottom'),d) for c in children]
 fixed=[px(attr(c,major),d,0 if attr(c,major)=='0dp' else width if horizontal and attr(c,major)=='match_parent' else height if not horizontal and attr(c,major)=='match_parent' else measure(c,d) if not horizontal else 0) for c in children]
 remain=max(0,size-sum(fixed)-sum(margins));remaining_weight=sum(weights)
 for c,base,weight,margin in zip(children,fixed,weights,margins):
  share=int(remain*weight/remaining_weight) if remaining_weight else 0;remain-=share;remaining_weight-=weight;extent=base+share
  cw=extent if horizontal else px(attr(c,'layout_width'),d,width);ch=px(attr(c,'layout_height'),d,height) if horizontal else extent
  if attr(c,'layout_height')=='wrap_content':ch=measure(c,d)
  layout(c,cw,ch,d,page,rows)
allrows=[]
for page,file in [('bopomofo','keyboard_bopomofo.xml'),('english','keyboard_english.xml'),('numbers','keyboard_numbers.xml'),('voice','keyboard_view.xml')]:
 root=ET.parse(w/'app/src/main/res/layout'/file).getroot()
 if page=='voice':root=next(e for e in root.iter() if attr(e,'id')=='@+id/voiceKeyboard')
 for width in [320,360,400,600,800]:
  for d in [1.3,1.5,2,2.625,3]:
   for fs in [1,1.3]:
    rows=[];layout(root,int(width*d+.5),measure(root,d),d,page,rows)
    if not rows:raise AssertionError('no actual XML labels '+page)
    for text,cls,cw,ch,inset in rows:allrows.append('\t'.join([f'{page}:{width}:{d}:{fs}',base64.b64encode(text.encode()).decode(),cls,str(cw),str(ch),str(inset)]))
(out/'layouts.tsv').write_text('\n'.join(allrows)+'\n')
subprocess.run(['python3','scripts/test_app688_host.py','--out',str(out)],cwd=w,check=True)
cp=':'.join([str(out),*(str(w/'out/test-libs'/n) for n in ['junit-4.13.2.jar','hamcrest-core-1.3.jar'])])
subprocess.run(['javac','-encoding','UTF-8','-cp',cp,'-d',str(out),str(w/'scripts/app690_host/App690LayoutHarness.java'),str(w/'scripts/app690_host/App690PixelSelfTest.java'),str(w/'app/src/androidTest/java/com/simon/voiceime/App690FPixels.java')],cwd=w,check=True)
# STOP1 §1 keeps product XML measurements separate from F tool regression.
checks=[('App690PixelSelfTest',[])]
if a.measure_real_grid:checks.append(('App690LayoutHarness',[str(out/'layouts.tsv')]))
for main,args in checks:subprocess.run(['java','-ea','-cp',cp,'com.simon.voiceime.'+main,*args],cwd=w,check=True)
if not a.skip_tools:
 for tool in ['check_scope_690.py','scan_style_literals_690.py','check_style_690.py','check_telemetry_690.py']:subprocess.run(['python3',str(a.tools/tool),'--self-test'],cwd=w,check=True)
