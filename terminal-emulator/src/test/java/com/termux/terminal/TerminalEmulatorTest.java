package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void attachingViewClientPreservesShellCursorShape() {
        TerminalSessionClient client = new RecordingClient();
        TerminalEmulator emulator = new TerminalEmulator(new RecordingOutput(), 20, 5, 100, client);

        // Fish selects its insert-mode beam during shell startup, before the view attaches.
        append(emulator, "\u001b[6 q");
        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR, emulator.getCursorStyle());

        emulator.updateTerminalSessionClient(client);

        assertEquals(TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR, emulator.getCursorStyle());
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

    private static final class RecordingClient implements TerminalSessionClient {
        @Override public void onTextChanged(TerminalSession session) {}
        @Override public void onTitleChanged(TerminalSession session) {}
        @Override public void onSessionFinished(TerminalSession session) {}
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public Integer getTerminalCursorStyle() { return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK; }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }
}
