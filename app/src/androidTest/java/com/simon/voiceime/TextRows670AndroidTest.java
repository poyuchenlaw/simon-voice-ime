package com.simon.voiceime;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import java.io.File;
import java.util.*;

/** Actual emulator window and physical taps; no direct controller edits. */
public class TextRows670AndroidTest extends TextRows669AndroidTest {
    LinearLayout words,characters;HorizontalScrollView wordScroll,charScroll;
    final List<JSONObject> rawDrawFrames=new java.util.concurrent.CopyOnWriteArrayList<>();
    final List<JSONObject> injections=new ArrayList<>();
    final java.util.concurrent.ConcurrentLinkedQueue<String> durableDraws=new java.util.concurrent.ConcurrentLinkedQueue<>();
    volatile boolean profiling;Thread profiler;
    ViewTreeObserver.OnPreDrawListener matrixPreObserver;JSONObject[] matrixFrameSlot;
    int id(String name){return inst.getTargetContext().getResources().getIdentifier(name,"id","com.simon.voiceime");}
    @Override void bindWindow()throws Exception {
        Class<?> cls=Class.forName("android.view.WindowManagerGlobal");Object wm=cls.getDeclaredMethod("getInstance").invoke(null);
        java.lang.reflect.Field field=cls.getDeclaredField("mViews");field.setAccessible(true);
        for(Object obj:(List<?>)field.get(wm)){
            View v=(View)obj;View p=v.findViewById(id("boStreamPreview"));
            if(!(p instanceof TextView)||!p.isShown())continue;
            root=v;row1=(TextView)p;row2=v.findViewById(id("boPhoneticPreview"));row3=v.findViewById(id("boCandidateBar"));keyboard=v.findViewById(id("bopomofoKeyboard"));
            words=v.findViewById(id("boWordCandidateItems"));characters=v.findViewById(id("boCandidateItems"));wordScroll=v.findViewById(id("boWordCandidateScroll"));charScroll=v.findViewById(id("boCandidateScroll"));return;
        }
        throw new AssertionError("actual keyboard window unavailable");
    }
    @Override JSONObject geometry()throws Exception {
        View viewport=(View)row1.getParent();android.text.Layout layout=row1.getLayout();int end=row1.getText().length(),line=layout.getLineForOffset(end);
        JSONArray lines=new JSONArray();String text=row1.getText().toString();for(int cp=0;cp<text.codePointCount(0,text.length());cp++)lines.put(layout.getLineForOffset(text.offsetByCodePoints(0,cp)));
        View key=keyboard.findViewWithTag("key:ㄐ");int[] at=new int[2],rootAt=new int[2];key.getLocationOnScreen(at);root.getLocationOnScreen(rootAt);
        return new JSONObject().put("uptime_ms",SystemClock.uptimeMillis()).put("key_index",keyIndex)
            .put("chars",text.codePointCount(0,text.length())).put("lines",lines).put("line_count",layout.getLineCount())
            .put("row1_scroll",viewport.getScrollX()).put("row1_vertical",viewport.getScrollY()).put("word_scroll",wordScroll.getScrollX()).put("char_scroll",charScroll.getScrollX())
            .put("font_px",row1.getTextSize()).put("row1_height",viewport.getHeight()).put("row2_height",wordScroll.getHeight()).put("row3_height",charScroll.getHeight())
            .put("caret_top",row1.getTotalPaddingTop()+layout.getLineTop(line)).put("caret_bottom",row1.getTotalPaddingTop()+layout.getLineBottom(line))
            .put("key_x",at[0]).put("key_y",at[1]).put("key_width",key.getWidth()).put("key_height",key.getHeight())
            .put("root_y",rootAt[1]).put("root_height",root.getHeight()).put("root_screen_bottom",rootAt[1]+root.getHeight()).put("word_candidates",words.getChildCount()).put("character_candidates",characters.getChildCount());
    }
    int internalGlyphs(View v){
        if(!v.isShown())return 0;Object tag=v.getTag();if(tag instanceof String&&((String)tag).startsWith("key:"))return 0;
        int count=0;if(v instanceof TextView){String t=((TextView)v).getText().toString();for(int cp:t.codePoints().toArray())if(cp>=0x3105&&cp<=0x312f||"ˊˇˋ˙ˉ".indexOf(cp)>=0)count++;}
        if(v instanceof ViewGroup)for(int n=0;n<((ViewGroup)v).getChildCount();n++)count+=internalGlyphs(((ViewGroup)v).getChildAt(n));return count;
    }
    JSONObject structure(View v,String inherited)throws Exception {
        String name="";try {if(v.getId()!=View.NO_ID)name=v.getResources().getResourceEntryName(v.getId());}catch(android.content.res.Resources.NotFoundException ignored){}
        String row=inherited;if(v==row1)row="preview";else if(v==words)row="words";else if(v==characters)row="characters";
        Object tag=v.getTag();boolean key=tag instanceof String&&((String)tag).startsWith("key:");
        int[] at=new int[2];v.getLocationOnScreen(at);Rect visible=new Rect();boolean intersects=v.getGlobalVisibleRect(visible);
        JSONObject node=new JSONObject().put("id",name).put("class",v.getClass().getName()).put("row",row).put("shown",v.isShown()).put("key",key)
            .put("x",at[0]).put("y",at[1]).put("width",v.getWidth()).put("height",v.getHeight()).put("visible",intersects).put("clip",visible.toShortString());
        if(v instanceof TextView){TextView tv=(TextView)v;String text=tv.getText().toString();android.text.Layout layout=tv.getLayout();JSONArray glyphs=new JSONArray();
            for(int off=0;off<text.length();){int cp=text.codePointAt(off),line=layout==null?-1:layout.getLineForOffset(off);JSONObject glyph=new JSONObject().put("character",new String(Character.toChars(cp))).put("codepoint",cp).put("utf16",off).put("line",line).put("zhuyin",cp>=0x3105&&cp<=0x312f||"ˊˇˋ˙ˉ".indexOf(cp)>=0);
                if(layout!=null)glyph.put("x",at[0]+tv.getTotalPaddingLeft()+layout.getPrimaryHorizontal(off)-tv.getScrollX()).put("top",at[1]+tv.getTotalPaddingTop()+layout.getLineTop(line)-tv.getScrollY()).put("bottom",at[1]+tv.getTotalPaddingTop()+layout.getLineBottom(line)-tv.getScrollY());
                glyphs.put(glyph);off+=Character.charCount(cp);
            }node.put("text",text).put("glyphs",glyphs);
        }
        JSONArray children=new JSONArray();if(v instanceof ViewGroup)for(int n=0;n<((ViewGroup)v).getChildCount();n++)children.put(structure(((ViewGroup)v).getChildAt(n),row));return node.put("children",children);
    }
    void captureStructure(String file)throws Exception {
        final JSONObject[] value={null};inst.runOnMainSync(()->{try{value[0]=structure(keyboard,"keyboard");}catch(Exception e){throw new RuntimeException(e);}});
        assertNotNull("in-process hierarchy is present",value[0]);save(file,value[0].toString());
    }
    void bind() {inst.runOnMainSync(()->{try{bindWindow();}catch(Exception e){throw new RuntimeException(e);}});}
    void begin()throws Exception {
        ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();startEndpoint();bind();
        profiling=true;Thread main=android.os.Looper.getMainLooper().getThread();
        profiler=new Thread(()->{try(java.io.FileWriter traces=new java.io.FileWriter(new File(out,"perf-main.jsonl"));java.io.FileWriter draws=new java.io.FileWriter(new File(out,"perf-draws.jsonl"))){
            while(profiling){StringBuilder stack=new StringBuilder();for(StackTraceElement frame:main.getStackTrace())stack.append(frame).append("\n");
                traces.write(new JSONObject().put("uptime_ms",SystemClock.uptimeMillis()).put("key_index",keyIndex).put("state",main.getState().toString()).put("stack",stack.toString()).toString()+"\n");traces.flush();
                for(String line;(line=durableDraws.poll())!=null;)draws.write(line+"\n");draws.flush();Thread.sleep(300);
            }
        }catch(Exception e){error=e;}},"MatrixPerformanceSampler");profiler.start();
    }
    void persistDraws()throws Exception {
        if(out==null)return;
        final String[] data=new String[3];
        inst.runOnMainSync(()->{StringBuilder a=new StringBuilder(),b=new StringBuilder();for(JSONObject f:frames)a.append(f).append("\n");for(JSONObject f:rawDrawFrames)b.append(f).append("\n");data[0]=a.toString();data[1]=b.toString();data[2]=new JSONArray(injections).toString();});
        save("frames.jsonl",data[0]);save("raw-draw-frames.jsonl",data[1]);save("injections.json",data[2]);
    }
    @Override protected void tearDown()throws Exception {
        persistDraws();
        profiling=false;if(profiler!=null)profiler.join(3000);
        stop=true;if(endpoint!=null)endpoint.close();if(server!=null)server.join(5000);
        if(matrixPreObserver!=null&&root!=null)inst.runOnMainSync(()->root.getViewTreeObserver().removeOnPreDrawListener(matrixPreObserver));
        if(observer!=null&&root!=null)inst.runOnMainSync(()->{if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnDrawListener(observer);});
        super.tearDown();
    }
    void awaitStableWindow()throws Exception {
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        JSONArray trace=new JSONArray();final String[] previous={null};final int[] stable={0};
        Runnable poll=new Runnable(){public void run(){
            View key=keyboard.findViewWithTag("key:ㄐ");int[] at=new int[2],window=new int[2];key.getLocationOnScreen(at);root.getLocationOnScreen(window);
            String signature=window[1]+":"+root.getHeight()+":"+at[0]+":"+at[1]+":"+key.getWidth()+":"+key.getHeight();
            trace.put(signature);stable[0]=!root.isLayoutRequested()&&row1.getLayout()!=null&&signature.equals(previous[0])?stable[0]+1:0;previous[0]=signature;
            if(stable[0]>=3)ready.countDown();else root.postOnAnimation(this);
        }};
        inst.runOnMainSync(()->root.postOnAnimation(poll));
        boolean settled=ready.await(5,java.util.concurrent.TimeUnit.SECONDS);
        inst.runOnMainSync(()->root.removeCallbacks(poll));save("startup-layout.json",trace.toString());
        assertTrue("keyboard opening settled before typing",settled);
    }
    void tapRhythm(Rect r)throws Exception {
        long t=SystemClock.uptimeMillis();MotionEvent d=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0),u=MotionEvent.obtain(t,t+20,MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0);
        assertTrue(ui.injectInputEvent(d,false)&&ui.injectInputEvent(u,true));d.recycle();u.recycle();
        injections.add(new JSONObject().put("key_index",keyIndex).put("down_ms",t).put("return_ms",SystemClock.uptimeMillis()).put("x",r.centerX()).put("y",r.centerY()));
        // The recorded rhythm already includes pauses. waitForIdleSync here
        // adds an unrequested idle delay to every key, hiding rapid typing.
    }
    public void testRefreshTelemetry()throws Exception {
        begin();inst.getTargetContext().getSharedPreferences("simon_ime_prefs",0).edit().putBoolean("ime_auto_upload",true).commit();typeToday();
        File spool=new File(inst.getTargetContext().getFilesDir(),"ime-diagnostics.jsonl");String text="";long end=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<end){if(spool.exists())text=new String(java.nio.file.Files.readAllBytes(spool.toPath()),java.nio.charset.StandardCharsets.UTF_8);if(text.contains("text_candidates_refresh"))break;Thread.sleep(50);}
        boolean found=false;for(String line:text.split("\n"))if(!line.isEmpty()){JSONObject event=new JSONObject(line);if("text_candidates_refresh".equals(event.optString("step"))){assertEquals("",event.getString("key"));assertTrue(event.isNull("key_to_candidate_ms"));assertEquals(100,event.getInt("debounce_ms"));assertTrue(event.getBoolean("ok"));found=true;}}
        assertTrue("actual refresh callback must persist compatible metadata",found);save("refresh-telemetry.jsonl",text);
    }
    public void testThreeRowsRhythm()throws Exception {
        begin();awaitStableWindow();assertNotNull("word row must exist",words);assertTrue("word row visible",wordScroll.isShown());assertFalse("phonetic row hidden",row2.isShown());
        int length=Integer.parseInt(InstrumentationRegistry.getArguments().getString("length","15"));
        int[] cadence={46,444,86,329,1145,89,55,66,131,197,232,88,444,881,53,191,87,147,753,198,921,170,276,158,286,425,34,504,219,257,202,804,149,148};
        String[] base={"ㄐㄧㄣ ","ㄊㄧㄢ ","ㄨㄛˇ","ㄇㄣ˙","ㄧˋ","ㄑㄧˇ","ㄊㄠˇ","ㄌㄨㄣˋ","ㄐㄧˋ","ㄏㄨㄚˋ"};
        Map<String,Rect> keys=new HashMap<>();JSONArray positions=new JSONArray();
        for(String s:base)for(int j=0;j<s.length();j++){String k=s.charAt(j)==' '?"空白":s.substring(j,j+1);if(!keys.containsKey(k)){
            Rect accessible=new Rect();await(k).getBoundsInScreen(accessible);Rect physical=new Rect();
            inst.runOnMainSync(()->{View key=keyboard.findViewWithTag("key:"+("空白".equals(k)?"space":k));assertNotNull("actual tagged key "+k,key);int[] at=new int[2];key.getLocationOnScreen(at);physical.set(at[0],at[1],at[0]+key.getWidth(),at[1]+key.getHeight());});
            positions.put(new JSONObject().put("key",k).put("accessibility",accessible.toShortString()).put("physical",physical.toShortString()));keys.put(k,physical);
        }}save("initial-key-bounds.json",positions.toString());
        inst.runOnMainSync(()->{
            assertTrue("frame-commit sampling requires hardware rendering",root.isHardwareAccelerated());
            matrixPreObserver=()->{
                final JSONObject[] slot=new JSONObject[1];matrixFrameSlot=slot;
                root.getViewTreeObserver().registerFrameCommitCallback(()->root.post(()->{
                    JSONObject snapshot=slot[0];if(snapshot==null)return;
                    frames.add(snapshot);
                    try{assertEquals("no internal glyph outside keys",0,snapshot.getInt("internal_glyph_count"));}catch(Throwable e){error=e;}
                }));return true;
            };root.getViewTreeObserver().addOnPreDrawListener(matrixPreObserver);
            observer=()->{
                try{
                    JSONObject snapshot=geometry();int glyphs=internalGlyphs(keyboard);rawDrawFrames.add(snapshot);durableDraws.add(snapshot.toString());
                    // Pre-draw registered this frame's callback before Android captures it.
                    snapshot.put("internal_glyph_count",glyphs);
                    if(matrixFrameSlot!=null)matrixFrameSlot[0]=snapshot;
                }catch(Throwable e){error=e;}

            };root.getViewTreeObserver().addOnDrawListener(observer);
        });
        captureStructure("structure-before.json");
        int step=0;for(int n=0;n<length;n++){String s=base[n%base.length];for(int j=0;j<s.length();j++){keyIndex=step;tapRhythm(keys.get(s.charAt(j)==' '?"空白":s.substring(j,j+1)));Thread.sleep(cadence[step++%cadence.length]);}}
        Thread.sleep(1000);inst.waitForIdleSync();save("injections.json",new JSONArray(injections).toString());screenshot("three-rows-final");persistDraws();assertNull("draw invariant",error);
        final String[] shown={null};inst.runOnMainSync(()->shown[0]=row1.getText().toString());assertEquals("full sentence visible",length,shown[0].codePointCount(0,shown[0].length()));
        StringBuilder draws=new StringBuilder();for(JSONObject f:rawDrawFrames)draws.append(f).append("\n");save("raw-draw-frames.jsonl",draws.toString());
        assertFalse("committed render frames captured",frames.isEmpty());
        StringBuilder rawFrames=new StringBuilder();for(JSONObject f:frames)rawFrames.append(f).append("\n");save("frames.jsonl",rawFrames.toString());
        StringBuilder data=new StringBuilder();int[] heights=null;float font=-1;int[] keyPosition=null;int lastKey=-2;JSONArray prior=null;Map<Integer,Integer> changes=new HashMap<>();int maxMoves=0;
        for(JSONObject f:frames){
            assertEquals("no row1 horizontal scrolling",0,f.getInt("row1_scroll"));
            int[] h={f.getInt("row2_height"),f.getInt("row3_height")};if(heights==null)heights=h;else assertTrue("fixed candidate row heights",Arrays.equals(heights,h));
            int[] position={f.getInt("key_x"),f.getInt("key_y"),f.getInt("key_width"),f.getInt("key_height")};if(keyPosition==null)keyPosition=position;else assertTrue("keys never move while preview grows",Arrays.equals(keyPosition,position));
            if(font<0)font=(float)f.getDouble("font_px");else assertEquals("stable preview font",font,(float)f.getDouble("font_px"),.01f);
            int key=f.getInt("key_index");if(key!=lastKey){changes.clear();lastKey=key;}
            JSONArray now=f.getJSONArray("lines");if(prior!=null)for(int cp=0;cp<Math.min(now.length(),prior.length());cp++)if(now.getInt(cp)!=prior.getInt(cp)){int n=changes.getOrDefault(cp,0)+1;changes.put(cp,n);maxMoves=Math.max(maxMoves,n);assertTrue("at most one line move per character per key",n<=1);}prior=now;
            assertTrue("cursor line top visible",f.getInt("caret_top")>=f.getInt("row1_vertical"));
            assertTrue("cursor line bottom fully visible",f.getInt("caret_bottom")<=f.getInt("row1_vertical")+f.getInt("row1_height"));
            data.append(f).append('\n');
        }
        for(JSONObject f:rawDrawFrames){int[] position={f.getInt("key_x"),f.getInt("key_y"),f.getInt("key_width"),f.getInt("key_height")};assertTrue("raw draw keys also never move while preview grows",Arrays.equals(keyPosition,position));}
        save("frames.jsonl",data.toString());
        for(Map.Entry<String,Rect> k:keys.entrySet()){Rect after=new Rect();await(k.getKey()).getBoundsInScreen(after);assertEquals("all key positions identical "+k.getKey(),k.getValue(),after);}
        save("three-rows-result.json",new JSONObject().put("characters",length).put("draws",frames.size()).put("max_line_moves_per_key",maxMoves).put("internal_glyphs",0).put("word_candidates",words.getChildCount()).put("character_candidates",characters.getChildCount()).put("memory",shell("dumpsys meminfo com.simon.voiceime")).toString());
        captureStructure("structure-after.json");
        tap("↵");Thread.sleep(300);String committed=String.valueOf(await("test_input").getText());assertEquals("Enter sends shown text",shown[0],committed);
        assertFalse("commit zero phonetic",committed.codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||"ˊˇˋ˙ˉ".indexOf(cp)>=0));
        save("commit-result.json",new JSONObject().put("shown",shown[0]).put("committed",committed).put("zero_zhuyin",true).toString());
    }
    public void testFirstKeyKeepsWindowFrame()throws Exception {
        begin();awaitStableWindow();final JSONObject[] before={null},after={null};
        inst.runOnMainSync(()->{try{before[0]=geometry();}catch(Exception e){throw new RuntimeException(e);}});
        tap("ㄐ");Thread.sleep(400);inst.waitForIdleSync();
        inst.runOnMainSync(()->{try{after[0]=geometry();}catch(Exception e){throw new RuntimeException(e);}});
        save("first-key-window.json",new JSONObject().put("before",before[0]).put("after",after[0]).toString());
        assertEquals("first provisional character must not resize input window",before[0].getInt("root_height"),after[0].getInt("root_height"));
        assertEquals("first provisional character must not relocate input window",before[0].getInt("root_y"),after[0].getInt("root_y"));
    }
    public void testWrapContract()throws Exception {
        begin();assertTrue("Amendment2 row1 vertical viewport",row1.getParent() instanceof ScrollView);
        assertFalse("row1 never scrolls horizontally",row1.getParent() instanceof HorizontalScrollView);
        for(int n=0;n<45;n++)for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白"})tap(k);
        Thread.sleep(300);
        inst.runOnMainSync(()->{
            assertTrue("sentence wraps",row1.getLayout().getLineCount()>1);
            assertEquals("zero horizontal offset",0,((View)row1.getParent()).getScrollX());
            assertEquals("45 text characters",45,row1.getText().toString().codePointCount(0,row1.getText().length()));
        });
    }
    public void testHidePreservesComposition()throws Exception {
        begin();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白","ㄊ","ㄧ","ㄢ","空白"})tap(k);
        final String[] shown={null};inst.runOnMainSync(()->shown[0]=row1.getText().toString());shell("input keyevent BACK");Thread.sleep(500);tap("test_input");await("ㄗ");bind();
        inst.runOnMainSync(()->assertEquals("hide retains composition",shown[0],row1.getText().toString()));
        tap("↵");assertEquals("hide/reopen Enter must not duplicate preedit",shown[0],String.valueOf(await("test_input").getText()));
    }
    void swipe(HorizontalScrollView scroll,int distance)throws Exception {
        int[] at=new int[2];inst.runOnMainSync(()->scroll.getLocationOnScreen(at));
        float x=at[0]+scroll.getWidth()/2f,y=at[1]+scroll.getHeight()/2f;long t=SystemClock.uptimeMillis();
        android.view.MotionEvent d=android.view.MotionEvent.obtain(t,t,0,x,y,0);ui.injectInputEvent(d,true);d.recycle();
        for(int step=1;step<=8;step++){android.view.MotionEvent m=android.view.MotionEvent.obtain(t,t+step*25,2,x-distance*step/8f,y,0);ui.injectInputEvent(m,true);m.recycle();}
        android.view.MotionEvent u=android.view.MotionEvent.obtain(t,t+225,1,x-distance,y,0);ui.injectInputEvent(u,true);u.recycle();Thread.sleep(250);
    }
    void revealTenth(LinearLayout items,HorizontalScrollView scroll)throws Exception {revealIndex(items,scroll,9);}
    void awaitCandidateCount(LinearLayout items,int count)throws Exception {
        long end=SystemClock.uptimeMillis()+5000;final boolean[] ready={false};
        while(SystemClock.uptimeMillis()<end){inst.runOnMainSync(()->{
            ready[0]=items.getChildCount()>=count;
            for(int n=0;n<items.getChildCount()&&ready[0];n++){
                View item=items.getChildAt(n);ready[0]=item.isEnabled();Object tag=item.getTag();
                if(tag instanceof ZhuyinInputController.TextChoice){ZhuyinInputController.TextChoice choice=(ZhuyinInputController.TextChoice)tag;ready[0]&=choice.keys.equals(actualController().sentenceKeys())&&choice.witness.equals(actualController().previewText());}
            }
        });if(ready[0])return;Thread.sleep(50);}
        assertTrue("background menu must become available within 5 seconds",ready[0]);
    }
    void revealIndex(LinearLayout items,HorizontalScrollView scroll,int index)throws Exception {
        awaitCandidateCount(items,index+1);
        assertTrue("requested actual candidate exists",items.getChildCount()>index);int[] viewAt=new int[2],scrollAt=new int[2];
        swipe(scroll,Math.min(300,scroll.getWidth()/3));
        int priorX=Integer.MIN_VALUE,priorScroll=Integer.MIN_VALUE,stable=0;
        for(int n=0;n<60;n++){
            // The stock ScrollView consumes a down event to stop an active fling.
            // Wait for a measured settled viewport before testing a candidate tap.
            View item=items.getChildAt(index);
            inst.runOnMainSync(()->{item.getLocationOnScreen(viewAt);scroll.getLocationOnScreen(scrollAt);});
            int centre=viewAt[0]+item.getWidth()/2,lo=scrollAt[0]+20,hi=scrollAt[0]+scroll.getWidth()-20;
            if(centre>=lo&&centre<=hi){
                int x=scroll.getScrollX();stable=priorX==viewAt[0]&&priorScroll==x?stable+1:0;priorX=viewAt[0];priorScroll=x;
                if(stable>=8)return;Thread.sleep(50);continue;
            }
            stable=0;int dx=centre-(lo+hi)/2;swipe(scroll,Math.max(-scroll.getWidth()/3,Math.min(scroll.getWidth()/3,dx)));
        }
        throw new AssertionError("rank10 not reachable by swipe");
    }
    public void testCandidateTapAtStart()throws Exception {
        begin();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白","ㄊ","ㄧ","ㄢ","空白","ㄨ","ㄛ","ˇ"})tap(k);
        Rect caret=new Rect();inst.runOnMainSync(()->{int[] at=new int[2];row1.getLocationOnScreen(at);int x=at[0]+row1.getPaddingLeft();caret.set(x-1,at[1]+row1.getHeight()/2-1,x+1,at[1]+row1.getHeight()/2+1);});tap(caret);Thread.sleep(300);
        final String[] before={null};inst.runOnMainSync(()->before[0]=row1.getText().toString());
        revealTenth(words,wordScroll);revealTenth(characters,charScroll);
        TextView choice=(TextView)characters.getChildAt(9);String chosen=choice.getText().toString();assertEquals("single character row item",1,chosen.codePointCount(0,chosen.length()));
        Rect bounds=new Rect();inst.runOnMainSync(()->{int[] at=new int[2];choice.getLocationOnScreen(at);bounds.set(at[0],at[1],at[0]+choice.getWidth(),at[1]+choice.getHeight());});tap(bounds);Thread.sleep(300);
        inst.runOnMainSync(()->assertEquals("rank10 char replaces only first char",chosen+before[0].substring(before[0].offsetByCodePoints(0,1)),row1.getText().toString()));
        save("candidate-result.json",new JSONObject().put("character_rank",10).put("word_rank_reachable",10).put("suffix_preserved",true).toString());
    }

    ZhuyinInputController actualController(){
        try{android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();
            java.lang.reflect.Field f=SimonIMEService.class.getDeclaredField("zhuyinInput");f.setAccessible(true);return (ZhuyinInputController)f.get(c);
        }catch(Exception e){throw new RuntimeException(e);}
    }
    void typeToday()throws Exception{for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白","ㄊ","ㄧ","ㄢ","空白"})tap(k);}
    String shown(){final String[] value={null};inst.runOnMainSync(()->value[0]=row1.getText().toString());return value[0];}
    void tapBoundary(int boundary)throws Exception{
        final Rect r=new Rect();inst.runOnMainSync(()->{
            android.text.Layout layout=row1.getLayout();int utf=row1.getText().toString().offsetByCodePoints(0,boundary),line=layout.getLineForOffset(utf);
            View parent=(View)row1.getParent();if(parent instanceof ScrollView)((ScrollView)parent).scrollTo(0,Math.max(0,row1.getTotalPaddingTop()+layout.getLineTop(line)-10));
            int[] at=new int[2];row1.getLocationOnScreen(at);
            int x=Math.round(at[0]+row1.getPaddingLeft()+layout.getPrimaryHorizontal(utf)),y=at[1]+row1.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2;
            r.set(x-1,y-1,x+1,y+1);
        });tap(r);Thread.sleep(200);
    }
    public void testTwentyPhysicalDeletions()throws Exception{
        begin();for(int n=0;n<10;n++)typeToday();JSONArray results=new JSONArray();
        for(int trial=0;trial<20;trial++){
            String old=shown();int count=old.codePointCount(0,old.length());int boundary=new int[]{1,count,count/2,2,count-1}[trial%5];tapBoundary(boundary);
            String expected=old.substring(0,old.offsetByCodePoints(0,boundary-1))+old.substring(old.offsetByCodePoints(0,boundary));tap("⌫");
            assertEquals("one whole character per physical delete",expected,shown());
            inst.runOnMainSync(()->assertEquals("one reading per remaining character",expected.codePointCount(0,expected.length()),actualController().phoneticSyllables().size()));
            results.put(new JSONObject().put("trial",trial).put("boundary",boundary).put("removed_chars",1).put("remaining",count-1));
            // Keep twenty characters so the last trial also exercises a word body.
            for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白"})tap(k);
        }
        save("physical-deletions.json",results.toString());
    }
    public void testCommitClearsBothRows()throws Exception{
        begin();typeToday();awaitCandidateCount(words,1);awaitCandidateCount(characters,1);assertTrue(words.getChildCount()>0);assertTrue(characters.getChildCount()>0);tap("↵");
        assertEquals("no stale word choices after commit",0,words.getChildCount());
        assertEquals("empty preview", "",shown());
    }
    public void testRotationAndAppSwitch()throws Exception{
        begin();typeToday();String before=shown();
        ui.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);Thread.sleep(1500);tap("test_input");await("ㄗ");bind();assertEquals("rotation retains composition",before,shown());
        ui.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);Thread.sleep(1500);tap("test_input");await("ㄗ");bind();assertEquals("rotation back retains composition",before,shown());
        shell("am start -W -a android.settings.SETTINGS");Thread.sleep(500);shell("am start -W -n com.ime.sandbox.testpad/.MainActivity");tap("test_input");await("ㄗ");bind();assertEquals("app switch retains composition",before,shown());
        tap("↵");assertEquals("rotation/app switch Enter no duplicate",before,String.valueOf(await("test_input").getText()));
        save("lifecycle-result.json",new JSONObject().put("rotation",true).put("app_switch",true).toString());ui.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
    }
    public void testTenCompositionsMemory()throws Exception{
        begin();JSONArray samples=new JSONArray();for(int n=0;n<10;n++){typeToday();samples.put(new JSONObject().put("composition",n+1).put("memory",shell("dumpsys meminfo com.simon.voiceime")));tap("↵");}
        save("ten-compositions-memory.json",samples.toString());
    }

    public void testFortyFiveCursorCharacters()throws Exception{
        begin();for(int n=0;n<22;n++)typeToday();for(String k:new String[]{"ㄐ","ㄧ","ㄣ","空白"})tap(k);
        assertEquals(45,shown().codePointCount(0,shown().length()));JSONArray checks=new JSONArray();
        for(int boundary:new int[]{0,22,45}){
            tapBoundary(boundary);String before=shown();revealTenth(words,wordScroll);revealTenth(characters,charScroll);
            TextView choice=(TextView)characters.getChildAt(9);String chosen=choice.getText().toString();assertEquals(1,chosen.codePointCount(0,chosen.length()));
            Rect r=new Rect();inst.runOnMainSync(()->{int[] a=new int[2];choice.getLocationOnScreen(a);r.set(a[0],a[1],a[0]+choice.getWidth(),a[1]+choice.getHeight());});tap(r);Thread.sleep(200);
            int index=Math.max(1,boundary)-1;String expected=before.substring(0,before.offsetByCodePoints(0,index))+chosen+before.substring(before.offsetByCodePoints(0,index+1));
            assertEquals("only adjacent char replaced boundary="+boundary,expected,shown());
            inst.runOnMainSync(()->assertEquals(45,actualController().phoneticSyllables().size()));
            checks.put(new JSONObject().put("boundary",boundary).put("character_rank",10).put("word_rank_reachable",10).put("unaffected_context_preserved",true));
        }
        save("cursor45-result.json",checks.toString());
    }
    public void testTypingWordAndCharacter()throws Exception{
        begin();typeToday();awaitCandidateCount(words,1);awaitCandidateCount(characters,1);TextView choice=null;
        for(int i=0;i<words.getChildCount();i++){TextView item=(TextView)words.getChildAt(i);String t=item.getText().toString();if(t.codePointCount(0,t.length())==2&&!t.equals(shown())){choice=item;break;}}
        assertNotNull("two-character replacement word while typing",choice);revealIndex(words,wordScroll,words.indexOfChild(choice));final TextView picked=choice;String word=choice.getText().toString();
        Rect r=new Rect();inst.runOnMainSync(()->{int[] a=new int[2];picked.getLocationOnScreen(a);r.set(a[0],a[1],a[0]+picked.getWidth(),a[1]+picked.getHeight());});tap(r);
        inst.runOnMainSync(()->{try{
            android.content.Context c=row1.getContext();while(!(c instanceof SimonIMEService)&&c instanceof android.content.ContextWrapper)c=((android.content.ContextWrapper)c).getBaseContext();
            java.lang.reflect.Field taps=SimonIMEService.class.getDeclaredField("textCandidateTaps");taps.setAccessible(true);
            Object tag=picked.getTag();JSONObject check=new JSONObject().put("expected",word).put("actual",row1.getText()).put("tap_count",taps.getInt(c)).put("enabled",picked.isEnabled()).put("shown",picked.isShown()).put("bounds",r.toShortString()).put("current_keys",actualController().sentenceKeys());
            if(tag instanceof ZhuyinInputController.TextChoice){ZhuyinInputController.TextChoice ch=(ZhuyinInputController.TextChoice)tag;check.put("choice_label",ch.label).put("choice_keys",ch.keys).put("choice_witness",ch.witness).put("choice_index",ch.index).put("choice_boundary",ch.boundary).put("character_focus",ch.characterFocus);}
            save("typing-word-tap.json",check.toString());
        }catch(Exception e){throw new RuntimeException(e);}});
        assertEquals("word tap replaces word in place",word,shown());
        for(String k:new String[]{"ㄨ","ㄛ","ˇ"})tap(k);assertEquals("following text composes after word replacement",word+"我",shown());
        awaitCandidateCount(characters,2);revealIndex(characters,charScroll,1);TextView character=(TextView)characters.getChildAt(1);String label=character.getText().toString();inst.runOnMainSync(()->{int[] a=new int[2];character.getLocationOnScreen(a);r.set(a[0],a[1],a[0]+character.getWidth(),a[1]+character.getHeight());});tap(r);
        assertEquals("character tap preserves corrected word",word+label,shown());inst.runOnMainSync(()->assertEquals(3,actualController().phoneticSyllables().size()));
        save("typing-candidate-result.json",new JSONObject().put("word_replaced",true).put("following_text",true).put("char_replaced",true).toString());
    }

    public void testFortyFiveCursorWords()throws Exception{
        begin();for(int n=0;n<11;n++)typeToday();for(String k:new String[]{"ㄨ","ㄛ","ˇ"})tap(k);for(int n=0;n<11;n++)typeToday();assertEquals("45-character known-word fixture",45,shown().codePointCount(0,shown().length()));JSONArray checks=new JSONArray();
        for(int boundary:new int[]{0,22,45}){
            tapBoundary(boundary);String before=shown();int from=boundary==0?0:boundary-2;String oldWord=before.substring(before.offsetByCodePoints(0,from),before.offsetByCodePoints(0,from+2));int index=-1;assertEquals("each selected word is independently known", "今天", oldWord);
            for(int i=0;i<words.getChildCount();i++){String label=((TextView)words.getChildAt(i)).getText().toString();if(label.codePointCount(0,label.length())==2&&!label.equals(oldWord)){index=i;break;}}
            assertTrue("two-character alternative at each cursor",index>=0);revealIndex(words,wordScroll,index);TextView choice=(TextView)words.getChildAt(index);String label=choice.getText().toString();Rect r=new Rect();
            inst.runOnMainSync(()->{int[] a=new int[2];choice.getLocationOnScreen(a);r.set(a[0],a[1],a[0]+choice.getWidth(),a[1]+choice.getHeight());});tap(r);Thread.sleep(200);
            String expected=before.substring(0,before.offsetByCodePoints(0,from))+label+before.substring(before.offsetByCodePoints(0,from+2));assertEquals("only two-character adjacent word replaced",expected,shown());
            inst.runOnMainSync(()->assertEquals(45,actualController().phoneticSyllables().size()));checks.put(new JSONObject().put("boundary",boundary).put("from",from).put("span",2).put("rank",index+1).put("unaffected_context_preserved",true));
        }
        save("cursor45-word-result.json",checks.toString());
    }

}
