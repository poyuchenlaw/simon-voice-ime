package com.simon.voiceime;
import java.nio.file.*;import java.util.*;import org.json.*;
/** Real append-only controller replay, aligned by physical key offsets. */
public final class CorpusHostTest {
 public static void main(String[] a)throws Exception{
  int cases=0,split=0,unmapped=0,observations=0,commitLeaks=0;
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(a[0],a[1]));
  c.setLearningEnabled(false);c.setTextLayout(true);
  try{for(String line:Files.readAllLines(Paths.get(a[2]))){
   JSONObject row=new JSONObject(line);String keys=row.getString("keys");int start=row.getInt("start"),end=row.getInt("end");boolean bad=false,missing=false;c.clear();
   for(int i=0;i<keys.length();i++){
    c.press(keys.charAt(i)==' '?"space":keys.substring(i,i+1));if(i+1<end)continue;
    List<String> reading=c.phoneticSyllables();String joined=String.join("",reading).replace('ˉ',' ');
    if(!joined.equals(keys.substring(0,i+1))){missing=true;continue;}
    observations++;int offset=0;for(String syllable:reading){offset+=syllable.length();if(offset>start&&offset<end){bad=true;break;}}
   }
   String preview=c.textPreview(),committed=c.press("enter").commitText;
   if(!preview.equals(committed)||committed.codePoints().anyMatch(cp->cp>=0x3105&&cp<=0x312f||"ˉˊˇˋ˙".indexOf(cp)>=0||cp>='A'&&cp<='Z'||cp>='a'&&cp<='z')){commitLeaks++;System.out.println("COMMIT_LEAK "+row);}
   cases++;if(bad||missing){if(bad)split++;if(missing)unmapped++;System.out.println("CASE "+cases+" split="+bad+" unmapped="+missing+" "+row);}
  }}finally{c.close();}
  System.out.println("SUMMARY cases="+cases+" observations="+observations+" split="+split+" unmapped="+unmapped+" commit_leaks="+commitLeaks);
  if(split!=0||unmapped!=0||commitLeaks!=0)throw new AssertionError("complete syllable replay failed");
 }
}
