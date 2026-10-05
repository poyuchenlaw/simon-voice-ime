package com.simon.voiceime;
import java.util.*;
public class WordCursor670HostTest {
 public static void main(String[] a)throws Exception{
  for(int boundary:new int[]{0,22,45}){
  RimeZhuyinEngine engine=new RimeZhuyinEngine(a[0],a[1]);ZhuyinInputController c=new ZhuyinInputController(engine);c.setTextLayout(true);c.setRetypeEngineFactory(()->new RimeZhuyinEngine(a[0],a[1]));
  try{for(int n=0;n<11;n++)WrapCommit670HostTest.type(c,"ㄐㄧㄣ ㄊㄧㄢ ");WrapCommit670HostTest.type(c,"ㄨㄛˇ");for(int n=0;n<11;n++)WrapCommit670HostTest.type(c,"ㄐㄧㄣ ㄊㄧㄢ ");
   {
    String before=c.previewText();c.moveCursorToPreviewBoundary(boundary);
    try(RimeZhuyinEngine p=(RimeZhuyinEngine)engine.copyForTextEdit()){
     p.setTextLayout(false);p.moveCursorToPreviewCharacter(Math.max(1,boundary)-1);System.out.println("focus boundary="+boundary+" native word range="+Arrays.toString(p.previewEditRange())+" labels="+p.regroupLabels().subList(0,Math.min(8,p.regroupLabels().size())));
    }
    int from=boundary==0?0:boundary-2;String old=before.substring(from,from+2);if(!old.equals("今天"))throw new AssertionError("independent known word fixture");ZhuyinInputController.TextChoice pick=null;
    for(ZhuyinInputController.TextChoice q:c.textChoices())if(q.kind.equals("word")&&q.label.codePointCount(0,q.label.length())==2&&!q.label.equals(old)){pick=q;break;}
    if(pick==null)throw new AssertionError("no two-character word at boundary="+boundary);
    c.chooseTextCandidate(pick);String expected=before.substring(0,from)+pick.label+before.substring(from+2);System.out.println("boundary="+boundary+" label="+pick.label+" expected="+expected+" actual="+c.previewText());
    if(!expected.equals(c.previewText()))throw new AssertionError("word must replace selected adjacent span and preserve context");

   }
  }finally{c.close();}
  }
 }
}
