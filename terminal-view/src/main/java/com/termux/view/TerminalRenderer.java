package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    /** Cursor thickness settings are calibrated at TritonTerm's 32 px default font size. */
    private static final float CURSOR_THICKNESS_REFERENCE_TEXT_SIZE = 32f;

    private static final int METRIC_AFFECTING_EFFECTS = TextStyle.CHARACTER_ATTRIBUTE_BOLD |
        TextStyle.CHARACTER_ATTRIBUTE_BLINK | TextStyle.CHARACTER_ATTRIBUTE_ITALIC;

    final int mTextSize;
    final Typeface mTypeface;
    final Typeface mBoxDrawingTypeface;
    final Typeface mNerdTypeface;
    private final Paint mTextPaint = new Paint();
    /** Measurement paint is isolated from drawTextRun's bold/italic/typeface state. */
    private final Paint mMeasurePaint = new Paint();
    /** Reused per-row text runs avoid allocating short-lived objects for every frame. */
    private final ArrayList<TextRun> mTextRuns = new ArrayList<>(128);
    private float mCursorBeamThickness = 1.5f;
    private float mCursorUnderlineThickness = 2f;

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, null, null);
    }

    public TerminalRenderer(int textSize, Typeface typeface, Typeface boxDrawingTypeface, Typeface nerdTypeface) {
        mTextSize = textSize;
        mTypeface = typeface;
        mBoxDrawingTypeface = boxDrawingTypeface;
        mNerdTypeface = nerdTypeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        mMeasurePaint.setTypeface(typeface);
        mMeasurePaint.setAntiAlias(true);
        mMeasurePaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    public void setCursorThickness(float beamThickness, float underlineThickness) {
        // Keep cursor weight proportional to the cell as the user zooms. The config
        // value is the thickness at the reference text size; the renderer is rebuilt
        // for every zoom step, so derive the current pixel width from its text size.
        float zoomScale = mTextSize / CURSOR_THICKNESS_REFERENCE_TEXT_SIZE;
        mCursorBeamThickness = Math.max(0.5f, beamThickness * zoomScale);
        mCursorUnderlineThickness = Math.max(0.5f, underlineThickness * zoomScale);
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        Typeface measuredTypeface = mTypeface;
        boolean measuredFakeBold = false;
        float measuredTextSkew = 0f;
        for (int row = topRow; row < endRow; row++) {
            mTextPaint.setTypeface(mTypeface);
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;
            Typeface lastRunTypeface = mTypeface;
            int runCount = 0;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final Typeface codePointTypeface = typefaceForCodePoint(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                final int effect = TextStyle.decodeEffect(style);
                final float measuredCodePointWidth;
                if (codePoint < asciiMeasures.length && (effect & METRIC_AFFECTING_EFFECTS) == 0) {
                    measuredCodePointWidth = asciiMeasures[codePoint];
                } else {
                    // Measurement stays separate from drawing Paint state. Avoid resetting
                    // Paint attributes when adjacent special glyphs share the same style.
                    boolean fakeBold = (effect &
                        (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
                    float textSkew = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? -0.35f : 0.f;
                    if (measuredTypeface != codePointTypeface) {
                        mMeasurePaint.setTypeface(codePointTypeface);
                        measuredTypeface = codePointTypeface;
                    }
                    if (measuredFakeBold != fakeBold) {
                        mMeasurePaint.setFakeBoldText(fakeBold);
                        measuredFakeBold = fakeBold;
                    }
                    if (measuredTextSkew != textSkew) {
                        mMeasurePaint.setTextSkewX(textSkew);
                        measuredTextSkew = textSkew;
                    }
                    measuredCodePointWidth = mMeasurePaint.measureText(line, currentCharIndex, charsForCodePoint);
                }
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection ||
                    codePointTypeface != lastRunTypeface || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        addTextRun(runCount++, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex,
                            charsSinceLastRun, measuredWidthForRun, cursorColor, mEmulator.getCursorTextColor(), cursorShape, lastRunStyle,
                            reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunTypeface);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunTypeface = codePointTypeface;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            addTextRun(runCount++, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, mEmulator.getCursorTextColor(), cursorShape, lastRunStyle,
                reverseVideo || invertCursorTextColor || lastRunInsideSelection, lastRunTypeface);

            // Draw every cell background before any glyph. Nerd Font symbols may
            // intentionally overhang their advance into the following spacer cell;
            // painting that cell's selection background afterward clipped the icon.
            for (int runIndex = 0; runIndex < runCount; runIndex++) {
                drawTextRun(canvas, line, palette, heightOffset, mTextRuns.get(runIndex), false);
            }
            for (int runIndex = 0; runIndex < runCount; runIndex++) {
                drawTextRun(canvas, line, palette, heightOffset, mTextRuns.get(runIndex), true);
            }
        }
    }

    private void addTextRun(int index, int startColumn, int widthColumns, int startCharIndex, int widthChars,
                            float measuredWidth, int cursorColor, int cursorTextColor, int cursorStyle,
                            long textStyle, boolean reverseVideo, Typeface typeface) {
        if (index == mTextRuns.size()) {
            mTextRuns.add(new TextRun(startColumn, widthColumns, startCharIndex, widthChars, measuredWidth,
                cursorColor, cursorTextColor, cursorStyle, textStyle, reverseVideo, typeface));
        } else {
            mTextRuns.get(index).set(startColumn, widthColumns, startCharIndex, widthChars, measuredWidth,
                cursorColor, cursorTextColor, cursorStyle, textStyle, reverseVideo, typeface);
        }
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, TextRun run, boolean drawForeground) {
        final int startColumn = run.startColumn;
        final int runWidthColumns = run.widthColumns;
        final int startCharIndex = run.startCharIndex;
        final int runWidthChars = run.widthChars;
        float mes = run.measuredWidth;
        final int cursor = run.cursorColor;
        final int cursorStyle = run.cursorStyle;
        final long textStyle = run.textStyle;
        final boolean reverseVideo = run.reverseVideo;
        final Typeface runTypeface = run.typeface;
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        if (cursor != 0 && cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            foreColor = run.cursorTextColor;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (!drawForeground && backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (!drawForeground && cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                cursorHeight = Math.min(cursorHeight, mCursorUnderlineThickness);
            } else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                right = Math.min(right, left + mCursorBeamThickness);
            }
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if (drawForeground && (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);
            mTextPaint.setTypeface(runTypeface);

            // The text alignment is the default Paint.Align.LEFT.
            final float baseline = y - mFontLineSpacingAndAscent;
            if (runTypeface == mBoxDrawingTypeface) {
                // Hack's box glyphs extend past its font line spacing to overlap
                // adjacent rows. Stretch them to the Noto row pitch so Android
                // preserves that overlap with the Noto text face as primary.
                final float boxScaleY = mFontLineSpacing / mTextPaint.getFontSpacing();
                canvas.save();
                canvas.scale(1.f, boxScaleY, left, baseline);
                canvas.drawText(text, startCharIndex, runWidthChars, left, baseline, mTextPaint);
                canvas.restore();
            } else {
                canvas.drawText(text, startCharIndex, runWidthChars, left, baseline, mTextPaint);
            }
        }

        if (savedMatrix) canvas.restore();
    }

    private Typeface typefaceForCodePoint(int codePoint) {
        if (mBoxDrawingTypeface != null && codePoint >= 0x2500 && codePoint <= 0x259F) {
            return mBoxDrawingTypeface;
        }
        if (mNerdTypeface != null && isPrivateUseCodePoint(codePoint)) return mNerdTypeface;
        return mTypeface;
    }

    private static boolean isPrivateUseCodePoint(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF) ||
            (codePoint >= 0xF0000 && codePoint <= 0xFFFFD);
    }

    private static final class TextRun {
        int startColumn;
        int widthColumns;
        int startCharIndex;
        int widthChars;
        float measuredWidth;
        int cursorColor;
        int cursorTextColor;
        int cursorStyle;
        long textStyle;
        boolean reverseVideo;
        Typeface typeface;

        TextRun(int startColumn, int widthColumns, int startCharIndex, int widthChars, float measuredWidth,
                int cursorColor, int cursorTextColor, int cursorStyle, long textStyle, boolean reverseVideo, Typeface typeface) {
            set(startColumn, widthColumns, startCharIndex, widthChars, measuredWidth,
                cursorColor, cursorTextColor, cursorStyle, textStyle, reverseVideo, typeface);
        }

        void set(int startColumn, int widthColumns, int startCharIndex, int widthChars, float measuredWidth,
                 int cursorColor, int cursorTextColor, int cursorStyle, long textStyle, boolean reverseVideo, Typeface typeface) {
            this.startColumn = startColumn;
            this.widthColumns = widthColumns;
            this.startCharIndex = startCharIndex;
            this.widthChars = widthChars;
            this.measuredWidth = measuredWidth;
            this.cursorColor = cursorColor;
            this.cursorTextColor = cursorTextColor;
            this.cursorStyle = cursorStyle;
            this.textStyle = textStyle;
            this.reverseVideo = reverseVideo;
            this.typeface = typeface;
        }
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
