package com.simon.voiceime;

import android.graphics.*;
import android.graphics.drawable.*;
import android.view.animation.OvershootInterpolator;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.content.res.Configuration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Six left families, seven finals, and offline/AI/correction tracks. */
public final class DualHandKeyboard implements DualHandPage {
    public interface Hooks {
        default void changed(DualHandKeyboard keyboard) {}
        default void keyPressed() {}
        default boolean beforeSyllable(DualHandKeyboard keyboard){return true;}
        default boolean beforeWrite(DualHandKeyboard keyboard){return true;}
        default void written(DualHandKeyboard keyboard) {}
        default void committed(DualHandKeyboard keyboard,String text,List<String> sounds) {}
        default void cancel(DualHandKeyboard keyboard,boolean clear) {}
        default void close(DualHandKeyboard keyboard) {}
        default void cursorAnchor(android.view.inputmethod.CursorAnchorInfo info) {}
        default List<Integer> warnings(DualHandKeyboard keyboard){return Collections.emptyList();}
        default boolean space(DualHandKeyboard keyboard) {return false;}
        default void ai(DualHandKeyboard keyboard) {keyboard.service.dualHandStatus("AI 功能待接入");}
        default boolean restore(DualHandKeyboard keyboard) {keyboard.service.dualHandStatus("還原／刪詞功能待接入");return true;}
        default void grid(DualHandKeyboard keyboard,int index) {}
        default void selection(DualHandKeyboard keyboard,int start,int end,int composingStart,int composingEnd) {}
    }
    private final SimonIMEService service;
    private final DualHandTable table;
    private final DualHandState state;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,FrameLayout> keys=new LinkedHashMap<>();
    private final Map<String,Map<String,TextView>> labels=new HashMap<>();
    private final Map<Integer,Touch> touches=new HashMap<>();
    private final Surface root;
    private LinearLayout halves,leftHalf,rightHalf,bottomRow;
    private final LinearLayout candidates,previewItems,topBar;
    private final HorizontalScrollView previewScroll;
    private final android.graphics.Typeface typeface;
    private PopupWindow bubble;
    private TextView bubbleLabel;
    private boolean bubbleUsed,bubbleParked;
    private MessageQueue.IdleHandler bubbleWarmupIdle;
    private View bubbleWarmupContent;
    private ViewTreeObserver.OnDrawListener bubbleWarmupDraw;
    private final KeyboardLabels.Cache fitCache=new KeyboardLabels.Cache();
    private int cachedWidth=-1;
    private float cachedDensity=-1,cachedScale=-1,rowDp=78;
    private boolean split;
    private String paletteKey="",previewKey="",failedSplitKey="";
    private int safeInset;
    private static final float PREVIEW_FRACTION=DualHandVisuals.PREVIEW_FRACTION;
    private Hooks hooks=new Hooks() {};
    private boolean closed,composing;
    private float labelSizeSp;
    private final Runnable flush=this::flush;
    private static final float LEFT_WIDTH_FRACTION=DualHandVisuals.LEFT_FRACTION;
    private static final float[] BOTTOM_WIDTH_WEIGHTS={1,1,1.1f,1.2f,1.2f};
    private static final String[] BOTTOM_KEYS=DualHandTable.BOTTOM_KEYS.toArray(new String[0]);
    private static final String[][] LEFT={{"1","2"},{"3","4"},{"5","6"}};
    private static final String[][] RIGHT={{"A","B"},{"C","D"},{"E","F"}};
    private static final class Touch {
        DualHandState.Stroke stroke;
        float x,y,lastX,lastY,path,dx,dy;
        int samples;
        DualHandGesture.Result result;
        String feedback="tap";
        Runnable repeat;int repeats;boolean repeated;
    }
    public DualHandKeyboard(SimonIMEService service)throws IOException {
        this.service=service;
        try(Reader map=asset("dual_hand_map.tsv");Reader initial=asset("left_hand.tsv");Reader chars=asset("draft_chars.tsv");Reader aiChars=asset("draft_chars_ai.tsv")) {table=new DualHandTable(map,initial,chars,aiChars);}
        state=new DualHandState(table);
        typeface=KeyboardTypeface.get(service);
        root=new Surface();root.setOrientation(LinearLayout.VERTICAL);root.setTag("dual-hand-keyboard");root.setClipChildren(false);root.setClipToPadding(false);
        topBar=new LinearLayout(service);topBar.setOrientation(LinearLayout.HORIZONTAL);
        previewItems=new LinearLayout(service);previewItems.setOrientation(LinearLayout.HORIZONTAL);previewItems.setTag("dh-preview-items");
        previewScroll=new HorizontalScrollView(service);previewScroll.setTag("dh-phonetic-preview");previewScroll.setHorizontalScrollBarEnabled(false);previewScroll.addView(previewItems,new HorizontalScrollView.LayoutParams(-2,-1));
        previewItems.setOnClickListener(v->{state.setCursor(state.syllables().size());changed(false);});
        candidates=new LinearLayout(service);candidates.setOrientation(LinearLayout.HORIZONTAL);candidates.setTag("dh-candidates");
        HorizontalScrollView candidateScroll=new HorizontalScrollView(service);candidateScroll.setHorizontalScrollBarEnabled(false);candidateScroll.addView(candidates,new HorizontalScrollView.LayoutParams(-2,-1));
        topBar.addView(previewScroll,new LinearLayout.LayoutParams(0,-1,PREVIEW_FRACTION));topBar.addView(candidateScroll,new LinearLayout.LayoutParams(0,-1,1-PREVIEW_FRACTION));
        root.addView(topBar);configureLayout(service.getResources().getDisplayMetrics().widthPixels);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{applyInsets(v.getRootWindowInsets());if(r-l!=or-ol)configureLayout(r-l);});
        root.setOnApplyWindowInsetsListener((v,in)->{applyInsets(in);return in;});
        renderLabels();
        setHooks(new DualHandEditor(this,service,ui));
    }
    private void applyInsets(WindowInsets in){
        if(in==null)return;
        int bottom=Build.VERSION.SDK_INT>=30?in.getInsets(WindowInsets.Type.systemGestures()).bottom:Build.VERSION.SDK_INT>=29?in.getSystemGestureInsets().bottom:0;
        if(bottom!=safeInset){safeInset=bottom;applyPalette();}
    }
    private Reader asset(String name)throws IOException {return new InputStreamReader(service.getAssets().open("dual_hand/"+name),StandardCharsets.UTF_8);}
    private int dp(float value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private TextView text(String value,float size){TextView v=new DualHandLabelView(service);v.setText(value);v.setTextSize(size);v.setTextColor(color("text"));v.setTag(R.id.keyboard_role,"background");v.setTag(R.id.keyboard_text_role,"text");v.setGravity(Gravity.CENTER);v.setFocusable(false);v.setTypeface(typeface);return v;}
    private LinearLayout half(String[][] rows){
        LinearLayout half=new LinearLayout(service);half.setOrientation(LinearLayout.VERTICAL);
        for(String[] row:rows){LinearLayout line=new LinearLayout(service);line.setOrientation(LinearLayout.HORIZONTAL);half.addView(line,new LinearLayout.LayoutParams(-1,dp(rowDp)));
            for(String key:row){FrameLayout box=new KeyCap(service,key);box.setBackgroundResource(R.drawable.keyboard_key);box.setTag("dh-key:"+key);box.setClickable(true);
                boolean function=!DualHandTable.isLeft(key)&&!DualHandTable.isRight(key),compact=!DualHandTable.isRight(key);
                Map<String,TextView> parts=new HashMap<>();TextView main=text("",function?12:labelSizeSp);parts.put("tap",main);
                main.setIncludeFontPadding(false);
                box.addView(main,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                for(String direction:new String[]{"up","left","down","right"}){
                    TextView edge=text("",function?12:labelSizeSp);edge.setIncludeFontPadding(false);parts.put(direction,edge);int gravity="up".equals(direction)?Gravity.TOP|Gravity.CENTER_HORIZONTAL:"down".equals(direction)?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:"left".equals(direction)?Gravity.LEFT|Gravity.CENTER_VERTICAL:Gravity.RIGHT|Gravity.CENTER_VERTICAL;
                    FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,gravity);box.addView(edge,p);
                }
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),dp(2),dp(2),dp(2));line.addView(box,p);keys.put(key,box);labels.put(key,parts);
                box.setOnClickListener(v->activate(key,"tap"));
            }
        }return half;
    }
    private int[] columnWidths(int screenWidth){
        int available=screenWidth-dp(6);boolean wide=screenWidth>=dp(600);
        int active=wide?Math.min(2*dp(service.getSharedPreferences("simon_ime_prefs",0).getFloat("dual_hand_outer_half_dp",200)),available):available;
        int minKey=(int)Math.ceil(64*service.getResources().getDisplayMetrics().density);
        int left=Math.max(2*(minKey+2*dp(2)),Math.round(active*LEFT_WIDTH_FRACTION));
        if(split){left=Math.round(screenWidth*DualHandVisuals.WING_FRACTION);active=2*left;}
        int[] widths=new int[7];widths[0]=left;widths[1]=active-left;
        float total=0;for(float weight:BOTTOM_WIDTH_WEIGHTS)total+=weight;
        int zero=Math.max(minKey+2*dp(2),Math.round(available*BOTTOM_WIDTH_WEIGHTS[2]/total));
        int minSlot=2*dp(2)+(int)Math.ceil(52*service.getResources().getDisplayMetrics().density);
        int remaining=available-zero,cells=4;float others=total-BOTTOM_WIDTH_WEIGHTS[2];
        for(int i=0;i<5;i++){
            if(i==2){widths[i+2]=zero;continue;}
            cells--;int desired=Math.round((available-zero)*BOTTOM_WIDTH_WEIGHTS[i]/others);
            widths[i+2]=cells==0?remaining:Math.min(Math.max(minSlot,desired),remaining-minSlot*cells);
            remaining-=widths[i+2];
        }
        return widths;
    }
    private float keyContentWidth(String key,int[] widths){
        int width=split?widths[0]/3:DualHandTable.isLeft(key)?widths[0]/2:"Ø".equals(key)?widths[4]:widths[1]/2;
        return KeyboardLabels.contentWidth(width,dp(KeyboardLabels.KEY_MARGIN_DP),0);
    }
    private float fitLabelSize(int screenWidth){
        android.util.DisplayMetrics metrics=service.getResources().getDisplayMetrics();
        int[] widths=columnWidths(screenWidth);float height=dp(rowDp)-2*dp(KeyboardLabels.KEY_MARGIN_DP)-2*dp(KeyboardLabels.FIT_VERTICAL_MARGIN_DP);
        android.graphics.Paint paint=new android.graphics.Paint();paint.setTypeface(typeface);
        List<KeyboardLabels.Label> group=new ArrayList<>();
        List<String> states=new ArrayList<>();states.add(null);states.addAll(Arrays.asList(DualHandTable.STATES));
        for(String pending:states)for(String key:DualHandTable.RIGHT_KEYS){
            String[] values=new String[5];for(int i=0;i<5;i++){
                String sound=table.right(pending,key,DualHandTable.DIRECTIONS[i]);if(!sound.isEmpty())sound=suffix(pending,sound);
                values[i]="up".equals(DualHandTable.DIRECTIONS[i])||"down".equals(DualHandTable.DIRECTIONS[i])||split?sound:vertical(sound);
            }group.add(new KeyboardLabels.Label(keyContentWidth(key,widths),height,0,values));
        }
        for(int key=1;key<=6;key++){String[] values=new String[5];for(int i=0;i<5;i++)values[i]=table.left(""+key,DualHandTable.DIRECTIONS[i]);group.add(new KeyboardLabels.Label(keyContentWidth(""+key,widths),height,0,values));}
        return fitCache.fit(screenWidth,metrics.density,metrics.scaledDensity,typeface,paint,group);
    }
    private void configureLayout(int width){
        if(width<=0)return;
        android.util.DisplayMetrics metrics=service.getResources().getDisplayMetrics();
        String layoutKey=width+":"+metrics.density+":"+metrics.scaledDensity+":"+service.getResources().getConfiguration().screenHeightDp;
        boolean desired=!layoutKey.equals(failedSplitKey)&&service.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE&&DualHandVisuals.ENABLE_LANDSCAPE_SPLIT&&service.getSharedPreferences("simon_ime_prefs",0).getBoolean("dual_hand_landscape_split",true)&&182<=service.getResources().getConfiguration().screenHeightDp*.55f&&DualHandVisuals.landscapeRowDp(service.getResources().getConfiguration().screenHeightDp)>=0;
        if(cachedWidth==width&&cachedDensity==metrics.density&&cachedScale==metrics.scaledDensity&&split==desired){applyPaletteIfChanged();return;}
        cancelTouches();split=desired;rowDp=split?50:78;
        while(root.getChildCount()>1)root.removeViewAt(1);keys.clear();labels.clear();
        topBar.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(split?32:44)));
        ((LinearLayout.LayoutParams)previewScroll.getLayoutParams()).weight=split?.3f:PREVIEW_FRACTION;
        ((LinearLayout.LayoutParams)topBar.getChildAt(1).getLayoutParams()).weight=split?.7f:1-PREVIEW_FRACTION;
        halves=new LinearLayout(service);halves.setOrientation(LinearLayout.HORIZONTAL);halves.setClipChildren(false);root.addView(halves,new LinearLayout.LayoutParams(-1,-2));
        if(split){
            leftHalf=half(new String[][]{{"1","2","3"},{"4","5","6"},{"tools","symbols"}});
            rightHalf=half(new String[][]{{"A","B","C"},{"D","E","F"},{"Ø","clipboard","core"}});bottomRow=null;
        }else {leftHalf=half(LEFT);rightHalf=half(RIGHT);}
        int[] widths=columnWidths(width);
        halves.addView(leftHalf,new LinearLayout.LayoutParams(widths[0],-2));halves.addView(new View(service),new LinearLayout.LayoutParams(0,1,1));halves.addView(rightHalf,new LinearLayout.LayoutParams(widths[1],-2));
        if(!split){LinearLayout bottom=half(new String[][]{BOTTOM_KEYS});bottomRow=(LinearLayout)bottom.getChildAt(0);root.addView(bottom,new LinearLayout.LayoutParams(-1,-2));for(int i=0;i<5;i++){View key=bottomRow.getChildAt(i);LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)key.getLayoutParams();p.width=widths[i+2]-2*dp(2);p.weight=0;key.setLayoutParams(p);}}
        try{labelSizeSp=fitLabelSize(width);}catch(IllegalStateException tooSmall){if(split){failedSplitKey=layoutKey;cachedWidth=-1;configureLayout(width);return;}throw tooSmall;}
        for(String key:labels.keySet())if(DualHandTable.isLeft(key)||DualHandTable.isRight(key))for(TextView label:labels.get(key).values())label.setTextSize(labelSizeSp);
        cachedWidth=width;cachedDensity=metrics.density;cachedScale=metrics.scaledDensity;previewKey="";paletteKey="";applyPaletteIfChanged();renderPreview();
    }
    private void applyPaletteIfChanged(){String key=service.getSharedPreferences("simon_ime_prefs",0).getString("keyboard_theme","system")+":"+service.getResources().getConfiguration().uiMode;if(!key.equals(paletteKey)){paletteKey=key;applyPalette();}}
    private boolean dark(){return KeyboardTheme.isDark(service.getSharedPreferences("simon_ime_prefs",0).getString("keyboard_theme","system"),(service.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    private int color(String role){return DualHandVisuals.color(dark(),role);}
    private void applyPalette(){
        root.setTag(R.id.keyboard_role,split?"transparent":"background");root.setBackgroundColor(split?color("transparent"):color("background"));root.setPadding(split?0:dp(3),0,split?0:dp(3),(split?0:dp(3))+(KeyboardInsets.ownedByParent(root)?0:safeInset));
        topBar.setTag(R.id.keyboard_role,"background");topBar.setBackgroundColor(color("background"));
        for(Map.Entry<String,FrameLayout> entry:keys.entrySet()){
            String key=entry.getKey(),role=DualHandTable.isLeft(key)?"left":"Ø".equals(key)?"zero":DualHandTable.isRight(key)?"right":"core".equals(key)?"core":"tools";
            KeyboardKeycaps.apply(entry.getValue(),dark(),role,0);
        }
        renderLabels();previewKey="";renderPreview();
    }
    public boolean landscapeSplit(){return split;}
    public void touchableRegion(android.graphics.Region region,int x,int y){
        region.set(x,y,x+root.getWidth(),y+topBar.getHeight());if(split){int wing=Math.round(root.getWidth()*DualHandVisuals.WING_FRACTION);region.op(x,y+topBar.getHeight(),x+wing,y+root.getHeight(),android.graphics.Region.Op.UNION);region.op(x+root.getWidth()-wing,y+topBar.getHeight(),x+root.getWidth(),y+root.getHeight(),android.graphics.Region.Op.UNION);}else region.op(x,y,x+root.getWidth(),y+root.getHeight(),android.graphics.Region.Op.UNION);
    }
    public void setHooks(Hooks hooks){this.hooks=Objects.requireNonNull(hooks);hooks.changed(this);}
    public DualHandState buffer(){return state;}
    public LinearLayout candidateRow(){return candidates;}
    public View view(){return root;}
    private String labelState(){
        Touch latest=null;for(Touch touch:touches.values())if(DualHandTable.isLeft(touch.stroke.key)&&(latest==null||touch.stroke.down>=latest.stroke.down))latest=touch;
        if(latest!=null){String value=table.left(latest.stroke.key,latest.result.direction);if(!value.isEmpty())return value;}
        return state.pending();
    }
    private void renderLabels(){
        String pending=labelState();
        for(int i=1;i<=6;i++){
            String key=Integer.toString(i);Map<String,TextView> parts=labels.get(key);StringBuilder description=new StringBuilder(key);
            for(String direction:DualHandTable.DIRECTIONS){String value=table.left(key,direction);TextView label=parts.get(direction);if(!value.contentEquals(label.getText()))label.setText(value);label.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);if(!value.isEmpty())description.append(" ").append("tap".equals(direction)?"":direction+" ").append(value);}
            keys.get(key).setContentDescription(description);
        }
        functionLabels("tools","41鍵","⚙","英數","收起","語音");
        functionLabels("symbols","空白","、","符號","。","，");
        functionLabels("clipboard","剪貼簿","常用詞","","","");
        functionLabels("core","送出","⌫",state.aiShown()?"還原":"刪字","AI","送出");
        keys.get("core").setActivated(state.syllables().size()>8);
        labels.get("core").get("tap").setTextColor(state.syllables().size()>8?color("accent"):color("text"));
        labels.get("core").get("down").setTextColor(state.syllables().size()>8?color(dark()?"mode_append":"accent"):color("text"));
        labels.get("core").get("left").setTextColor(state.aiShown()?color("accent"):color("text"));
        for(String key:DualHandTable.RIGHT_KEYS){
            Map<String,TextView> parts=labels.get(key);
            for(String direction:DualHandTable.DIRECTIONS){
                String syllable=table.right(pending,key,direction),value=syllable.isEmpty()?"":suffix(pending,syllable);
                TextView label=parts.get(direction);String formatted=split||"up".equals(direction)||"down".equals(direction)?value:vertical(value);if(!formatted.contentEquals(label.getText()))label.setText(formatted);label.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
            }
            StringBuilder description=new StringBuilder(key+" "+parts.get("tap").getText());
            for(String direction:new String[]{"up","left","down","right"})if(!parts.get(direction).getText().toString().isEmpty())description.append(" ").append(direction).append(" ").append(parts.get(direction).getText());
            keys.get(key).setContentDescription(description);
        }
        for(String key:keys.keySet()){
            String highlighted="";for(Touch touch:touches.values())if(touch.stroke.key.equals(key)&&DualHandVisuals.highlight(touch.result.direction,output(key,touch.result.direction)))highlighted=touch.result.direction;
            for(String direction:DualHandTable.DIRECTIONS){TextView label=labels.get(key).get(direction);boolean active=direction.equals(highlighted);int tint=active||pending!=null&&DualHandTable.isRight(key)?color("hint"):color("tap".equals(direction)?"text":"secondary");
                if("core".equals(key)&&((state.syllables().size()>8&&("tap".equals(direction)||"down".equals(direction)))||(state.aiShown()&&"left".equals(direction))))tint=color("accent");
                label.setTag(R.id.keyboard_role,keys.get(key).getTag(R.id.keyboard_role));label.setTag(R.id.keyboard_text_role,"core".equals(key)&&((state.syllables().size()>8&&("tap".equals(direction)||"down".equals(direction)))||(state.aiShown()&&"left".equals(direction)))?"accent":active||pending!=null&&DualHandTable.isRight(key)?"hint":"tap".equals(direction)?"text":"secondary");
                if("core".equals(key)&&"down".equals(direction)&&state.syllables().size()>8){tint=color(dark()?"mode_append":"accent");label.setTag(R.id.keyboard_text_role,dark()?"mode_append":"accent");}
                if(label.getCurrentTextColor()!=tint){label.setTextColor(tint);if(pending!=null&&DualHandTable.isRight(key)){label.setAlpha(.6f);label.animate().alpha(1).setDuration(120).start();}}
                if(label.isActivated()!=active){label.setActivated(active);label.animate().scaleX(active?1.15f:1).scaleY(active?1.15f:1).setDuration(80).start();}
            }
            keys.get(key).invalidate();
        }
    }
    private String output(String key,String direction){return DualHandTable.isLeft(key)?table.left(key,direction):DualHandTable.isRight(key)?table.right(labelState(),key,direction):"";}
    private void renderPreview(){
        String key=state.revision()+":"+state.selected()+":"+state.cursor()+":"+state.pending()+":"+paletteKey;
        if(key.equals(previewKey))return;previewKey=key;previewItems.removeAllViews();View caret=null;
        for(int i=0;i<=state.syllables().size();i++){
            if(i==state.cursor()&&state.pending()!=null){TextView pending=text(state.pending(),12);pending.setTag(R.id.keyboard_text_role,"hint");pending.setTextColor(color("hint"));pending.setContentDescription("待補韻母 "+state.pending());previewItems.addView(pending,new LinearLayout.LayoutParams(-2,-1));caret=pending;}
            if(i==state.syllables().size())break;
            final int index=i;TextView item=text(state.syllables().get(i),12);item.setPadding(dp(3),0,dp(3),0);item.setTextColor(color("text"));item.setTag("dh-syllable:"+i);item.setContentDescription("注音 "+state.syllables().get(i));
            if(i==state.selected()){item.setTag(R.id.keyboard_role,"highlight");item.setBackgroundColor(color("highlight"));}
            item.setOnClickListener(v->{state.select(index);changed(false);});previewItems.addView(item,new LinearLayout.LayoutParams(-2,-1));if(i+1==state.cursor()&&caret==null)caret=item;
        }
        View target=caret;previewScroll.post(()->{if(target!=null){int x=Math.max(0,target.getRight()-previewScroll.getWidth());previewScroll.smoothScrollTo(x,0);}else previewScroll.smoothScrollTo(0,0);});
    }
    private void feedback(Touch touch){
        String direction=touch.result.direction,value=output(touch.stroke.key,direction);boolean letter=DualHandTable.isLeft(touch.stroke.key)||DualHandTable.isRight(touch.stroke.key);
        if("core".equals(touch.stroke.key)&&"up".equals(direction)&&touch.repeat==null){
            touch.repeat=new Runnable(){public void run(){if(!touches.containsValue(touch)||!"up".equals(touch.result.direction))return;touch.repeated=true;function("core","up");touch.repeats++;ui.postDelayed(this,Math.max(30,120-touch.repeats*6));}};
            ui.postDelayed(touch.repeat,Math.max(0,400-(SystemClock.uptimeMillis()-touch.stroke.down)));
        }else if(!"up".equals(direction)&&touch.repeat!=null){ui.removeCallbacks(touch.repeat);touch.repeat=null;}
        if(!direction.equals(touch.feedback)){if(!"tap".equals(direction))haptic(HapticFeedbackConstants.CLOCK_TICK);touch.feedback=direction;
            FrameLayout cap=keys.get(touch.stroke.key);cap.animate().translationX(0).translationY(0).rotationX(0).rotationY(0).setDuration(80);
            if(letter&&cap.isHardwareAccelerated()&&DualHandVisuals.highlight(direction,value)){float dx="right".equals(direction)?dp(2):"left".equals(direction)?-dp(2):0,dy="down".equals(direction)?dp(2):"up".equals(direction)?-dp(2):0;cap.animate().translationX(dx).translationY(dy).rotationX(-dy).rotationY(dx).setDuration(80);}cap.animate().start();
        }
        if(!letter||!DualHandVisuals.highlight(direction,value)){if(!bubbleParked&&bubble!=null)bubble.dismiss();return;}
        bubbleUsed=true;bubbleParked=false;stopBubbleWarmup();
        if(bubble==null)createBubble();
        bubbleLabel.setText(value);bubbleLabel.setTextSize(labelSizeSp*1.25f);int[] origin=new int[2];root.getLocationOnScreen(origin);
        int x=DualHandVisuals.clamp(origin[0]+Math.round(touch.lastX)-dp(31),dp(62),service.getResources().getDisplayMetrics().widthPixels),y=DualHandVisuals.clamp(origin[1]+Math.round(touch.lastY)-dp(15)-dp(62),dp(62),service.getResources().getDisplayMetrics().heightPixels);
        // Cursor and touch positions are screen coordinates; older popups use the parent window.
        if(Build.VERSION.SDK_INT>=29)bubble.setIsLaidOutInScreen(true);
        else {int[] window=new int[2];root.getLocationInWindow(window);x-=origin[0]-window[0];y-=origin[1]-window[1];}
        try{if(bubble.isShowing())bubble.update(x,y,-1,-1);else {bubble.showAtLocation(root,Gravity.TOP|Gravity.LEFT,x,y);}}catch(RuntimeException hidden){bubble.dismiss();}
    }
    private void createBubble(){bubbleLabel=text("",labelSizeSp*1.25f);bubbleLabel.setGravity(Gravity.CENTER);GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(color("bubble"));FrameLayout content=new FrameLayout(service);View halo=new View(service);halo.setTag(R.id.keyboard_role,"bubble");halo.setBackground(circle);if(Build.VERSION.SDK_INT>=31)halo.setRenderEffect(RenderEffect.createBlurEffect(dp(2),dp(2),Shader.TileMode.CLAMP));content.addView(halo,new FrameLayout.LayoutParams(-1,-1));bubbleLabel.setTag(R.id.keyboard_role,"bubble");bubbleLabel.setTag(R.id.keyboard_text_role,"bubble_text");bubbleLabel.setTextColor(color("bubble_text"));content.addView(bubbleLabel,new FrameLayout.LayoutParams(-1,-1));bubble=new PopupWindow(content,dp(62),dp(62),false);bubble.setBackgroundDrawable(new ColorDrawable(color("transparent")));bubble.setClippingEnabled(false);bubble.setTouchable(false);bubble.setElevation(dp(3));}
    private void scheduleBubbleWarmup(){
        if(closed||bubbleUsed||bubbleParked||bubbleWarmupIdle!=null||!root.isShown()||root.getWindowVisibility()!=View.VISIBLE||!root.isAttachedToWindow()||root.getWidth()<=0||root.getHeight()<=0)return;
        bubbleWarmupIdle=()->{
            bubbleWarmupIdle=null;
            if(closed||bubbleUsed||bubbleParked||!root.isShown()||root.getWindowVisibility()!=View.VISIBLE||!root.isAttachedToWindow()||!touches.isEmpty())return false;
            if(bubble==null)createBubble();
            bubbleLabel.setText(output("Ø","right"));bubbleLabel.setTextSize(labelSizeSp*1.25f);
            bubbleParked=true;View content=bubble.getContentView();bubbleWarmupContent=content;
            bubbleWarmupDraw=new ViewTreeObserver.OnDrawListener(){
                int draws;
                public void onDraw(){
                    draws++;
                    ui.post(()->{
                        // A touch or lifecycle exit may already have taken ownership of this window.
                        if(bubbleWarmupDraw!=this)return;
                        if(draws<2)content.invalidate();else stopBubbleWarmup();
                    });
                }
            };
            content.getViewTreeObserver().addOnDrawListener(bubbleWarmupDraw);
            // Match real feedback content/halo, but keep the complete window and shadow offscreen.
            int x=-dp(70),y=0;
            if(Build.VERSION.SDK_INT>=29)bubble.setIsLaidOutInScreen(true);
            else {int[] screen=new int[2],window=new int[2];root.getLocationOnScreen(screen);root.getLocationInWindow(window);x-=screen[0]-window[0];y-=screen[1]-window[1];}
            try{bubble.showAtLocation(root,Gravity.TOP|Gravity.LEFT,x,y);}catch(RuntimeException hidden){dismissBubble();}
            return false;
        };
        Looper.myQueue().addIdleHandler(bubbleWarmupIdle);
    }
    private void stopBubbleWarmup(){
        if(bubbleWarmupIdle!=null){Looper.myQueue().removeIdleHandler(bubbleWarmupIdle);bubbleWarmupIdle=null;}
        if(bubbleWarmupDraw!=null&&bubbleWarmupContent!=null){ViewTreeObserver observer=bubbleWarmupContent.getViewTreeObserver();if(observer.isAlive())observer.removeOnDrawListener(bubbleWarmupDraw);}
        bubbleWarmupDraw=null;bubbleWarmupContent=null;
    }
    private void dismissBubble(){stopBubbleWarmup();bubbleParked=false;if(bubble!=null)bubble.dismiss();}
    static void blurPopup(android.content.Context context,PopupWindow popup,int radius){
        if(Build.VERSION.SDK_INT<31)return;
        View decor=popup.getContentView().getRootView();if(!(decor.getLayoutParams() instanceof WindowManager.LayoutParams))return;
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)decor.getLayoutParams();if((params.flags&WindowManager.LayoutParams.FLAG_BLUR_BEHIND)!=0)return;
        params.flags|=WindowManager.LayoutParams.FLAG_BLUR_BEHIND;params.setBlurBehindRadius(radius);
        try{((WindowManager)context.getSystemService(android.content.Context.WINDOW_SERVICE)).updateViewLayout(decor,params);}catch(RuntimeException unavailable){/* System blur may be unavailable; the translucent background remains. */}
    }
    private void releaseCap(String key){FrameLayout cap=keys.get(key);cap.setPressed(false);KeyboardKeycaps.release(cap);dismissBubble();}
    private final class KeyCap extends FrameLayout implements KeyboardProbe.Source {
        final String key;final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        KeyCap(android.content.Context c,String key){super(c);this.key=key;setWillNotDraw(false);setClipChildren(false);setClipToPadding(false);}
        public List<Map<String,Object>> describe(){
            Map<String,TextView> parts=labels.get(key);TextView main=parts.get("tap");
            List<Map<String,Object>> rows=new ArrayList<>();Map<String,Object> cap=new LinkedHashMap<>(KeyboardProbe.text(main,DualHandTable.isLeft(key)||DualHandTable.isRight(key),true));cap.putAll(KeyboardProbe.view(this));
            cap.put("kind",DualHandTable.isLeft(key)||DualHandTable.isRight(key)?"keycap":"function");cap.put("key",key);cap.put("guide_dot",(DualHandTable.isLeft(key)||DualHandTable.isRight(key))&&DualHandVisuals.guideDot(output(key,"tap")));rows.add(cap);
            if(parts!=null)for(String direction:DualHandTable.DIRECTIONS){TextView label=parts.get(direction);if(label.getVisibility()!=View.VISIBLE)continue;
                Map<String,Object> row=KeyboardProbe.text(label,DualHandTable.isLeft(key)||DualHandTable.isRight(key),true);
                row.put("role",getTag(R.id.keyboard_role));row.put("bg_color",color((String)getTag(R.id.keyboard_role)));row.put("key",key);row.put("direction",direction);
                row.put("bg_factory",true);row.put("corner_dp",KeyboardKeycaps.CORNER_DP);row.put("elevation_dp",getElevation()/service.getResources().getDisplayMetrics().density);row.put("highlight_line",true);row.put("kind","icon".equals(row.get("label_group"))?"icon":cap.get("kind"));
                if("⌫".equals(label.getText().toString())||"⚙".equals(label.getText().toString())){
                    Paint system=new Paint(Paint.ANTI_ALIAS_FLAG);system.setTypeface(Typeface.DEFAULT);system.setTextSize(label.getTextSize());Rect bounds=new Rect();String value=label.getText().toString();system.getTextBounds(value,0,value.length(),bounds);
                    int[] origin=new int[2];label.getLocationOnScreen(origin);float width=system.hasGlyph(value)?bounds.width():dp(14),height=system.hasGlyph(value)?bounds.height():dp(14);
                    float x=origin[0]+(label.getWidth()-width)/2,y=origin[1]+(label.getHeight()-height)/2;
                    row.put("typeface","system");row.put("icon_visible_px",Math.max(width,height));row.put("label_bounds",new float[]{x,y,x+width,y+height});row.put("clipped",width>label.getWidth()||height>label.getHeight());
                }rows.add(row);
            }return rows;
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),h=getHeight();
            if(!DualHandTable.isLeft(key)&&!DualHandTable.isRight(key))return;
            ink.setColor(color("secondary"));
            ink.setStyle(Paint.Style.FILL);ink.setAlpha(80);if(DualHandVisuals.guideDot(output(key,"tap")))canvas.drawCircle(w/2,h/2,dp(1.5f),ink);
        }
    }
    private void functionLabels(String key,String... values){
        StringBuilder description=new StringBuilder();
        for(int i=0;i<DualHandTable.DIRECTIONS.length;i++){
            String direction=DualHandTable.DIRECTIONS[i],value=values[i];TextView label=labels.get(key).get(direction);
            String formatted=shortFunctionLabel(value,direction);if(!formatted.contentEquals(label.getText()))label.setText(formatted);label.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
            if(!value.isEmpty()){if(description.length()>0)description.append(" ");if(!"tap".equals(direction))description.append(direction).append(" ");description.append(value);}
        }
        keys.get(key).setContentDescription(description);
    }
    private String shortFunctionLabel(String value,String direction){return KeyboardLabels.functionLabel(value,direction,split);}
    private static String vertical(String value){return String.join("\n",value.split(""));}
    private static String suffix(String state,String syllable){
        if(state!=null&&!Arrays.asList("Ø").contains(state)&&syllable.startsWith(state))syllable=syllable.substring(state.length());
        return syllable.isEmpty()?"Ø":syllable;
    }
    public boolean compose(List<Integer> warnings){
        if(!hooks.beforeWrite(this))return false;boolean accepted=service.dualHandCompose(state.draft(),warnings);
        if(accepted){composing=!state.draft().isEmpty();hooks.written(this);}else service.dualHandStatus("輸入框未接受組字，草稿已保留");return accepted;
    }
    public void refresh(){changed(false);}
    private void changed(boolean editor){
        if(editor)compose(hooks.warnings(this));
        renderLabels();renderPreview();hooks.changed(this);
    }
    private void vibrate(){haptic(HapticFeedbackConstants.CLOCK_TICK);}
    private void haptic(int kind){KeyboardHaptics.perform(root,kind);}
    private void flush(){
        if(closed)return;
        List<DualHandState.Output> ready;
        while(!(ready=state.drain(SystemClock.uptimeMillis(),()->hooks.beforeSyllable(this))).isEmpty())for(DualHandState.Output result:ready){
            if(result.empty)haptic(Build.VERSION.SDK_INT>=30?HapticFeedbackConstants.REJECT:HapticFeedbackConstants.LONG_PRESS);
            if(!result.syllable.isEmpty()){JSONObject data=new JSONObject();try{data.put("state",result.state).put("key",result.stroke.key).put("gesture",result.direction).put("syllable",result.syllable);}catch(JSONException error){throw new IllegalStateException(error);}event("dh_syllable",data);changed(true);}
            else if(!DualHandTable.isLeft(result.stroke.key)&&!DualHandTable.isRight(result.stroke.key))function(result.stroke.key,result.direction);
            else changed(false);
        }
        renderLabels();
    }
    private void activate(String key,String direction){
        hooks.keyPressed();
        DualHandState.Stroke stroke=state.begin(key,SystemClock.uptimeMillis());state.end(stroke,direction,false);flush();ui.removeCallbacks(flush);ui.postDelayed(flush,DualHandGesture.RACE_MS+1);
    }
    private void function(String key,String direction){
        if("core".equals(key))switch(direction){
            case "tap":
            case "right":if(!state.syllables().isEmpty())finish();else if(finish())service.dualHandFunction("enter");break;
            case "up":Integer before=service.dualHandBackspaceLength();int composingBefore=state.syllables().size();
                DualHandState.Delete deleted=state.backspace();if(deleted==DualHandState.Delete.EDITOR)service.dualHandErase();else if(deleted==DualHandState.Delete.BLOCKED)vibrate();else changed(deleted==DualHandState.Delete.SYLLABLE);
                service.dualHandBackspaceTelemetry("down",before,composingBefore);service.dualHandBackspaceTelemetry("up",null,state.syllables().size());break;
            case "down":if(service.dualHandProtected())vibrate();else hooks.ai(this);break;
            case "left":if(!hooks.restore(this)&&finish())service.dualHandStatus("刪詞功能待接入");break;
        }
        else if("symbols".equals(key))switch(direction){
            case "tap":state.discardPending();if(!hooks.space(this)){if(state.syllables().isEmpty())service.dualHandCommit(" ");else finish();}break;
            case "up":if(finish())service.dualHandCommit("、");break;
            case "left":if(finish())service.dualHandPanel("symbols");break;
            case "right":if(finish())service.dualHandCommit("，");break;
            case "down":if(finish())service.dualHandCommit("。");break;
            default:vibrate();
        }
        else if("clipboard".equals(key))switch(direction){
            case "tap":if(finish())service.dualHandPanel("clipboard");break;
            case "up":if(finish())service.dualHandPanel("commands");break;
            default:vibrate();
        }
        else if("tools".equals(key))switch(direction){
            case "tap":if(finish())service.dualHandFunction("to41");break;
            case "up":if(finish())service.dualHandFunction("settings");break;
            case "left":if(finish())service.dualHandFunction("toEnglish");break;
            case "right":if(finish())service.dualHandFunction("toVoice");break;
            case "down":if(finish())service.requestHideSelf(0);break;
        }
    }
    public boolean finish(){
        state.discardPending();String value=state.draft();List<String> sounds=state.syllables();if(!value.isEmpty()&&!hooks.beforeWrite(this))return false;
        if(!value.isEmpty()&&!service.dualHandCommit(value))return false;
        if(value.isEmpty()&&composing&&!service.dualHandFinishComposition())return false;
        composing=false;hooks.committed(this,value,sounds);state.clearBuffer();changed(false);return true;
    }
    public void requestGrid(int index){if(index<0||index>=state.syllables().size())return;hooks.grid(this,index);}
    public void event(String name,JSONObject fields){if(service.dualHandProtected())return;ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry!=null)telemetry.record(name,"dual_hand",fields,service.dualHandProtected());}
    private void cancelTouches(){dismissBubble();for(Touch touch:touches.values())if(touch.repeat!=null)ui.removeCallbacks(touch.repeat);for(String key:keys.keySet())releaseCap(key);touches.clear();state.cancelTouches();ui.removeCallbacks(flush);}
    private boolean leftEdge(String key){
        if(!DualHandTable.isLeft(key)||table.left(key,"left").isEmpty())return false;
        FrameLayout cap=keys.get(key);if(cap==null)return false;
        ViewGroup row=(ViewGroup)cap.getParent();
        // Layout coordinates identify the edge independently of the animated key translation.
        return cap.getLeft()==row.getPaddingLeft()+((ViewGroup.MarginLayoutParams)cap.getLayoutParams()).leftMargin;
    }
    public void clear(){cancelTouches();hooks.cancel(this,true);state.clear();composing=false;changed(false);}
    public void reconfigure(){cancelTouches();hooks.cancel(this,false);configureLayout(root.getWidth());renderLabels();}
    public void selection(int start,int end,int composingStart,int composingEnd){hooks.selection(this,start,end,composingStart,composingEnd);}
    public void cursorAnchor(android.view.inputmethod.CursorAnchorInfo info){if(info==null)dismissBubble();hooks.cursorAnchor(info);}
    public void close(){closed=true;cancelTouches();hooks.cancel(this,true);hooks.close(this);}
    private final class Surface extends LinearLayout {
        Surface(){super(service);setMotionEventSplittingEnabled(false);}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){super.onLayout(changed,l,t,r,b);scheduleBubbleWarmup();}
        @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);if(root==null)return;if(!root.isShown())dismissBubble();else scheduleBubbleWarmup();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(root==null)return;if(visibility!=View.VISIBLE)dismissBubble();else scheduleBubbleWarmup();}
        @Override protected void onDetachedFromWindow(){dismissBubble();super.onDetachedFromWindow();}
        private String hit(float x,float y){
            if(y<topBar.getBottom())return null;
            if(split&&x>=root.getWidth()*DualHandVisuals.WING_FRACTION&&x<root.getWidth()*(1-DualHandVisuals.WING_FRACTION))return null;
            String nearest=null;float best=Float.MAX_VALUE;
            for(Map.Entry<String,FrameLayout> item:keys.entrySet()){
                Rect r=new Rect();item.getValue().getDrawingRect(r);offsetDescendantRectToMyCoords(item.getValue(),r);
                if(r.isEmpty())continue;if(r.contains((int)x,(int)y))return item.getKey();
                float dx=Math.max(Math.max(r.left-x,0),x-r.right),dy=Math.max(Math.max(r.top-y,0),y-r.bottom),distance=dx*dx+dy*dy;
                if(distance<best&&distance<=dp(4)*dp(4)){nearest=item.getKey();best=distance;}
            }return nearest;
        }
        public boolean onInterceptTouchEvent(MotionEvent e){return e.getActionMasked()==MotionEvent.ACTION_DOWN&&hit(e.getX(),e.getY())!=null;}
        public boolean onTouchEvent(MotionEvent e){
            if(closed)return true;
            int action=e.getActionMasked(),index=e.getActionIndex();
            if(action==MotionEvent.ACTION_CANCEL){cancelTouches();renderLabels();return true;}
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){String key=hit(e.getX(index),e.getY(index));if(key!=null){hooks.keyPressed();Touch t=new Touch();t.x=t.lastX=e.getX(index);t.y=t.lastY=e.getY(index);t.result=DualHandGesture.classify(0,0,0,0);t.stroke=state.begin(key,e.getEventTime());touches.put(e.getPointerId(index),t);keys.get(key).setPressed(true);KeyboardKeycaps.press(keys.get(key));haptic(HapticFeedbackConstants.KEYBOARD_TAP);renderLabels();}}
            float density=service.getResources().getDisplayMetrics().density;
            for(int i=0;i<e.getPointerCount();i++){Touch t=touches.get(e.getPointerId(i));if(t==null)continue;
                for(int h=0;h<e.getHistorySize();h++)move(t,e.getHistoricalX(i,h),e.getHistoricalY(i,h),density);
                move(t,e.getX(i),e.getY(i),density);t.result=DualHandGesture.classify(t.stroke.key,leftEdge(t.stroke.key),t.dx,t.dy,e.getEventTime()-t.stroke.down,t.path,t.samples);feedback(t);
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){Touch t=touches.remove(e.getPointerId(index));if(t!=null){if(t.repeat!=null)ui.removeCallbacks(t.repeat);state.end(t.stroke,t.result.direction,t.repeated&&"up".equals(t.result.direction));releaseCap(t.stroke.key);
                try{event("dh_gesture",new JSONObject().put("key",t.stroke.key).put("dx_dp",t.dx).put("dy_dp",t.dy).put("contact_ms",e.getEventTime()-t.stroke.down).put("gesture",t.result.direction).put("ambiguous",t.result.ambiguous).put("samples",t.samples));}catch(JSONException error){throw new IllegalStateException(error);}
                flush();ui.removeCallbacks(flush);ui.postDelayed(flush,DualHandGesture.RACE_MS+1);
            }}renderLabels();return true;
        }
        private void move(Touch t,float x,float y,float density){if(t.samples==0||x!=t.lastX||y!=t.lastY)t.samples++;t.path+=(float)Math.hypot(x-t.lastX,y-t.lastY)/density;t.lastX=x;t.lastY=y;t.dx=(x-t.x)/density;t.dy=(y-t.y)/density;}
    }
}
