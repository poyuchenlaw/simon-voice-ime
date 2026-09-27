package com.simon.voiceime;

/** Standard US-QWERTY physical-key mapping expected by libchewing. */
final class ZhuyinKeyMap {
    private ZhuyinKeyMap() {}
    static int physicalKey(String symbol) {
        final String symbols = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˊˇˋ˙";
        final String keys = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-6347";
        int index = symbols.indexOf(symbol);
        if (index < 0 || index >= keys.length()) return -1;
        return keys.charAt(index);
    }
}
