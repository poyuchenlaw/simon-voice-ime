package com.simon.voiceime;
import android.os.*;import android.view.*;import android.view.inputmethod.*;import android.widget.*;import android.graphics.*;import android.graphics.drawable.ColorDrawable;import java.io.*;import java.util.*;
/** Shared cursor correction, field-local ownership and popup positioning. */
class CorrectionEditor {
    interface Page {
        View view();LinearLayout candidateRow();DualHandState buffer();
        default View fallbackAnchor(){return candidateRow()==null?view():candidateRow();}
        default boolean dual(){return false;}default boolean blocked(){return false;}default boolean reverseOnly(){return false;}
        String name();default void refresh(){}default boolean compose(List<Integer> warnings){return false;}
        default List<Integer> warnings(){return Collections.emptyList();}default void blur(PopupWindow popup,int radius){}
    }
    protected final SimonIMEService service;protected final Handler ui;protected Page page;
    protected DualHandTracks tracks;protected DualHandMemory memory;protected boolean closed;
    protected View guardView;protected ViewTreeObserver.OnPreDrawListener pageGuard;
    protected Runnable selectionTask,markerExpiry;protected PopupWindow popup,markerPopup;protected TextView marker;
    protected long markerRequestGeneration;
    protected long lastTapTime=-1000;protected int lastTapPosition=-1;protected Snapshot lastTapOwner;
    protected CursorAnchorInfo anchor;protected int compositionStart=-1,compositionEnd=-1,beforeCompositionEnd=-1;
    protected Snapshot witness,beforeWrite;
    protected final CorrectionRecords records=new CorrectionRecords();
    protected final Map<Integer,String> positions=records.positions,glyphs=records.glyphs;
    protected final CorrectionCounters counters=new CorrectionCounters();
protected static final class Snapshot {
        final InputConnection connection;final long field;final String text;final int offset,start,end;
        Snapshot(InputConnection connection,long field,ExtractedText value){this.connection=connection;this.field=field;text=value.text.toString();offset=value.startOffset;start=offset+Math.min(value.selectionStart,value.selectionEnd);end=offset+Math.max(value.selectionStart,value.selectionEnd);}
        boolean sameText(Snapshot other){return other!=null&&connection==other.connection&&field==other.field&&offset==other.offset&&text.equals(other.text);}
        boolean same(Snapshot other){return sameText(other)&&start==other.start&&end==other.end;}
        String range(int left,int right){return left>=offset&&right>=left&&right<=offset+text.length()?text.substring(left-offset,right-offset):null;}
    }
protected Snapshot snapshot(){
        try{
            InputConnection connection=service.getCurrentInputConnection();if(connection==null)return null;
            ExtractedTextRequest request=new ExtractedTextRequest();request.hintMaxChars=512;request.hintMaxLines=10;
            ExtractedText value=connection.getExtractedText(request,0);
            if(value==null||value.text==null||value.partialStartOffset>=0||value.partialEndOffset>=0||value.startOffset<0||value.selectionStart<0||value.selectionEnd<0||value.selectionStart>value.text.length()||value.selectionEnd>value.text.length())return null;
            return new Snapshot(connection,service.dualHandFieldGeneration(),value);
        }catch(RuntimeException unavailable){return null;}
    }
protected void forgetPositions(){records.clear();}
protected void validate(Snapshot now){
        if(service.dualHandProtected()){forgetPositions();return;}
        if(witness==null||witness.sameText(now))return;
        if(page.dual()||now==null||witness.connection!=now.connection||witness.field!=now.field||witness.offset!=now.offset){forgetPositions();return;}
        int left=0,oldEnd=witness.text.length(),newEnd=now.text.length();
        while(left<oldEnd&&left<newEnd&&witness.text.charAt(left)==now.text.charAt(left))left++;
        if(left>0&&left<oldEnd&&Character.isLowSurrogate(witness.text.charAt(left)))left--;
        while(oldEnd>left&&newEnd>left&&witness.text.charAt(oldEnd-1)==now.text.charAt(newEnd-1)){oldEnd--;newEnd--;}
        if(oldEnd<witness.text.length()&&Character.isLowSurrogate(witness.text.charAt(oldEnd))){oldEnd++;newEnd++;}
        rebase(witness,now,witness.offset+left,witness.offset+oldEnd,now.text.substring(left,newEnd));
    }
public boolean beforeWrite(Page page){
        beforeWrite=snapshot();beforeCompositionEnd=compositionStart<0?-1:compositionEnd;validate(beforeWrite);
        if(beforeWrite==null||witness!=null&&!witness.same(beforeWrite)){
            cancelCall("stale");dismissGrid();service.dualHandStatus("文字已變動，草稿已保留，請重新定位游標");return false;
        }
        return true;
    }
public void written(Page page){
        Snapshot now=snapshot();
        if(now==null){forgetPositions();witness=null;compositionStart=-1;return;}
        String text=page.buffer().draft();int left=now.end-text.length();
        int oldLeft=beforeWrite==null?-1:compositionStart>=0?compositionStart:beforeWrite.start;
        if(beforeWrite!=null&&oldLeft>=0)rebase(beforeWrite,now,oldLeft,beforeCompositionEnd>=0?beforeCompositionEnd:beforeWrite.end,text);
        compositionStart=now.start==now.end&&text.equals(now.range(left,now.end))?left:-1;compositionEnd=compositionStart<0?-1:now.end;
        witness=now;
    }
public void committed(Page page,String text,List<String> sounds){
        Snapshot now=snapshot();validateBeforeCommit(now,text);
        if(now!=null&&now.start==now.end&&text.equals(now.range(now.end-text.length(),now.end))&&sounds.size()==text.codePointCount(0,text.length())){
            records.record(now.end-text.length(),text,sounds,tracks,service.dualHandProtected(),!page.dual());
        }
        witness=now;compositionStart=compositionEnd=-1;cancelCall("cancelled");dismissGrid();
    }
protected void validateBeforeCommit(Snapshot now,String text){
        if(beforeWrite==null||now==null||beforeWrite.connection!=now.connection||beforeWrite.field!=now.field||beforeWrite.offset!=now.offset){forgetPositions();return;}
        int left=compositionStart>=0?compositionStart:beforeWrite.start;
        String prefix=beforeWrite.range(beforeWrite.offset,left),suffix=beforeWrite.range(beforeCompositionEnd>=0?beforeCompositionEnd:beforeWrite.end,beforeWrite.offset+beforeWrite.text.length());
        if(prefix==null||suffix==null||!(prefix+text+suffix).equals(now.text))forgetPositions();
        else rebase(beforeWrite,now,left,beforeCompositionEnd>=0?beforeCompositionEnd:beforeWrite.end,text);
    }
protected void rebase(Snapshot before,Snapshot after,int left,int right,String value){
        String prefix=before.range(before.offset,left),suffix=before.range(right,before.offset+before.text.length());
        if(after==null||before.connection!=after.connection||before.field!=after.field||before.offset!=after.offset||prefix==null||suffix==null||!(prefix+value+suffix).equals(after.text)){forgetPositions();return;}
        records.rebase(before.text,after.text,left,right,value,before.offset);
    }
public void selection(Page page,int start,int end,int composingLeft,int composingEnd){
        if(closed||service.dualHandProtected()){cancel(true);return;}
        if(!page.dual()&&(page.blocked()||composingLeft>=0)){cancel(false);return;}
        if(selectionTask!=null)ui.removeCallbacks(selectionTask);
        boolean own=page.dual()?service.dualHandSelfUpdate():service.correctionSelfUpdate();Snapshot now=snapshot();validate(now);
        if(own){
            String current=page.buffer().draft();
            if(now!=null&&composingLeft>=0&&composingEnd-composingLeft==current.length()&&current.equals(now.range(composingLeft,composingEnd))){compositionStart=composingLeft;this.compositionEnd=composingEnd;}
            witness=now;return;
        }
        cancelCall("stale");dismissGrid();dismissMarker();
        if(now==null||now.start!=start||now.end!=end)return;
        if(start!=end)return;
        int left=composingLeft>=0?composingLeft:compositionStart;
        String current=page.buffer().draft();boolean inComposition=!current.isEmpty()&&left>=0&&current.equals(now.range(left,left+current.length()))&&start>=left&&start<=left+current.length();
        if(!current.isEmpty()&&!inComposition){service.dualHandFinishComposition();page.buffer().clearBuffer();compositionStart=compositionEnd=-1;page.refresh();}
        final int base=inComposition?left:now.offset;
        int utf=Math.max(base,start-(start>base?1:0));if(utf>now.offset&&Character.isLowSurrogate(now.text.charAt(utf-now.offset)))utf--;
        final int index=now.text.codePointCount(base-now.offset,utf-now.offset);final boolean composing=inComposition;
        long time=SystemClock.uptimeMillis();
        boolean doubleTap=lastTapOwner!=null&&lastTapOwner.sameText(now)&&lastTapPosition==start&&time-lastTapTime<=500;
        lastTapOwner=now;lastTapPosition=start;lastTapTime=time;
        if(doubleTap){lastTapOwner=null;openGrid(now,base,index,composing);}
        else {selectionTask=()->{selectionTask=null;if(now.same(snapshot()))showMarker(now,base,index,composing);};ui.postDelayed(selectionTask,100);}
        witness=now;
    }
public void keyPressed(){
        dismissMarker();lastTapOwner=null;
        if(selectionTask!=null)ui.removeCallbacks(selectionTask);selectionTask=null;
    }
protected void dismissMarker(){
        markerRequestGeneration++;
        if(markerExpiry!=null)ui.removeCallbacks(markerExpiry);markerExpiry=null;
        if(markerPopup!=null){markerPopup.dismiss();markerPopup=null;}
        if(marker!=null&&marker.getParent() instanceof ViewGroup)((ViewGroup)marker.getParent()).removeView(marker);
        marker=null;releasePageGuard();
    }
protected void showMarker(Snapshot owner,int base,int index,boolean composing){
        showMarkerWithBounds(owner,base,index,composing,null,false);
    }
private void showMarkerWithBounds(Snapshot owner,int base,int index,boolean composing,RectF resolved,boolean requested){
        dismissMarker();if(service.dualHandProtected()||page.blocked()||!owner.same(snapshot()))return;
        String text=composing?page.buffer().draft():owner.text;
        if(index<0||index>=text.codePointCount(0,text.length()))return;
        if(choices(owner,base,index,composing).isEmpty()){counters.event(page.name(),"no_candidates");return;}
        float density=service.getResources().getDisplayMetrics().density;
        int width=Math.round(32*density),height=Math.round(24*density);
        marker=new TextView(service){protected final Paint glow=new Paint(Paint.ANTI_ALIAS_FLAG);@Override protected void onDraw(Canvas canvas){glow.setColor(color("halo"));canvas.drawRoundRect(getWidth()/2f-8*density,getHeight()/2f-4*density,getWidth()/2f+8*density,getHeight()/2f+4*density,4*density,4*density,glow);}};
        marker.setContentDescription("開啟校正選字");marker.setTag("dh-correction-marker");
        marker.setOnClickListener(v->{dismissMarker();if(owner.same(snapshot()))openGrid(owner,base,index,composing);});
        RectF bounds=resolved;
        if(bounds==null&&anchor!=null&&anchor.getSelectionStart()==owner.start&&anchor.getSelectionEnd()==owner.end){
            bounds=anchor.getCharacterBounds(base+text.offsetByCodePoints(0,index));
            if(bounds!=null)bounds=new RectF(bounds);
            if(bounds==null&&!requested&&Build.VERSION.SDK_INT>=34){
                final long generation=markerRequestGeneration;
                final int position=base+text.offsetByCodePoints(0,index);
                android.util.DisplayMetrics display=service.getResources().getDisplayMetrics();
                owner.connection.requestTextBoundsInfo(new RectF(0,0,display.widthPixels,display.heightPixels),command->ui.post(command),result->{
                    if(generation!=markerRequestGeneration||closed||service.dualHandProtected()||page.blocked()||!page.view().isShown()||!owner.same(snapshot()))return;
                    RectF exact=null;
                    TextBoundsInfo info=result.getTextBoundsInfo();
                    if(info!=null&&position>=info.getStartIndex()&&position<info.getEndIndex()){
                        exact=new RectF();info.getCharacterBounds(position,exact);Matrix matrix=new Matrix();info.getMatrix(matrix);matrix.mapRect(exact);
                        if(!Float.isFinite(exact.left)||!Float.isFinite(exact.top)||!Float.isFinite(exact.right)||!Float.isFinite(exact.bottom))exact=null;
                    }
                    showMarkerWithBounds(owner,base,index,composing,exact,true);
                });
                return;
            }
            if(bounds==null)bounds=new RectF(anchor.getInsertionMarkerHorizontal(),anchor.getInsertionMarkerTop(),anchor.getInsertionMarkerHorizontal(),anchor.getInsertionMarkerBottom());
            anchor.getMatrix().mapRect(bounds);
        }
        if(bounds!=null&&Float.isFinite(bounds.right)&&Float.isFinite(bounds.bottom)){
            markerPopup=new PopupWindow(marker,width,height,false);markerPopup.setBackgroundDrawable(new ColorDrawable(color("transparent")));markerPopup.setOutsideTouchable(false);markerPopup.setClippingEnabled(true);
            android.util.DisplayMetrics display=service.getResources().getDisplayMetrics();
            Rect visible=new Rect();page.view().getWindowVisibleDisplayFrame(visible);
            if(visible.isEmpty())visible.set(0,0,display.widthPixels,display.heightPixels);
            int[] position=CorrectionPosition.place(bounds.left,bounds.top,bounds.bottom,width,height,display.widthPixels,visible.top,visible.bottom,density);
            int x=position[0],y=position[1];
            if(Build.VERSION.SDK_INT>=29)markerPopup.setIsLaidOutInScreen(true);
            else {int[] screen=new int[2],window=new int[2];page.view().getLocationOnScreen(screen);page.view().getLocationInWindow(window);x-=screen[0]-window[0];y-=screen[1]-window[1];}
            try{markerPopup.showAtLocation(page.view(),Gravity.TOP|Gravity.LEFT,x,y);}
            catch(RuntimeException unavailable){markerPopup=null;fallbackMarker(width,height);}
        }else fallbackMarker(width,height);
        guardPage();markerExpiry=this::dismissMarker;ui.postDelayed(markerExpiry,3000);
    }
public void cursorAnchor(CursorAnchorInfo info){anchor=info;if(info==null){dismissMarker();lastTapOwner=null;}}
protected void openGrid(Snapshot owner,int base,int index,boolean composing){
        dismissMarker();
        if(service.dualHandProtected()||page.blocked()||tracks==null||!owner.same(snapshot()))return;cancelCall("cancelled");validate(owner);
        String text=composing?page.buffer().draft():owner.text;int[] points=text.codePoints().toArray();
        if(index<0||index>=points.length)return;
        List<DualHandTracks.Choice> choices=choices(owner,base,index,composing);if(choices.isEmpty()){counters.event(page.name(),"no_candidates");return;}
        String[] labels=choices.stream().map(c->c.text).toArray(String[]::new);long revision=page.buffer().revision();dismissGrid();
        View content=service.buildChoiceGrid(labels,3,72,rank->{
            DualHandTracks.Choice choice=choices.get(rank);
            if(!service.dualHandProtected()&&!page.blocked()&&owner.same(snapshot())&&(!composing||revision==page.buffer().revision())){
                if(composing){page.buffer().replace(choice.start,choice.end,choice.text);boolean replaced=page.compose(page.warnings());page.refresh();if(replaced){remember(page.buffer().syllables().subList(choice.start,choice.end),choice.text,owner.field);page.refresh();gridEvent("replaced",index,rank,choice.personal);}}
                else replaceCommitted(owner,base,text,choice,index,rank);
            }else {forgetPositions();service.dualHandStatus("文字已變動，請重新選字");}
            dismissGrid();
        });
        content.setBackgroundColor(color("overlay"));
        final PopupWindow openedPopup=new PopupWindow(content,-2,-2,false);popup=openedPopup;popup.setOnDismissListener(()->{if(popup==openedPopup){popup=null;counters.cancel();releasePageGuard();}});popup.setBackgroundDrawable(new ColorDrawable(color("transparent")));popup.setOutsideTouchable(true);popup.setClippingEnabled(true);popup.setElevation(8*service.getResources().getDisplayMetrics().density);
        try{showGridAtCursor(base+text.offsetByCodePoints(0,index));page.blur(popup,Math.round(20*service.getResources().getDisplayMetrics().density));gridEvent("opened",index,-1,false);guardPage();}
        catch(RuntimeException unavailable){dismissGrid();service.dualHandStatus("選字框未能開啟");}
    }
protected void showGridAtCursor(int position){
        if(anchor==null){popup.showAsDropDown(page.fallbackAnchor(),0,-Math.round(180*service.getResources().getDisplayMetrics().density));return;}
        RectF bounds=anchor.getCharacterBounds(position);
        if(bounds==null)bounds=new RectF(anchor.getInsertionMarkerHorizontal(),anchor.getInsertionMarkerTop(),anchor.getInsertionMarkerHorizontal(),anchor.getInsertionMarkerBottom());
        if(!Float.isFinite(bounds.left)||!Float.isFinite(bounds.top)){popup.showAsDropDown(page.fallbackAnchor());return;}
        anchor.getMatrix().mapRect(bounds);View content=popup.getContentView();content.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
        android.util.DisplayMetrics display=service.getResources().getDisplayMetrics();
        int x=Math.max(0,Math.min(Math.round(bounds.left),display.widthPixels-content.getMeasuredWidth()));
        int y=DualHandVisuals.clamp(Math.round(bounds.top)-content.getMeasuredHeight()-Math.round(4*service.getResources().getDisplayMetrics().density),content.getMeasuredHeight(),display.heightPixels);
        if(bounds.top-content.getMeasuredHeight()<0)y=DualHandVisuals.clamp(Math.round(bounds.bottom)+Math.round(4*service.getResources().getDisplayMetrics().density),content.getMeasuredHeight(),display.heightPixels);
        popup.showAtLocation(page.view(),Gravity.TOP|Gravity.LEFT,x,y);
    }
protected void replaceCommitted(Snapshot owner,int base,String text,DualHandTracks.Choice choice,int index,int rank){
        if(service.dualHandProtected()||page.blocked()||!owner.same(snapshot())||choice.text.isEmpty())return;
        int left=base+text.offsetByCodePoints(0,choice.start),right=base+text.offsetByCodePoints(0,choice.end);InputConnection connection=owner.connection;
        String old=owner.range(left,right);boolean selected=false;
        try{
            connection.beginBatchEdit();if(!page.dual())service.correctionSelfWrite(left,right);selected=connection.setSelection(left,right);Snapshot selection=snapshot();
            if(!selected||!owner.sameText(selection)||selection.start!=left||selection.end!=right)return;
            if(!page.dual())service.correctionSelfWrite(left+choice.text.length(),left+choice.text.length());
            if(!connection.commitText(choice.text,1)){service.dualHandStatus("輸入框未接受校正");return;}
            Snapshot replaced=snapshot();String expected=owner.text.substring(0,left-owner.offset)+choice.text+owner.text.substring(right-owner.offset);
            if(replaced==null||!expected.equals(replaced.text)){forgetPositions();return;}
            List<String> retained=new ArrayList<>();int oldAt=left;
            for(int cp:old.codePoints().toArray()){retained.add(positions.get(oldAt));oldAt+=Character.charCount(cp);}
            rebase(owner,replaced,left,right,choice.text);
            records.record(left,choice.text,retained,tracks,service.dualHandProtected(),!page.dual());
            remember(choice.syllables,choice.text,owner.field);gridEvent("replaced",index,rank,choice.personal);witness=replaced;
        }catch(RuntimeException unavailable){service.dualHandStatus("輸入框未接受校正");}
        finally{try{if(selected){int delta=choice.text.length()-(right-left);int restoreStart=owner.start>=right?owner.start+delta:owner.start>left?left+choice.text.length():owner.start;int restoreEnd=owner.end>=right?owner.end+delta:owner.end>left?left+choice.text.length():owner.end;if(!page.dual())service.correctionSelfWrite(restoreStart,restoreEnd);connection.setSelection(restoreStart,restoreEnd);}}finally{connection.endBatchEdit();witness=snapshot();}}
    }
protected void remember(List<String> sounds,String text,long field){
        if(service.dualHandProtected()||memory==null||field!=service.dualHandFieldGeneration())return;
        try{memory.remember(sounds,text,service.dualHandProtected());}
        catch(IOException failure){service.dualHandStatus("個人記憶未儲存，文字已保留");}
    }
protected void dismissGrid(){counters.cancel();if(popup!=null){PopupWindow old=popup;popup=null;old.getContentView().animate().alpha(0).setDuration(150).withEndAction(old::dismiss).start();}releasePageGuard();}

    CorrectionEditor(SimonIMEService service,Handler ui,Page page){this.service=service;this.ui=ui;this.page=page;service.correctionDictionary().load(this);}
    protected void guardPage(){
        if(pageGuard!=null)return;guardView=page.view();
        pageGuard=()->{if(service.dualHandProtected()||page.blocked())cancel(false);return true;};
        guardView.getViewTreeObserver().addOnPreDrawListener(pageGuard);
    }
    protected void releasePageGuard(){
        if(marker==null&&popup==null&&pageGuard!=null){if(guardView.getViewTreeObserver().isAlive())guardView.getViewTreeObserver().removeOnPreDrawListener(pageGuard);pageGuard=null;guardView=null;}
    }
    protected void ready(){}
    protected void cancelCall(String outcome){}
    protected int color(String role){return DualHandVisuals.color(KeyboardRoles.dark(service),role);}
    protected void fallbackMarker(int width,int height){
        android.util.Log.d("IME690Correction","anchor_unavailable page="+page.name());
        if(page.candidateRow()!=null)page.candidateRow().addView(marker,new LinearLayout.LayoutParams(width,height));
        else {markerPopup=new PopupWindow(marker,width,height,false);markerPopup.setBackgroundDrawable(new ColorDrawable(color("transparent")));markerPopup.showAsDropDown(page.view(),0,-page.view().getHeight());}
    }
    protected List<DualHandTracks.Choice> choices(Snapshot owner,int base,int index,boolean composing){
        if(tracks==null)return Collections.emptyList();
        String text=composing?page.buffer().draft():owner.text;int[] points=text.codePoints().toArray();
        if(index<0||index>=points.length)return Collections.emptyList();
        boolean recorded=!composing&&!page.reverseOnly()&&records.reading(base+text.offsetByCodePoints(0,index),new String(Character.toChars(points[index])))!=null;
        counters.event(page.name(),recorded?"source_record":"source_reverse");
        List<List<String>> sounds=new ArrayList<>();int at=base;
        for(int i=0;i<points.length;i++){
            String ch=new String(Character.toChars(points[i]));String known=composing?page.buffer().syllables().get(i):page.reverseOnly()?null:records.reading(at,ch);
            sounds.add(known==null?tracks.readings(ch):Collections.singletonList(known));at+=ch.length();
        }
        return tracks.grid(sounds,index);

    }
    protected void gridEvent(String action,int index,int rank,boolean personal){if("opened".equals(action))counters.open(page.name());else if("replaced".equals(action))counters.choose(rank);}
    void selection(int start,int end,int composingLeft,int composingEnd){selection(page,start,end,composingLeft,composingEnd);}
    void cancel(boolean clear){dismissMarker();lastTapOwner=null;dismissGrid();if(selectionTask!=null)ui.removeCallbacks(selectionTask);selectionTask=null;if(clear){forgetPositions();witness=null;beforeWrite=null;compositionStart=compositionEnd=-1;anchor=null;}}
    void recordCommit(String text,List<String> sounds){
        if(service.dualHandProtected()){cancel(true);return;}Snapshot now=snapshot();
        if(now==null||now.start!=now.end||!text.equals(now.range(now.end-text.length(),now.end)))return;
        validate(now);records.record(now.end-text.length(),text,sounds,tracks,false);witness=now;
    }
}
