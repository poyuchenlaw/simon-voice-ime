package com.simon.voiceime;
import java.util.ArrayList;
import java.util.List;
/** Predict self edits in UTF-16; tolerate intermediate and delayed editor notifications. */
final class CursorTapGuard {
 private final List<long[]> selfSelections=new ArrayList<>();
 private int start=-1,end=-1,composingStart=-1,composingEnd=-1;
 private boolean selfUpdate;
 boolean selfUpdate(){return selfUpdate;}
 void observe(int left,int right,int compositionLeft,int compositionRight){
  expire();if(!selfSelections.isEmpty())return;
  start=Math.min(left,right);end=Math.max(left,right);
  composingStart=compositionLeft;composingEnd=compositionRight;
 }
 void replace(int length,int position,boolean composing){
  expire();if(start<0)return;
  int left=composingStart>=0?composingStart:start;
  int cursor=Math.max(0,left+(position>0?length+position-1:position));
  predict(cursor,cursor);
  composingStart=composing?left:-1;composingEnd=composing?left+length:-1;
 }
 void delete(int left,int right){
  expire();if(start<0)return;
  int boundary=composingStart>=0?Math.min(start,composingStart):start;
  int removed=Math.min(left,boundary);predict(start-removed,end-removed);
  if(composingStart>=0){composingStart-=removed;composingEnd-=removed;}
 }
 void select(int left,int right){predict(Math.min(left,right),Math.max(left,right));}
 void composingRegion(int left,int right){composingStart=Math.min(left,right);composingEnd=Math.max(left,right);}
 void finishComposition(){composingStart=composingEnd=-1;}
 private void predict(int left,int right){
  if(left<0||right<0)return;
  expire();selfSelections.add(new long[]{left,right,System.nanoTime(),start,end});
  start=left;end=right;
 }
 void selfSelection(int start,int end){
  if(start<0||end<0)return;
  expire();
  selfSelections.add(new long[]{start,end,System.nanoTime(),-1,-1});
 }
 private void expire(){long now=System.nanoTime();selfSelections.removeIf(value->now-value[2]>2_000_000_000L);}
 void reset(){selfUpdate=false;selfSelections.clear();start=end=composingStart=composingEnd=-1;}
 boolean allow(int oldStart,int oldEnd,int start,int end,boolean composing,boolean protectedField){
  expire();boolean self=false;
  for(int i=0;i<selfSelections.size();i++)if(selfSelections.get(i)[0]==start&&selfSelections.get(i)[1]==end){
   int matched=i;
   // A jump can acknowledge a whole edit batch, including a repeated cursor position.
   if(selfSelections.get(i)[3]!=oldStart||selfSelections.get(i)[4]!=oldEnd)
    for(int n=i+1;n<selfSelections.size();n++)if(selfSelections.get(n)[0]==start&&selfSelections.get(n)[1]==end)matched=n;
   selfSelections.subList(0,matched+1).clear();self=true;break;
  }
  selfUpdate=self;
  return !self&&!composing&&!protectedField&&start>=0&&start==end&&(oldStart!=start||oldEnd!=end);
 }
}
