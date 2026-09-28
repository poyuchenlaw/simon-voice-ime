package com.simon.voiceime;

/** Simon's fixed nine-key groups. Kept separate from the traditional 41-key layout. */
final class T9KeyMap {
    static final String[] GROUPS = {
            "ㄅㄆㄇㄈ", "ㄉㄊㄋㄌ", "ㄍㄎㄏ", "ㄐㄑㄒ", "ㄓㄔㄕㄖ",
            "ㄗㄘㄙ", "ㄧㄨㄩ", "ㄚㄛㄜㄝㄞㄟㄠㄡ", "ㄢㄣㄤㄥㄦ"
    };
    private T9KeyMap() {}
    static boolean isDigit(String key) { return key != null && key.length() == 1 && key.charAt(0) >= '1' && key.charAt(0) <= '9'; }
    static int digitForSymbol(String symbol) {
        if(symbol==null||symbol.codePointCount(0,symbol.length())!=1)return -1;
        for(int i=0;i<GROUPS.length;i++)if(GROUPS[i].contains(symbol))return i+1;
        return -1;
    }
    static String fullPressSequence(String symbols) {
        StringBuilder out=new StringBuilder();for(int i=0;i<symbols.length();){int n=Character.charCount(symbols.codePointAt(i));String cp=symbols.substring(i,i+n);int key=digitForSymbol(cp);if(key<0)throw new IllegalArgumentException("Not a mapped Zhuyin symbol");out.append((char)('0'+key));i+=n;}return out.toString();
    }
    static String twoPress(int consonantGroup,int finalGroup) {
        if(consonantGroup<1||consonantGroup>9||finalGroup<1||finalGroup>9)throw new IllegalArgumentException("T9 groups must be 1..9");
        return ""+(char)('0'+consonantGroup)+(char)('0'+finalGroup);
    }
}
