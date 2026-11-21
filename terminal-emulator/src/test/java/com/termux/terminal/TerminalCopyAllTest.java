package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TerminalCopyAllTest {
    @Test
    public void repeatedCopiesIncludeOutputAddedSinceTheFirstCopy() {
        RecordingOutput output = new RecordingOutput();
        TerminalEmulator emulator = emulator(output, 40, 4);
        append(emulator, "first result");
        assertTrue(emulator.copyAllTextToClipboard());
        append(emulator, "\r\nsecond result");
        assertTrue(emulator.copyAllTextToClipboard());
        assertEquals("first result", output.copies.get(0));
        assertEquals("first result\nsecond result", output.copies.get(1));
    }

    @Test
    public void copiesRetainedHistoryAlongWithTheVisibleScreen() {
        RecordingOutput output = new RecordingOutput();
        TerminalEmulator emulator = emulator(output, 40, 2);
        append(emulator, "one\r\ntwo\r\nthree\r\nfour");
        assertTrue(emulator.getScreen().getActiveTranscriptRows() > 0);
        assertTrue(emulator.copyAllTextToClipboard());
        assertEquals("one\ntwo\nthree\nfour", output.copies.get(0));
    }

    @Test
    public void clearedHistoryAndScreenDoNotReappearInTheNextCopy() {
        RecordingOutput output = new RecordingOutput();
        TerminalEmulator emulator = emulator(output, 40, 2);
        append(emulator, "old one\r\nold two\r\nold three");
        assertTrue(emulator.copyAllTextToClipboard());
        append(emulator, "\u001b[3J\u001b[2J\u001b[Hnew result");
        assertTrue(emulator.copyAllTextToClipboard());
        assertEquals("new result", output.copies.get(1));
    }

    @Test
    public void copiesTheActiveBufferAfterEnteringAndLeavingAnAlternateScreen() {
        RecordingOutput output = new RecordingOutput();
        TerminalEmulator emulator = emulator(output, 40, 4);
        append(emulator, "shell output");
        assertTrue(emulator.copyAllTextToClipboard());
        append(emulator, "\u001b[?1049happ screen");
        assertTrue(emulator.copyAllTextToClipboard());
        assertEquals("app screen", output.copies.get(1));
        append(emulator, "\u001b[?1049l");
        assertTrue(emulator.copyAllTextToClipboard());
        assertEquals("shell output", output.copies.get(2));
    }

    @Test
    public void joinsSoftWrappedLinesAndPreservesUnicode() {
        RecordingOutput output = new RecordingOutput();
        TerminalEmulator emulator = emulator(output, 8, 4);
        append(emulator, "abcdefghijk\r\nαρχ 🌊");
        assertTrue(emulator.copyAllTextToClipboard());
        assertEquals("abcdefghijk\nαρχ 🌊", output.copies.get(0));
    }

    @Test
    public void emptyScreenDoesNotPretendToCopyAnOldValue() {
        RecordingOutput output = new RecordingOutput();
        TerminalEmulator emulator = emulator(output, 40, 4);
        assertFalse(emulator.copyAllTextToClipboard());
        assertTrue(output.copies.isEmpty());
        append(emulator, "old result");
        assertTrue(emulator.copyAllTextToClipboard());
        append(emulator, "\u001b[3J\u001b[2J\u001b[H");
        assertFalse(emulator.copyAllTextToClipboard());
        assertEquals(1, output.copies.size());
    }

    private static TerminalEmulator emulator(RecordingOutput output, int columns, int rows) {
        return new TerminalEmulator(output, columns, rows, 100, null);
    }
    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }
    private static final class RecordingOutput extends TerminalOutput {
        final List<String> copies = new ArrayList<>();
        @Override public void onCopyTextToClipboard(String text) { copies.add(text); }
        @Override public void write(byte[] data, int offset, int count) { }
        @Override public void titleChanged(String oldTitle, String newTitle) { }
        @Override public void onPasteTextFromClipboard() { }
        @Override public void onBell() { }
        @Override public void onColorsChanged() { }
    }
}
