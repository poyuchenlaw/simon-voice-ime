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
        String phrase=cleaned.replaceAll("[\\p{P}\\p{Z}\\s]", "");
        if(phrase.equals("謝謝觀看")||phrase.equals("谢谢观看")
                ||phrase.matches("(?:字幕由|字幕制作由|字幕製作由).+提供")
                ||phrase.equals("請不吝點贊訂閱轉發打賞支持明鏡與點點欄目")
                ||phrase.equals("请不吝点赞订阅转发打赏支持明镜与点点栏目"))return true;
        // Symbols ($, arrows, emoji) stay real; only punctuation-only APPEND is silence.
        return append&&!cleaned.isEmpty()&&cleaned.codePoints().allMatch(c ->
                Character.isWhitespace(c)||Character.isSpaceChar(c)
                ||Character.getType(c)==Character.CONNECTOR_PUNCTUATION
                ||Character.getType(c)==Character.DASH_PUNCTUATION
                ||Character.getType(c)==Character.START_PUNCTUATION
                ||Character.getType(c)==Character.END_PUNCTUATION
                ||Character.getType(c)==Character.INITIAL_QUOTE_PUNCTUATION
                ||Character.getType(c)==Character.FINAL_QUOTE_PUNCTUATION
                ||Character.getType(c)==Character.OTHER_PUNCTUATION);
    }
    private VoiceResultText() {}
}
