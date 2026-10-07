package com.simon.voiceime;
import java.util.regex.Pattern;
/** Speech-only normalization; ordinary words containing sil stay intact. */
final class VoiceResultText {
    private static final Pattern SIL=Pattern.compile("(?iu)[<\\[({〈《【（＜]\\s*s\\s*i\\s*l\\s*[>\\])}〉》】）＞]");
    static String clean(String text) {return text==null?"":SIL.matcher(text).replaceAll("").trim();}
    static boolean isSilence(String text) {return clean(text).codePoints().allMatch(c -> Character.isWhitespace(c)||Character.isSpaceChar(c));}
    // Explicit recognizer control markers, not phrases a speaker may genuinely dictate.
    static boolean isHallucinationMarker(String text) {
        return clean(text).matches("(?iu)(?:<\\s*hallucination\\s*>|\\[\\s*(?:hallucination|blank_audio|no_speech)\\s*\\]|<\\|(?:nospeech|endoftext)\\|>)");
    }
    static boolean isNonSpeech(String text,boolean append,long audioMs) {
        String cleaned=clean(text);
        if(isHallucinationMarker(cleaned))return true;
        return isSilence(cleaned);
    }
    private VoiceResultText() {}
}
