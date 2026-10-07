package com.simon.voiceime;
import java.util.*;import java.nio.file.*;
/** Tone-preserving public-controller regression; literal expectations are specification inputs. */
public final class ToneBoundaryHostTest {
 public static void main(String[] args)throws Exception{
  String[][] cases={{"ㄉㄜ˙","的"},{"ㄕˋ","是"},{"ㄨㄛˇ","我"},{"ㄅㄨˋ","不"},{"ㄖㄣˊ","人"},{"ㄘㄚ ","擦"},{"ㄏㄠˇ","好"},{"ㄊㄞˊ","臺"},{"ㄌㄧㄣˊ","林"}};
  ZhuyinInputController c=new ZhuyinInputController(new RimeZhuyinEngine(args[0],args[1]));try{
   c.setLearningEnabled(false);c.setTextLayout(true);
   for(String[] row:cases){
    c.clear();for(char k:row[0].toCharArray())c.press(k==' '?"space":String.valueOf(k));
    String typed=row[0].replace(' ','ˉ');
    if(!c.phoneticSyllables().equals(Arrays.asList(typed)))throw new AssertionError("tone changed or split "+typed+" "+c.phoneticSyllables());
    System.out.println("RANK "+typed+" preview="+c.textPreview());
    if(!c.textPreview().equals(row[1]))throw new AssertionError("rank changed for "+typed+": "+c.textPreview());
   }
   if(args.length>2)for(String input:Files.readAllLines(Paths.get(args[2]))){
    c.clear();for(char k:input.toCharArray())c.press(k==' '?"space":String.valueOf(k));
    System.out.println("INVENTORY\t"+input.replace(' ','ˉ')+"\t"+c.textPreview()+"\t"+String.join("|",c.phoneticSyllables()));
   }
  }
  finally{c.close();}
  System.out.println("PASS tone/rank examples="+cases.length);
 }
}
