package com.termux.view;

/** Pure row/pixel conversion used by terminal scrollback gestures. */
final class TerminalScrollMath {

    private TerminalScrollMath() {}

    static int wholeRows(float pixels, float lineSpacing) {
        return (int) (pixels / lineSpacing);
    }

    static float fractionalPixels(float pixels, int wholeRows, float lineSpacing) {
        return pixels - wholeRows * lineSpacing;
    }
}
