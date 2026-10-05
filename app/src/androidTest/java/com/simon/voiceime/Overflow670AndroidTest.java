package com.simon.voiceime;
import android.graphics.Rect;import org.json.*;import java.io.File;import java.util.*;
public class Overflow670AndroidTest extends TextRows669AndroidTest {
 public void testBeyondFirstRowCapacity()throws Exception{
  ready();out=new File(inst.getTargetContext().getFilesDir(),"v666");out.mkdirs();startEndpoint();
  Map<String,Rect> keys=new HashMap<>();String input="ㄐㄧㄣ ";String suffix="ㄟˇㄜ˙ㄎㄢˋㄉㄜ˙ㄔㄨ ㄌㄞˊ";
  for(String seq:new String[]{input,suffix})for(int i=0;i<seq.length();i++){String k=seq.charAt(i)==' '?"空白":seq.substring(i,i+1);if(!keys.containsKey(k)){Rect r=new Rect();await(k).getBoundsInScreen(r);keys.put(k,r);}}
  for(int n=0;n<45;n++)for(int i=0;i<input.length();i++){tap(keys.get(input.charAt(i)==' '?"空白":input.substring(i,i+1)));Thread.sleep(40);}
  inst.runOnMainSync(()->{try{bindWindow();}catch(Exception e){throw new RuntimeException(e);}});
  final JSONObject record=new JSONObject();inst.runOnMainSync(()->{try{int width=((android.view.View)row1.getParent()).getWidth();float needed=row1.getPaint().measureText(row1.getText().toString());record.put("prefix_characters",row1.getText().length()).put("viewport_width",width).put("unwrapped_width",needed);assertTrue("typed beyond one row capacity",needed>width);}catch(Exception e){throw new RuntimeException(e);}});
  for(int i=0;i<suffix.length();i++){tap(keys.get(suffix.charAt(i)==' '?"空白":suffix.substring(i,i+1)));Thread.sleep(80);}
  Thread.sleep(300);final String[] preview={null};inst.runOnMainSync(()->preview[0]=row1.getText().toString());record.put("preview",preview[0]);
  tap("↵");Thread.sleep(300);String committed=String.valueOf(await("test_input").getText());record.put("committed",committed);boolean raw=(preview[0]+committed).codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||"ˊˇˋ˙ˉ".indexOf(cp)>=0);record.put("raw_zhuyin",raw);save("overflow-result.json",record.toString());android.graphics.Bitmap image=ui.takeScreenshot();assertNotNull("screen capture",image);try(java.io.FileOutputStream f=new java.io.FileOutputStream(new File(out,"overflow-screen.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,f);}assertFalse("no raw zhuyin beyond row capacity or in commit",raw);
 }
}
