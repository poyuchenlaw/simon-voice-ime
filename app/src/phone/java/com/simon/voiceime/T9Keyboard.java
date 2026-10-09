package com.simon.voiceime;
import com.simon.voiceime.t9.T9Core;
import android.os.*;import android.view.*;import android.widget.*;import android.graphics.Rect;import android.text.*;import android.text.style.RelativeSizeSpan;
import java.util.*;import java.util.concurrent.*;import org.json.*;
/** Big keys -> Rust FSM -> existing Rime -> a bounded pause-time suggestion. */
public final class T9Keyboard implements T9Page {
 static final float T9_REASSIGN_FRACTION=0.5f;
 static final long T9_DIGIT_HOLD_MS=400;
 private final SimonIMEService service;final T9Core core=new T9Core();
 private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private final Map<Integer,String> characterCache=new HashMap<>();
 private final android.content.SharedPreferences.OnSharedPreferenceChangeListener learningChanged=(prefs,key)->{if(key!=null&&key.startsWith("t9_learning_"))characterCache.clear();};
 private T9Local local;private boolean closed,ready;private int epoch,cursor=-1;private long decodeRevision;
 private KeyboardTouchLayout root;private TextView preview;private LinearLayout sentences,words,characters,keyArea;private final Map<Integer,TextView> keys=new LinkedHashMap<>();
 private final Map<LinearLayout,Integer> rowUsed=new IdentityHashMap<>();
 private final List<Press> presses=new ArrayList<>();private final List<String> top20=new ArrayList<>(),engine20=new ArrayList<>();private String ai="",localSentence="",display="cover",lastCodes="";private final T9Pause pause;private JSONObject aiTelemetry=new JSONObject();
 private static class Press {int key;TextView view;float x,y;long time;boolean ready,digit,cancelled;Runnable hold;}
 public T9Keyboard(SimonIMEService s){service=s;service.getSharedPreferences("simon_ime_prefs",0).registerOnSharedPreferenceChangeListener(learningChanged);pause=new T9Pause(this,s);build();
  worker.execute(()->{try{local=new T9Local(service);ui.post(()->{if(closed)return;ready=true;decode();});}catch(Throwable error){android.util.Log.e("T9","Local decoder unavailable",error);ui.post(()->{if(!closed)preview.setText("九宮格載入失敗，可切回注音");});}});
 }
 public View view(){return root;}
 private int dp(float v){return Math.round(v*service.getResources().getDisplayMetrics().density);}
 private TextView label(String text){TextView v=new TextView(service);v.setText(text);v.setTextSize(21);v.setGravity(Gravity.CENTER);v.setTextColor(service.getResources().getColor(R.color.keyboard_key_text,null));v.setPadding(dp(5),dp(2),dp(5),dp(2));v.setBackgroundResource(R.drawable.keyboard_key);v.setFocusable(false);return v;}
 private LinearLayout row(){LinearLayout r=new LinearLayout(service);r.setOrientation(LinearLayout.HORIZONTAL);r.setBaselineAligned(false);return r;}
 private LinearLayout candidateRow(String tag){HorizontalScrollView scroll=new HorizontalScrollView(service);scroll.setHorizontalScrollBarEnabled(false);LinearLayout r=row();r.setTag(tag);scroll.addView(r,new HorizontalScrollView.LayoutParams(-2,-1));root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(44)));return r;}
 private void build(){
  root=new KeyboardTouchLayout(service,null);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(3),0,dp(3),dp(3));
  preview=label("");preview.setTag("t9-preview");preview.setMinHeight(dp(46));preview.setGravity(Gravity.CENTER_VERTICAL);root.addView(preview,new LinearLayout.LayoutParams(-1,-2));
  sentences=candidateRow("t9-sentences");words=candidateRow("t9-words");characters=candidateRow("t9-characters");keyArea=new LinearLayout(service);keyArea.setOrientation(LinearLayout.VERTICAL);root.addView(keyArea);layoutKeys();
  root.setOnApplyWindowInsetsListener((v,in)->{root.setPadding(dp(3),0,dp(3),dp(3)+in.getSystemWindowInsetBottom());return in;});
  root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol&&r-l>0){boolean wide=(r-l)/service.getResources().getDisplayMetrics().density>=600;if(wide!=!display.equals("cover")){layoutKeys();render();}}});
 }
 private void addKey(LinearLayout row,int k){TextView v=label("");String[] map={"ㄢㄣㄤㄥㄦ","ㄅㄆㄇㄈ","ㄉㄊㄋㄌ","ㄍㄎㄏ","ㄐㄑㄒ","ㄓㄔㄕㄖ","ㄗㄘㄙ","ㄧㄨㄩ","ㄚㄛㄜㄝ","ㄞㄟㄠㄡ","分音節"};
  String text=k==11?"⌫":map[k]+"\n"+(k==10?"*":k);SpannableString span=new SpannableString(text);int at=text.indexOf('\n');if(at>=0)span.setSpan(new RelativeSizeSpan(.55f),at+1,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);v.setText(span);v.setTextSize(k==0?20:22);v.setMaxLines(2);v.setAutoSizeTextTypeUniformWithConfiguration(12,k==0?20:22,1,android.util.TypedValue.COMPLEX_UNIT_SP);v.setTag("key:t9:"+k);v.setContentDescription(k==11?"T9 刪除":k==10?"T9 分音節":"T9 "+k);v.setClickable(true);
  LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(64),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(v,p);keys.put(k,v);
  v.setOnTouchListener((view,event)->touch(k,v,event));
  v.setOnClickListener(view->key(k,false,0,0));
 }
 private void blank(LinearLayout r){r.addView(new View(service),new LinearLayout.LayoutParams(0,dp(64),1));}
 private void layoutKeys(){keys.clear();keyArea.removeAllViews();int width=root.getWidth()>0?root.getWidth():service.getResources().getDisplayMetrics().widthPixels;boolean wide=width/service.getResources().getDisplayMetrics().density>=600;
  display=wide?(service.getResources().getConfiguration().orientation==2?"landscape":"inner"):"cover";
  if(!wide){for(int[] ids:new int[][]{{1,2,3},{4,5,6},{7,8,9},{10,0,11}}){LinearLayout r=row();keyArea.addView(r);for(int k:ids)addKey(r,k);}}
  else {LinearLayout halves=row(),left=new LinearLayout(service),right=new LinearLayout(service);left.setOrientation(1);right.setOrientation(1);keyArea.addView(halves);halves.addView(left,new LinearLayout.LayoutParams(0,-2,4));halves.addView(new View(service),new LinearLayout.LayoutParams(0,dp(200),2));halves.addView(right,new LinearLayout.LayoutParams(0,-2,4));
   for(int[] ids:new int[][]{{1,2,3},{4,5,6},{10,-1,-1}}){LinearLayout r=row();left.addView(r);for(int k:ids)if(k<0)blank(r);else addKey(r,k);}
   for(int[] ids:new int[][]{{7,8,9},{0,-1,-1},{11,-2,-3}}){LinearLayout r=row();right.addView(r);for(int k:ids)if(k==-1)blank(r);else if(k==-2)function(r,"空白",()->commit(false));else if(k==-3)function(r,"↵",()->commit(true));else addKey(r,k);}
  }
  LinearLayout bar=row();keyArea.addView(bar);function(bar,"注",()->service.t9Function("toT9"));function(bar,"EN",()->service.t9Function("toEnglish"));function(bar,"🎤",()->service.t9Function("toVoice"));function(bar,"123",()->service.t9Function("toNumbers"));function(bar,"，",()->{commit(false);service.t9Commit("，");});function(bar,"空白",()->commit(false));function(bar,"↵",()->commit(true));dim();
 }
 private void function(LinearLayout r,String text,Runnable action){TextView v=label(text);v.setTextSize(16);v.setTag("key:t9:function:"+text);v.setOnClickListener(x->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(1),dp(2),dp(1),dp(2));r.addView(v,p);}
 private boolean touch(int k,TextView v,MotionEvent e){
  if(e.getActionMasked()==MotionEvent.ACTION_DOWN){Press p=new Press();p.key=k;p.view=v;p.time=e.getDownTime();p.x=e.getRawX();p.y=e.getRawY();presses.add(p);v.setPressed(true);if(k==11||(core.mask()&(1<<k))!=0)haptic(v,false);
   if(k<10){p.hold=()->{if(!p.ready){p.ready=true;p.digit=true;flushPresses();}};ui.postDelayed(p.hold,T9_DIGIT_HOLD_MS);}return true;}
  if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){for(Press p:new ArrayList<>(presses))if(p.view==v&&p.time==e.getDownTime()){if(p.hold!=null)ui.removeCallbacks(p.hold);p.ready=true;p.cancelled=e.getActionMasked()==MotionEvent.ACTION_CANCEL;break;}v.setPressed(false);flushPresses();return true;}return true;
 }
 private void flushPresses(){while(!presses.isEmpty()&&presses.get(0).ready){Press p=presses.remove(0);if(!p.cancelled){if(p.digit){commit(false);service.t9Commit(Integer.toString(p.key));event("t9_digit_direct",new JSONObject(),p.key,false,false);}else key(p.key,false,p.x,p.y);}}}
 private void haptic(View v,boolean confirm){if(!service.getSharedPreferences("simon_ime_prefs",0).getBoolean("haptic_enabled",true))return;v.performHapticFeedback(confirm?HapticFeedbackConstants.CONFIRM:HapticFeedbackConstants.CLOCK_TICK);}
 void key(int key,boolean reassigned,float x,float y){
  if(closed)return;int mask=core.mask(),original=key;
  if(key==11){if(core.snapshot().length==0)service.t9Function("backspace");else {core.backspace();changed();}return;}
  boolean dimmed=(mask&(1<<key))==0;
  if(dimmed&&x!=0){float best=Float.MAX_VALUE;int nearest=-1;for(Map.Entry<Integer,TextView> item:keys.entrySet()){int k=item.getKey();if(k>10||(mask&(1<<k))==0)continue;View v=item.getValue();int[] at=new int[2];v.getLocationOnScreen(at);float dx=Math.max(Math.max(at[0]-x,0),x-(at[0]+v.getWidth())),dy=Math.max(Math.max(at[1]-y,0),y-(at[1]+v.getHeight()));float dist=(float)Math.hypot(dx,dy);if(dist<=v.getWidth()*T9_REASSIGN_FRACTION&&dist<best){best=dist;nearest=k;}}if(nearest>=0){key=nearest;reassigned=true;}}
  if(dimmed){event("t9_dimmed_press",new JSONObject(),original,true,reassigned);if(!reassigned){haptic(keys.get(original),false);return;}}
  if(reassigned)haptic(keys.get(key),false);
  if(core.push(key)){event(key==10?"t9_boundary_star":"t9_key",new JSONObject(),key,dimmed,reassigned);if(core.closed())haptic(keys.get(key),true);changed();}
 }
 private void changed(){epoch++;ai="";localSentence="";cursor=-1;pause.cancel();dim();decode();}
 private void dim(){int mask=core.mask();for(Map.Entry<Integer,TextView> e:keys.entrySet())e.getValue().setAlpha(e.getKey()==11||(mask&(1<<e.getKey()))!=0?1f:.2f);}
 private void decode(){String codes=core.codes();lastCodes=codes;long rev=++decodeRevision;int requestEpoch=epoch;if(!ready){preview.setText("九宮格載入中");return;}if(codes.isEmpty()){top20.clear();render();return;}
  String context=service.t9Context();List<String> installed=service.t9Vocabulary();
  worker.execute(()->{List<String> decoded=local.decode(codes,context);ui.post(()->{if(closed||rev!=decodeRevision||epoch!=requestEpoch)return;engine20.clear();engine20.addAll(decoded);top20.clear();for(String value:decoded)if(core.legal(value,codes)&&!top20.contains(value))top20.add(value);for(String form:installed)if(core.legal(form,codes)){top20.remove(form);top20.add(0,form);}
   String pick=service.getSharedPreferences("simon_ime_prefs",0).getString("t9_learning_pick_"+codes,"");if(core.legal(pick,codes)&&!pick.isEmpty()){top20.remove(pick);top20.add(0,pick);}if(top20.isEmpty()){StringBuilder b=new StringBuilder();for(String c:codes.split(" ")){String chars=orderedCharacters(Integer.parseInt(c));if(!chars.isEmpty())b.appendCodePoint(chars.codePointAt(0));}String fallback=b.toString();if(core.legal(fallback,codes))top20.add(fallback);}while(top20.size()>20)top20.remove(top20.size()-1);render();if(core.complete()&&!top20.isEmpty())pause.schedule(epoch,codes,new ArrayList<>(top20),display);});});
 }
 List<String> rankedSyllables(int code){
  android.content.SharedPreferences prefs=service.getSharedPreferences("simon_ime_prefs",0);List<String> values=new ArrayList<>();Map<String,Double> scores=new LinkedHashMap<>();long now=System.currentTimeMillis();
  for(String line:T9Core.table().split("\n")){String[] a=line.split("\t");if(a.length!=3||!a[0].equals(Integer.toString(code)))continue;String[] syllables=a[1].split(" "),staticScore=a[2].split(" ");for(int i=0;i<syllables.length;i++){String key="t9_learning_s_"+syllables[i];double count=Double.longBitsToDouble(prefs.getLong(key,0))*Math.pow(.5,Math.max(0,now-prefs.getLong(key+"_time",now))/(30.0*86400000));values.add(syllables[i]);scores.put(syllables[i],Double.parseDouble(staticScore[i])+Math.log1p(count));}}
  values.sort(Comparator.comparingDouble((String value)->-scores.get(value)));return values;
 }
 private String orderedCharacters(int code){String cached=characterCache.get(code);if(cached!=null)return cached;List<String> syllables=rankedSyllables(code);String raw=T9Core.characters(code);List<Integer> chars=new ArrayList<>();raw.codePoints().forEach(chars::add);chars.sort(Comparator.comparing((Integer c)->!CommonCharacters.permits(new String(Character.toChars(c)),false)).thenComparingInt(c->{int at=syllables.indexOf(T9Core.reading(c,code));return at<0?999:at;}));StringBuilder result=new StringBuilder();for(int c:chars)result.appendCodePoint(c);String ordered=result.toString();characterCache.put(code,ordered);return ordered;}
 private String draft(){return top20.isEmpty()?"":top20.get(0);}
 private void chip(LinearLayout r,String text,String description,Runnable choose){int index=rowUsed.getOrDefault(r,0);rowUsed.put(r,index+1);TextView v;if(index<r.getChildCount())v=(TextView)r.getChildAt(index);else {v=label(text);r.addView(v,new LinearLayout.LayoutParams(-2,-1));}if(!v.getText().toString().equals(text))v.setText(text);v.setContentDescription(description);v.setOnClickListener(x->choose.run());v.setVisibility(View.VISIBLE);}
 private void finishRows(){for(LinearLayout r:new LinearLayout[]{sentences,words,characters})for(int i=rowUsed.getOrDefault(r,0);i<r.getChildCount();i++)r.getChildAt(i).setVisibility(View.GONE);}
 private void render(){if(closed)return;preview.setText(draft());rowUsed.put(sentences,0);rowUsed.put(words,0);rowUsed.put(characters,0);if(!ai.isEmpty())chip(sentences,"AI "+ai,"T9 AI "+ai,()->chosen(ai,true));for(String value:top20){String shown=value.equals(draft())&&!localSentence.isEmpty()?localSentence:value;chip(sentences,shown,"T9 句 "+shown,()->chosen(shown,false));}
  if(top20.isEmpty()){finishRows();return;}String[] codes=lastCodes.split(" ");int at=cursor<0?codes.length-1:Math.min(cursor,codes.length-1);int[] cp=draft().codePoints().toArray();if(cp.length!=codes.length){finishRows();return;}
  int from=Math.max(0,at-1),length=Math.min(2,at+1);String wordCodes=String.join(" ",Arrays.copyOfRange(codes,from,from+length));Set<String> wordForms=new LinkedHashSet<>();
  if(length>1){for(String value:top20){int[] chars=value.codePoints().toArray();if(chars.length==cp.length)wordForms.add(new String(chars,from,length));}for(String form:service.t9Vocabulary())if(form.codePointCount(0,form.length())==length&&core.legal(form,wordCodes))wordForms.add(form);}
  for(String segment:wordForms)chip(words,segment,"T9 詞 "+segment,()->{int[] text=draft().codePoints().toArray(),replacement=segment.codePoints().toArray();System.arraycopy(replacement,0,text,from,length);String value=new String(text,0,text.length);top20.remove(value);top20.add(0,value);remember(value);ai="";localSentence="";epoch++;pause.cancel();render();});
  String chars=orderedCharacters(Integer.parseInt(codes[at]));List<String> options=new ArrayList<>();chars.codePoints().forEach(c->options.add(new String(Character.toChars(c))));options.sort(Comparator.comparing(s->!CommonCharacters.permits(s,false)));
  for(String ch:options){if(rowUsed.getOrDefault(characters,0)>=40)break;chip(characters,ch,"T9 字 "+ch,()->{int[] text=draft().codePoints().toArray();text[at]=ch.codePointAt(0);String value=new String(text,0,text.length);top20.remove(value);top20.add(0,value);remember(value);ai="";localSentence="";epoch++;pause.cancel();render();});}
  finishRows();
  preview.setOnTouchListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_UP&&cp.length>0){cursor=Math.min(cp.length-1,Math.max(0,(int)(e.getX()/Math.max(1,v.getWidth())*cp.length)));render();}return true;});
 }
 private void remember(String text){if(!service.t9Allowed())return;service.getSharedPreferences("simon_ime_prefs",0).edit().putString("t9_learning_pick_"+lastCodes,text).apply();}
 private void learn(String text){if(!service.t9Allowed())return;int[] ch=T9Contract.bare(text).codePoints().toArray();String[] codes=lastCodes.split(" ");if(ch.length!=codes.length)return;android.content.SharedPreferences p=service.getSharedPreferences("simon_ime_prefs",0);android.content.SharedPreferences.Editor e=p.edit();long now=System.currentTimeMillis();for(int i=0;i<ch.length;i++){String s=T9Core.reading(ch[i],Integer.parseInt(codes[i]));String key="t9_learning_s_"+s;double count=Double.longBitsToDouble(p.getLong(key,0));long then=p.getLong(key+"_time",now);count=count*Math.pow(.5,Math.max(0,now-then)/(30.0*86400000))+1;e.putLong(key,Double.doubleToLongBits(count)).putLong(key+"_time",now);}e.apply();}
 void chosen(String sentence,boolean fromAi){String text=T9Contract.bare(sentence);if(!core.legal(text,lastCodes))return;remember(text);learn(text);int selected=engine20.indexOf(text);if(selected>=0&&service.t9Allowed())worker.execute(()->local.committed(selected));service.t9Commit(sentence);if(fromAi)try{event("t9_ai",new JSONObject(aiTelemetry.toString()).put("accepted",true),-1,false,false);}catch(JSONException error){android.util.Log.w("T9","AI acceptance metadata",error);}clear();}
 private void commit(boolean enter){if(!draft().isEmpty())chosen(ai.isEmpty()?(localSentence.isEmpty()?draft():localSentence):ai,!ai.isEmpty());else if(core.snapshot().length>0)clear();else service.t9Function(enter?"enter":"space");}
 public void clear(){epoch++;decodeRevision++;pause.cancel();for(Press p:presses)if(p.hold!=null)ui.removeCallbacks(p.hold);presses.clear();core.clear();top20.clear();ai="";localSentence="";lastCodes="";cursor=-1;dim();render();}
 public void reconfigure(){byte[] snapshot=core.snapshot();epoch++;pause.cancel();layoutKeys();if(!core.restore(snapshot))clear();else {render();decode();}}
 public void close(){closed=true;service.getSharedPreferences("simon_ime_prefs",0).unregisterOnSharedPreferenceChangeListener(learningChanged);pause.cancel();ui.removeCallbacksAndMessages(null);worker.execute(()->{if(local!=null)local.close();});worker.shutdown();core.close();}
 void received(int generation,String sentence,String source,double confidence,int illegalPositions,long latency,List<String> alt,String draft,String codes){
  if(closed||generation!=epoch||!service.t9Allowed()||!core.complete()||!codes.equals(lastCodes))return;
  String filtered=T9Contract.filter(draft,alt,sentence);boolean invalid=!T9Contract.bare(sentence.split("\\n",2)[0]).equals(T9Contract.bare(filtered))||!core.legal(T9Contract.bare(filtered),codes);
  if(invalid)illegalPositions=Math.max(illegalPositions,codes.split(" ").length);
  try{event("t9_ai",aiTelemetry=new JSONObject().put("latency_ms",latency).put("source",invalid?"local":source).put("confidence_bucket",Math.min(9,(int)(confidence*10))).put("illegal_positions",illegalPositions).put("accepted",false),-1,false,false);}catch(JSONException e){throw new IllegalStateException(e);}
  if(invalid)return;
  if("local".equals(source)){localSentence=filtered;ai="";}else {ai=filtered;localSentence="";}render();
 }

 List<String> alternatives(String draft,List<String> candidates){int[] cp=draft.codePoints().toArray();List<String> out=new ArrayList<>();int total=0;for(int i=0;i<cp.length;i++){StringBuilder b=new StringBuilder();for(String s:candidates.subList(0,Math.min(T9Pause.T9_ALT_MARGIN,candidates.size()))){int[] c=s.codePoints().toArray();if(c.length==cp.length&&c[i]!=cp[i]&&b.indexOf(new String(Character.toChars(c[i])))<0&&b.codePointCount(0,b.length())<4&&total<60){b.appendCodePoint(c[i]);total++;}}out.add(b.toString());}return out;}
 void event(String name,JSONObject extra,int key,boolean dimmed,boolean reassigned){ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry==null)return;try{extra.put("display",display);if(key>=0)extra.put("key",key).put("fsm_state_class",core.complete()?"complete":"partial").put("dimmed",dimmed).put("reassigned",reassigned);telemetry.record(name,"t9",extra,!service.t9Allowed());}catch(JSONException error){android.util.Log.w("T9","Telemetry metadata invalid",error);}}
}
