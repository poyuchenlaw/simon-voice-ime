package com.simon.voiceime;

/** Pure page order, independent of Android and enum declaration order. */
public final class KeyboardPager {
    public enum KeyboardMode { VOICE, ENGLISH, NUMBERS }
    public enum Direction { LEFT, RIGHT }

    private KeyboardPager() {}

    public static KeyboardMode next(KeyboardMode current, Direction dir) {
        switch (current) {
            case VOICE:
                return dir == Direction.LEFT ? KeyboardMode.NUMBERS : KeyboardMode.ENGLISH;
            case NUMBERS:
                return dir == Direction.LEFT ? KeyboardMode.ENGLISH : KeyboardMode.VOICE;
            case ENGLISH:
                return dir == Direction.LEFT ? KeyboardMode.VOICE : KeyboardMode.NUMBERS;
            default:
                throw new IllegalArgumentException("Unknown keyboard mode");
        }
    }
}
