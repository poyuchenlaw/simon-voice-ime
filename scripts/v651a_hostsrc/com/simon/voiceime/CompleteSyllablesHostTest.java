package com.simon.voiceime;
import java.nio.file.*;
import java.util.*;
/** Public JNI regression: typed complete syllables remain one reading segment. */
public final class CompleteSyllablesHostTest {
    public static void main(String[] args) throws Exception {
        Path user=Paths.get(args[1]);Files.createDirectories(user);
        try(RimeZhuyinNative engine=new RimeZhuyinNative(args[0],user.toString())) {
            check(engine,"ㄗㄞˋㄉㄨㄛˋㄩˊㄋㄟˋㄖ","ㄉㄨㄛˋ");
            check(engine,"ㄅㄛˊㄗㄞˋㄉㄞˋㄒㄩㄢˇㄑㄧㄥ ㄉㄢ ","ㄒㄩㄢˇ");
        }
        System.out.println("PASS complete-syllable public JNI regression (2 cases)");
    }
    private static void check(RimeZhuyinNative engine,String keys,String syllable) {
        engine.clear();for(char symbol:keys.toCharArray())engine.key(symbol == ' ' ? ' ' : ZhuyinKeyMap.physicalKey(String.valueOf(symbol)));
        String[] parts=engine.readingSyllables();
        System.out.println(Arrays.toString(engine.candidates()) .substring(0, Math.min(60,Arrays.toString(engine.candidates()).length()))+" readings="+Arrays.toString(parts));
        if(!Arrays.asList(parts).contains(syllable))throw new AssertionError("complete syllable split: "+syllable);
    }
}
