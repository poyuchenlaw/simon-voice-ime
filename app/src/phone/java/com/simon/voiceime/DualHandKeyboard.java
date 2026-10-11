package com.simon.voiceime;

import android.graphics.Rect;
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
    private final LinearLayout halves, leftHalf, rightHalf, candidates;
    private Hooks hooks=new Hooks() {};
    private boolean closed,composing;
    private final Runnable flush=this::flush;
    private static final String[][] LEFT={{"1","2"},{"3","4"},{"5","6"},{"tools","symbols"}};
    private static final String[][] RIGHT={{"A","B"},{"C","D"},{"E","F"},{"Ø","core"}};
    private static final class Touch {
        DualHandState.Stroke stroke;
        float x,y,lastX,lastY,path,dx,dy;
        int samples;
        DualHandGesture.Result result;
    }
    public DualHandKeyboard(SimonIMEService service)throws IOException {
        this.service=service;
        try(Reader map=asset("dual_hand_map.tsv");Reader initial=asset("left_hand.tsv");Reader chars=asset("draft_chars.tsv");Reader aiChars=asset("draft_chars_ai.tsv")) {table=new DualHandTable(map,initial,chars,aiChars);}
        state=new DualHandState(table);
        root=new Surface();root.setOrientation(LinearLayout.VERTICAL);root.setTag("dual-hand-keyboard");
        root.setPadding(dp(3),0,dp(3),dp(3));
        candidates=new LinearLayout(service);candidates.setOrientation(LinearLayout.HORIZONTAL);candidates.setTag("dh-candidates");
        root.addView(candidates,new LinearLayout.LayoutParams(-1,dp(44)));
        halves=new LinearLayout(service);halves.setOrientation(LinearLayout.HORIZONTAL);root.addView(halves,new LinearLayout.LayoutParams(-1,-2));
        leftHalf=half(LEFT);rightHalf=half(RIGHT);
        halves.addView(leftHalf);halves.addView(new View(service),new LinearLayout.LayoutParams(0,1,1));halves.addView(rightHalf);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)sizeHalves(r-l);});
        root.setOnApplyWindowInsetsListener((v,in)->{root.setPadding(dp(3),0,dp(3),dp(3)+in.getSystemWindowInsetBottom());return in;});
        sizeHalves(service.getResources().getDisplayMetrics().widthPixels);renderLabels();
        setHooks(new DualHandEditor(this,service,ui));
    }
    private Reader asset(String name)throws IOException {return new InputStreamReader(service.getAssets().open("dual_hand/"+name),StandardCharsets.UTF_8);}
    private int dp(float value){return Math.round(value*service.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(service);v.setText(value);v.setTextSize(size);v.setTextColor(service.getResources().getColor(R.color.keyboard_key_text,null));v.setGravity(Gravity.CENTER);v.setFocusable(false);return v;}
    private LinearLayout half(String[][] rows){
        LinearLayout half=new LinearLayout(service);half.setOrientation(LinearLayout.VERTICAL);
        for(String[] row:rows){LinearLayout line=new LinearLayout(service);line.setOrientation(LinearLayout.HORIZONTAL);half.addView(line,new LinearLayout.LayoutParams(-1,dp(78)));
            for(String key:row){FrameLayout box=new FrameLayout(service);box.setBackgroundResource(R.drawable.keyboard_key);box.setTag("dh-key:"+key);box.setClickable(true);
                boolean function=!DualHandTable.isLeft(key)&&!DualHandTable.isRight(key),compact=!DualHandTable.isRight(key);
                Map<String,TextView> parts=new HashMap<>();TextView main=text("",function?10:DualHandTable.isLeft(key)?18:23);parts.put("tap",main);
                if(compact){main.setSingleLine();main.setIncludeFontPadding(false);}
                box.addView(main,new FrameLayout.LayoutParams(compact?-2:-1,compact?-2:-1,Gravity.CENTER));
                for(String direction:new String[]{"up","left","down","right"}){
                    TextView edge=text("",function?8:DualHandTable.isLeft(key)?9:11);if(compact){edge.setSingleLine();edge.setIncludeFontPadding(false);}parts.put(direction,edge);int gravity="up".equals(direction)?Gravity.TOP|Gravity.CENTER_HORIZONTAL:"down".equals(direction)?Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL:"left".equals(direction)?Gravity.LEFT|Gravity.CENTER_VERTICAL:Gravity.RIGHT|Gravity.CENTER_VERTICAL;
                    FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,gravity);int margin=dp(compact?1:2);p.setMargins(margin,margin,margin,margin);box.addView(edge,p);
                }
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(2),dp(2),dp(2),dp(2));line.addView(box,p);keys.put(key,box);labels.put(key,parts);
                box.setOnClickListener(v->activate(key,"tap"));
            }
        }return half;
    }
    private void sizeHalves(int width){
        if(width<=0)return;
        // Tunable handset reference: half of a 400dp outer screen; wide screens keep thumbs at edges.
        int available=width-root.getPaddingLeft()-root.getPaddingRight();boolean wide=width>=dp(600);
        android.content.SharedPreferences prefs=service.getSharedPreferences("simon_ime_prefs",0);
        if(!wide)prefs.edit().putFloat("dual_hand_outer_half_dp",available/(2f*service.getResources().getDisplayMetrics().density)).apply();
        int half=wide?Math.min(dp(prefs.getFloat("dual_hand_outer_half_dp",200)),available/2):available/2;
        leftHalf.setLayoutParams(new LinearLayout.LayoutParams(half,-2));rightHalf.setLayoutParams(new LinearLayout.LayoutParams(half,-2));
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
            for(String direction:DualHandTable.DIRECTIONS){String value=table.left(key,direction);TextView label=parts.get(direction);label.setText(value);label.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);if(!value.isEmpty())description.append(" ").append("tap".equals(direction)?"":direction+" ").append(value);}
            keys.get(key).setContentDescription(description);
        }
        functionLabels("tools","工具箱","41鍵","英數","收起","語音");
        functionLabels("symbols","符號","剪貼","","。","，");
        functionLabels("core","空白","⌫",state.aiShown()?"還原":"刪詞","AI","送出");
        keys.get("core").setActivated(state.syllables().size()>8);
        labels.get("core").get("tap").setTextColor(state.syllables().size()>8?0xFF4ECCA3:service.getResources().getColor(R.color.keyboard_key_text,null));
        labels.get("core").get("down").setTextColor(state.syllables().size()>8?0xFF4ECCA3:service.getResources().getColor(R.color.keyboard_key_text,null));
        labels.get("core").get("left").setTextColor(state.aiShown()?0xFF4ECCA3:service.getResources().getColor(R.color.keyboard_key_text,null));
        for(String key:DualHandTable.RIGHT_KEYS){
            Map<String,TextView> parts=labels.get(key);
            String main=table.right(pending,key,"tap");
            String fallback=table.right(null,key,"tap");if(fallback.isEmpty())fallback="Ø";
            parts.get("tap").setText(main.isEmpty()?fallback:suffix(pending,main));
            parts.get("tap").setAlpha(pending==null||!main.isEmpty()?1f:.25f);
            for(String direction:new String[]{"up","left","down","right"}){
                TextView edge=parts.get(direction);String syllable=table.right(pending,key,direction),value;
                if(pending==null)value=syllable.matches(".*[ㄧㄨㄩ].*")?"":syllable;
                else value=syllable.isEmpty()?"":suffix(pending,syllable);
                edge.setText(value);edge.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
            }
            StringBuilder description=new StringBuilder(key+" "+parts.get("tap").getText());
            for(String direction:new String[]{"up","left","down","right"})if(!parts.get(direction).getText().toString().isEmpty())description.append(" ").append(direction).append(" ").append(parts.get(direction).getText());
            keys.get(key).setContentDescription(description);
        }
    }
    private void functionLabels(String key,String... values){
        StringBuilder description=new StringBuilder();
        for(int i=0;i<DualHandTable.DIRECTIONS.length;i++){
            String direction=DualHandTable.DIRECTIONS[i],value=values[i];TextView label=labels.get(key).get(direction);
            label.setText(value);label.setVisibility(value.isEmpty()?View.GONE:View.VISIBLE);
            if(!value.isEmpty()){if(description.length()>0)description.append(" ");if(!"tap".equals(direction))description.append(direction).append(" ");description.append(value);}
        }
        keys.get(key).setContentDescription(description);
    }
    private static String suffix(String state,String syllable){
        if(state!=null&&!Arrays.asList("Ø","ㄧ","ㄨ","ㄩ").contains(state)&&syllable.startsWith(state))syllable=syllable.substring(state.length());
        return syllable.isEmpty()?"Ø":syllable;
    }
    public boolean compose(List<Integer> warnings){
        if(!hooks.beforeWrite(this))return false;boolean accepted=service.dualHandCompose(state.draft(),warnings);
        if(accepted){composing=!state.draft().isEmpty();hooks.written(this);}else service.dualHandStatus("輸入框未接受組字，草稿已保留");return accepted;
    }
    public void refresh(){changed(false);}
    private void changed(boolean editor){
        if(editor)compose(hooks.warnings(this));
        renderLabels();hooks.changed(this);
    }
    private void vibrate(){if(service.getSharedPreferences("simon_ime_prefs",0).getBoolean("haptic_enabled",true))root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}
    private void flush(){
        if(closed)return;
        List<DualHandState.Output> ready;
        while(!(ready=state.drain(SystemClock.uptimeMillis(),()->hooks.beforeSyllable(this))).isEmpty())for(DualHandState.Output result:ready){
            if(result.empty)vibrate();
            if(!result.syllable.isEmpty()){JSONObject data=new JSONObject();try{data.put("state",result.state).put("key",result.stroke.key).put("gesture",result.direction).put("syllable",result.syllable);}catch(JSONException error){throw new IllegalStateException(error);}event("dh_syllable",data);changed(true);}
            else if(!DualHandTable.isLeft(result.stroke.key)&&!DualHandTable.isRight(result.stroke.key))function(result.stroke.key,result.direction);
            else changed(false);
        }
        renderLabels();
    }
    private void activate(String key,String direction){
        DualHandState.Stroke stroke=state.begin(key,SystemClock.uptimeMillis());state.end(stroke,direction,false);flush();ui.removeCallbacks(flush);ui.postDelayed(flush,DualHandGesture.RACE_MS+1);
    }
    private void function(String key,String direction){
        if("core".equals(key))switch(direction){
            case "tap":if(!hooks.space(this)){if(state.syllables().isEmpty())service.dualHandCommit(" ");else finish();}break;
            case "up":DualHandState.Delete deleted=state.backspace();if(deleted==DualHandState.Delete.EDITOR)service.dualHandErase();else changed(deleted==DualHandState.Delete.SYLLABLE);break;
            case "down":if(service.dualHandProtected())vibrate();else hooks.ai(this);break;
            case "left":if(!hooks.restore(this)&&finish())service.dualHandStatus("刪詞功能待接入");break;
            case "right":if(!state.syllables().isEmpty())finish();else if(finish())service.dualHandFunction("enter");break;
        }
        else if("symbols".equals(key))switch(direction){
            case "tap":if(finish())service.dualHandPanel("symbols");break;
            case "up":if(finish())service.dualHandPanel("clipboard");break;
            case "right":if(finish())service.dualHandCommit("，");break;
            case "down":if(finish())service.dualHandCommit("。");break;
            default:vibrate();
        }
        else if("tools".equals(key))switch(direction){
            case "tap":if(finish())service.dualHandPanel("commands");break;
            case "up":if(finish())service.dualHandFunction("to41");break;
            case "left":if(finish())service.dualHandFunction("toEnglish");break;
            case "right":if(finish())service.dualHandFunction("toVoice");break;
            case "down":if(finish())service.requestHideSelf(0);break;
        }
    }
    public boolean finish(){
        String value=state.draft();List<String> sounds=state.syllables();if(!value.isEmpty()&&!hooks.beforeWrite(this))return false;
        if(!value.isEmpty()&&!service.dualHandCommit(value))return false;
        if(value.isEmpty()&&composing&&!service.dualHandFinishComposition())return false;
        composing=false;hooks.committed(this,value,sounds);state.clearBuffer();changed(false);return true;
    }
    public void requestGrid(int index){if(index<0||index>=state.syllables().size())return;hooks.grid(this,index);}
    public void event(String name,JSONObject fields){if(service.dualHandProtected())return;ImeTelemetry telemetry=ImeTelemetry.get();if(telemetry!=null)telemetry.record(name,"dual_hand",fields,service.dualHandProtected());}
    private void cancelTouches(){touches.clear();state.cancelTouches();ui.removeCallbacks(flush);for(FrameLayout key:keys.values())key.setPressed(false);}
    public void clear(){cancelTouches();hooks.cancel(this,true);state.clear();composing=false;changed(false);}
    public void reconfigure(){cancelTouches();hooks.cancel(this,false);sizeHalves(root.getWidth());renderLabels();}
    public void selection(int start,int end,int composingStart,int composingEnd){hooks.selection(this,start,end,composingStart,composingEnd);}
    public void cursorAnchor(android.view.inputmethod.CursorAnchorInfo info){hooks.cursorAnchor(info);}
    public void close(){closed=true;cancelTouches();hooks.cancel(this,true);hooks.close(this);}
    private final class Surface extends LinearLayout {
        Surface(){super(service);setMotionEventSplittingEnabled(false);}
        private String hit(float x,float y){
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
            if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){String key=hit(e.getX(index),e.getY(index));if(key!=null){Touch t=new Touch();t.x=t.lastX=e.getX(index);t.y=t.lastY=e.getY(index);t.result=DualHandGesture.classify(0,0,0,0);t.stroke=state.begin(key,e.getEventTime());touches.put(e.getPointerId(index),t);keys.get(key).setPressed(true);renderLabels();}}
            float density=service.getResources().getDisplayMetrics().density;
            for(int i=0;i<e.getPointerCount();i++){Touch t=touches.get(e.getPointerId(i));if(t==null)continue;
                for(int h=0;h<e.getHistorySize();h++)move(t,e.getHistoricalX(i,h),e.getHistoricalY(i,h),density);
                move(t,e.getX(i),e.getY(i),density);t.result=DualHandGesture.classify(t.dx,t.dy,e.getEventTime()-t.stroke.down,t.path,t.samples);
            }
            if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){Touch t=touches.remove(e.getPointerId(index));if(t!=null){state.end(t.stroke,t.result.direction,false);keys.get(t.stroke.key).setPressed(false);
                try{event("dh_gesture",new JSONObject().put("key",t.stroke.key).put("dx_dp",t.dx).put("dy_dp",t.dy).put("contact_ms",e.getEventTime()-t.stroke.down).put("gesture",t.result.direction).put("ambiguous",t.result.ambiguous).put("samples",t.samples));}catch(JSONException error){throw new IllegalStateException(error);}
                flush();ui.removeCallbacks(flush);ui.postDelayed(flush,DualHandGesture.RACE_MS+1);
            }}renderLabels();return true;
        }
        private void move(Touch t,float x,float y,float density){if(t.samples==0||x!=t.lastX||y!=t.lastY)t.samples++;t.path+=(float)Math.hypot(x-t.lastX,y-t.lastY)/density;t.lastX=x;t.lastY=y;t.dx=(x-t.x)/density;t.dy=(y-t.y)/density;}
    }
}
