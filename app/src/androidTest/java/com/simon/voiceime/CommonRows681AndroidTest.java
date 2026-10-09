package com.simon.voiceime;
import android.view.View;
import android.widget.TextView;
public class CommonRows681AndroidTest extends TapCursor677AndroidTest {
 public void testCommonRows()throws Exception{
  begin();text("我們明天十點在法院見面");tap("test_input");await("ㄗ");bind();awaitStableWindow();tapCaret(9);awaitTapRows(9);rowTags("common-row-tags.json");screenshot("common-two-rows");readback("common-editor-text.json");
  ZhuyinWordIndex index=ZhuyinWordIndex.open(inst.getTargetContext());
  ZhuyinAssociationHistory history=new ZhuyinAssociationHistory(new java.io.File(inst.getTargetContext().getFilesDir(),"zhuyin_associations.tsv"));
  try{inst.runOnMainSync(()->{for(android.widget.LinearLayout row:new android.widget.LinearLayout[]{words,characters})for(int i=0;i<row.getChildCount();i++){
   Object tag=row.getChildAt(i).getTag();if(tag instanceof ZhuyinInputController.TextChoice){String label=((ZhuyinInputController.TextChoice)tag).label;assertTrue(label,CommonCharacters.permits(label,index.isInstalledWord(label)||history.hasUsed(label)));}
  }});}finally{index.close();}
 }
 public void testMoreRareCharacters()throws Exception{
  begin();text("");focusEmptyEditor();for(String key:new String[]{"ㄉ","ㄚ","ˊ"})tap(key);Thread.sleep(400);bind();
  rowTags("rare-common-tags.json");screenshot("rare-common-row");
  inst.runOnMainSync(()->charScroll.fullScroll(View.FOCUS_RIGHT));Thread.sleep(200);tap("更多");Thread.sleep(300);bind();rowTags("rare-more-tags.json");
  final TextView[] target={null};inst.runOnMainSync(()->{for(int i=0;i<characters.getChildCount();i++)if(characters.getChildAt(i) instanceof TextView&&"龘".contentEquals(((TextView)characters.getChildAt(i)).getText()))target[0]=(TextView)characters.getChildAt(i);});
  assertNotNull("rare character accessible through complete-reading More",target[0]);
  inst.runOnMainSync(()->charScroll.scrollTo(target[0].getLeft(),0));Thread.sleep(200);clickCandidate(target[0]);tap("↵");readback("rare-committed-text.json");screenshot("rare-committed");assertEquals("龘",editorText(await("test_input")));
 }
}
