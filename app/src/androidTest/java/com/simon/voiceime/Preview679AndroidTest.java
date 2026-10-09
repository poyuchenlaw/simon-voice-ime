package com.simon.voiceime;
import android.widget.*;import android.view.*;import android.graphics.Rect;import android.os.SystemClock;
public class Preview679AndroidTest extends Bianshi678AndroidTest {
 void keys(String value)throws Exception{for(char key:value.toCharArray())tap(key==' '?"空白":String.valueOf(key));}
 TextView candidate(LinearLayout row,String label)throws Exception{
  long deadline=SystemClock.uptimeMillis()+8000;final TextView[] found={null};
  while(SystemClock.uptimeMillis()<deadline){inst.runOnMainSync(()->{for(int n=0;n<row.getChildCount();n++){View item=row.getChildAt(n);if(item instanceof TextView&&item.isEnabled()&&label.contentEquals(((TextView)item).getText())){found[0]=(TextView)item;break;}}});if(found[0]!=null)return found[0];Thread.sleep(50);}
  throw new AssertionError("requested candidate absent: "+label);
 }
 void physicalChoice(TextView item)throws Exception{final Rect bounds=new Rect();inst.runOnMainSync(()->assertTrue(item.getGlobalVisibleRect(bounds)));tap(bounds);Thread.sleep(300);inst.waitForIdleSync();}
 void setupReportedPreview()throws Exception{
  begin();assertEquals(1.3f,row1.getResources().getConfiguration().fontScale,.01f);keys("ㄒㄧㄢˋㄗㄞˋㄧㄠˋㄑㄩㄝˋㄖㄣˋㄓㄨˋ");physicalChoice(candidate(characters,"註"));keys("ㄧㄣ ㄕˋㄈㄡˇㄎㄜˇㄧˇㄕㄨㄣˋㄌㄧˋㄙㄨㄥˋㄔㄨ ");
  String before=shown();int index=before.indexOf("註音");assertTrue("reported wrong word exists in actual preedit",index>=0);final int boundary=index+1;
  final Rect point=new Rect();inst.runOnMainSync(()->{android.text.Layout layout=row1.getLayout();int line=layout.getLineForOffset(boundary);int[] origin=new int[2];row1.getLocationOnScreen(origin);int x=origin[0]+row1.getTotalPaddingLeft()+Math.round(layout.getPrimaryHorizontal(boundary));int y=origin[1]+row1.getTotalPaddingTop()+(layout.getLineTop(line)+layout.getLineBottom(line))/2;point.set(x-1,y-1,x+1,y+1);});
  tap(point);candidate(words,"注音");screenshot("reported-preview-caret");rowTags("reported-preview-row-tags.json");readback("reported-preview-before-text.json");
 }
 public void testPreviewWordReplacement()throws Exception {
  setupReportedPreview();String before=shown();int at=before.indexOf("註音");TextView item=candidate(words,"注音");assertEquals("leftmost word must be 注音",0,words.indexOfChild(item));physicalChoice(item);
  assertEquals("replace only cursor word",before.substring(0,at)+"注音"+before.substring(at+2),shown());assertCandidateDiagnostics(2);readback("reported-preview-word-text.json");screenshot("reported-preview-word-after");
 }
 public void testPreviewCharacterReplacement()throws Exception {
  setupReportedPreview();String before=shown();int at=before.indexOf("註音");physicalChoice(candidate(characters,"注"));
  assertEquals(before.substring(0,at)+"注"+before.substring(at+1),shown());assertCandidateDiagnostics(1);readback("reported-preview-char-text.json");screenshot("reported-preview-char-after");
 }
}
