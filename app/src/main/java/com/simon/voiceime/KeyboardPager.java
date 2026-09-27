package com.simon.voiceime;

/** Pure page order, independent of Android and enum declaration order. */
public final class KeyboardPager {
    public enum KeyboardMode { VOICE, BOPOMOFO, ENGLISH, NUMBERS }
    public enum Direction { LEFT, RIGHT }

    private KeyboardPager() {}

    public static KeyboardMode next(KeyboardMode current, Direction dir) {
        switch (current) {
            case VOICE:
                return dir == Direction.LEFT ? KeyboardMode.BOPOMOFO : KeyboardMode.ENGLISH;
            case BOPOMOFO:
                return dir == Direction.LEFT ? KeyboardMode.ENGLISH : KeyboardMode.VOICE;
            case NUMBERS:
                throw new IllegalArgumentException("NUMBERS is not part of the swipe cycle");
            case ENGLISH:
                return dir == Direction.LEFT ? KeyboardMode.VOICE : KeyboardMode.BOPOMOFO;
            default:
                throw new IllegalArgumentException("Unknown keyboard mode");
        }
    }
}
