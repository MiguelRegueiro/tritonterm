package com.termux.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.tritonterm.android.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class TerminalRendererFontRoutingTest {

    @Test
    public void verticalBoxDrawingGlyphsOverlapAdjacentRows() {
        Context context = ApplicationProvider.getApplicationContext();
        Typeface text = context.getResources().getFont(R.font.noto_sans_mono);
        Typeface boxes = context.getResources().getFont(R.font.hack_nerd_font_mono_regular);
        Typeface nerd = context.getResources().getFont(R.font.jetbrains_mono_nerd_font_regular);
        TerminalRenderer renderer = new TerminalRenderer(28, text, boxes, nerd);
        TerminalEmulator emulator = new TerminalEmulator(new TestOutput(), 4, 4, null, null);
        append(emulator, "│\r\n│\r\n│");

        Bitmap bitmap = render(renderer, emulator, 4);
        int x = Math.round(renderer.getFontWidth() / 2f);
        int gapOffset = renderer.getFontLineSpacing() / 4;
        for (int boundary = 1; boundary < 3; boundary++) {
            int y = boundary * renderer.getFontLineSpacing() + gapOffset;
            assertTrue("box drawing seam between rows " + (boundary - 1) + " and " + boundary,
                hasInk(bitmap, x, y));
        }
        bitmap.recycle();
    }

    @Test
    public void nerdFontIconOverhangRemainsVisibleInFollowingBlankCell() {
        Context context = ApplicationProvider.getApplicationContext();
        Typeface text = context.getResources().getFont(R.font.noto_sans_mono);
        Typeface boxes = context.getResources().getFont(R.font.hack_nerd_font_mono_regular);
        Typeface nerd = context.getResources().getFont(R.font.jetbrains_mono_nerd_font_regular);
        TerminalRenderer renderer = new TerminalRenderer(28, text, boxes, nerd);
        TerminalEmulator emulator = new TerminalEmulator(new TestOutput(), 6, 2, null, null);
        append(emulator, "\uF07B x");

        Bitmap bitmap = render(renderer, emulator, 2);
        int left = Math.round(renderer.getFontWidth());
        int right = Math.round(renderer.getFontWidth() * 2f);
        int top = 0;
        int bottom = renderer.getFontLineSpacing();
        assertTrue("JetBrains folder glyph should keep its right-side detail in the blank cell",
            hasInk(bitmap, left, top, right, bottom));
        bitmap.recycle();
    }

    @Test
    public void selectedNerdIconOverhangIsDrawnAfterFollowingCellBackground() {
        Context context = ApplicationProvider.getApplicationContext();
        Typeface text = context.getResources().getFont(R.font.noto_sans_mono);
        Typeface boxes = context.getResources().getFont(R.font.hack_nerd_font_mono_regular);
        Typeface nerd = context.getResources().getFont(R.font.jetbrains_mono_nerd_font_regular);
        TerminalRenderer renderer = new TerminalRenderer(28, text, boxes, nerd);
        TerminalEmulator emulator = new TerminalEmulator(new TestOutput(), 4, 1, null, null);
        int selectedBackground = Color.rgb(40, 70, 110);
        append(emulator, "\u001b[1;38;2;255;150;50;48;2;40;70;110m\uF03D7" +
            "\u001b[38;2;255;255;255m ");

        Bitmap bitmap = render(renderer, emulator, 1);
        int cellWidth = Math.round(renderer.getFontWidth());
        boolean iconOverhangVisible = false;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = cellWidth; x < Math.min(bitmap.getWidth(), cellWidth * 2); x++) {
                if (bitmap.getPixel(x, y) != selectedBackground) iconOverhangVisible = true;
            }
        }
        assertTrue("selected row background must not paint over the icon's right-side overhang", iconOverhangVisible);
        bitmap.recycle();
    }

    @Test
    public void boldNerdIconDoesNotChangeMetricsOfFollowingText() {
        Context context = ApplicationProvider.getApplicationContext();
        Typeface text = context.getResources().getFont(R.font.noto_sans_mono);
        Typeface boxes = context.getResources().getFont(R.font.hack_nerd_font_mono_regular);
        Typeface nerd = context.getResources().getFont(R.font.jetbrains_mono_nerd_font_regular);
        TerminalRenderer renderer = new TerminalRenderer(28, text, boxes, nerd);
        TerminalEmulator withIcon = new TerminalEmulator(new TestOutput(), 5, 1, null, null);
        TerminalEmulator reference = new TerminalEmulator(new TestOutput(), 5, 1, null, null);
        append(withIcon, "\u001b[1;7m\uF07B\u001b[0m  x");
        append(reference, "   x");

        Bitmap actual = render(renderer, withIcon, 1);
        Bitmap expected = render(renderer, reference, 1);
        int left = Math.round(renderer.getFontWidth() * 3f);
        int right = Math.round(renderer.getFontWidth() * 4f);
        for (int y = 0; y < renderer.getFontLineSpacing(); y++) {
            for (int x = left; x < right; x++) {
                assertEquals("text after a styled Nerd Font icon should keep its normal cell metrics",
                    expected.getPixel(x, y), actual.getPixel(x, y));
            }
        }
        actual.recycle();
        expected.recycle();
    }

    private static Bitmap render(TerminalRenderer renderer, TerminalEmulator emulator, int rows) {
        int width = Math.max(1, (int) Math.ceil(renderer.getFontWidth() * emulator.mColumns));
        int height = renderer.getFontLineSpacing() * rows;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1);
        return bitmap;
    }

    private static boolean hasInk(Bitmap bitmap, int x, int y) {
        return Color.red(bitmap.getPixel(x, y)) > 32 ||
            Color.green(bitmap.getPixel(x, y)) > 32 ||
            Color.blue(bitmap.getPixel(x, y)) > 32;
    }

    private static boolean hasInk(Bitmap bitmap, int left, int top, int right, int bottom) {
        for (int y = Math.max(0, top); y < Math.min(bitmap.getHeight(), bottom); y++) {
            for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) {
                if (hasInk(bitmap, x, y)) return true;
            }
        }
        return false;
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class TestOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onCopyTextToClipboard(String text) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }
}
