package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalEmulatorTest {

    @Test
    public void synchronizedUpdateModeCanSpanOutputChunksAndEndsOnReset() {
        TerminalEmulator emulator = new TerminalEmulator(new RecordingOutput(), 20, 5, 100, null);

        append(emulator, "\u001b[?20");
        assertFalse(emulator.isSynchronizedUpdateInProgress());

        append(emulator, "26hframe contents");
        assertTrue(emulator.isSynchronizedUpdateInProgress());

        append(emulator, "\u001b[?2026l");
        assertFalse(emulator.isSynchronizedUpdateInProgress());
    }

    @Test
    public void terminalResetEndsSynchronizedUpdateMode() {
        TerminalEmulator emulator = new TerminalEmulator(new RecordingOutput(), 20, 5, 100, null);
        append(emulator, "\u001b[?2026hunfinished frame");

        assertTrue(emulator.isSynchronizedUpdateInProgress());
        emulator.reset();
        assertFalse(emulator.isSynchronizedUpdateInProgress());
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class RecordingOutput extends TerminalOutput {
        @Override
        public void write(byte[] data, int offset, int count) {}

        @Override
        public void titleChanged(String oldTitle, String newTitle) {}

        @Override
        public void onCopyTextToClipboard(String text) {}

        @Override
        public void onPasteTextFromClipboard() {}

        @Override
        public void onBell() {}

        @Override
        public void onColorsChanged() {}
    }
}
