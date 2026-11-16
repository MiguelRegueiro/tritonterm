package com.termux.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalScrollMathTest {

    @Test
    public void preservesSubRowMovementUntilItCrossesALine() {
        float accumulatedPixels = 7f;
        int rows = TerminalScrollMath.wholeRows(accumulatedPixels, 20f);

        assertEquals(0, rows);
        assertEquals(7f, TerminalScrollMath.fractionalPixels(accumulatedPixels, rows, 20f), 0f);
    }

    @Test
    public void combinesSmallMovementsWithoutLosingDistance() {
        float accumulatedPixels = 7f + 15f;
        int rows = TerminalScrollMath.wholeRows(accumulatedPixels, 20f);

        assertEquals(1, rows);
        assertEquals(2f, TerminalScrollMath.fractionalPixels(accumulatedPixels, rows, 20f), 0f);
    }

    @Test
    public void retainsNegativeSubRowMovementForScrollingIntoHistory() {
        float accumulatedPixels = -22f;
        int rows = TerminalScrollMath.wholeRows(accumulatedPixels, 20f);

        assertEquals(-1, rows);
        assertEquals(-2f, TerminalScrollMath.fractionalPixels(accumulatedPixels, rows, 20f), 0f);
    }

    @Test
    public void flingPositionUsesTheSameSignedRemainderAsFingerScrolling() {
        float scrollPosition = -35f;
        int topRow = TerminalScrollMath.wholeRows(scrollPosition, 20f);

        assertEquals(-1, topRow);
        assertEquals(-15f, TerminalScrollMath.fractionalPixels(scrollPosition, topRow, 20f), 0f);
    }
}
