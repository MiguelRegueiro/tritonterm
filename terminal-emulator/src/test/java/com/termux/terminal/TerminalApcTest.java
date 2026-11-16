package com.termux.terminal;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;

public class TerminalApcTest {
    @Test
    public void unsupportedGraphicsQueriesDoNotPrintTheirPayload() {
        TerminalEmulator emulator = create();
        append(emulator, "before\u001b_Gi=123,s=1,v=1,a=q,t=d,f=24;AAAA\u001b\\after");
        assertEquals("beforeafter", emulator.getScreen().getTranscriptText().trim());
    }

    @Test
    public void applicationCommandsAndTheirTerminatorsCanSpanChunks() {
        TerminalEmulator emulator = create();
        append(emulator, "A\u001b");
        append(emulator, "_Gpayload\u001b[31m\n\u0007");
        append(emulator, "\u001b");
        append(emulator, "\\B");
        assertEquals("AB", emulator.getScreen().getTranscriptText().trim());
    }

    @Test
    public void cancellationAndResetRecoverNormalText() {
        TerminalEmulator emulator = create();
        append(emulator, "\u001b_Gcancel\u0018A\u001b_Gcancel\u001aB");
        assertEquals("AB", emulator.getScreen().getTranscriptText().trim());
        emulator = create();
        append(emulator, "\u001b_Gunfinished");
        emulator.reset();
        append(emulator, "D");
        assertEquals("D", emulator.getScreen().getTranscriptText().trim());
    }

    @Test
    public void largeUnsupportedPayloadIsDiscardedWithoutAccumulation() {
        TerminalEmulator emulator = create();
        append(emulator, "A\u001b_G");
        for (int i = 0; i < 100; i++) append(emulator, "x".repeat(1024));
        append(emulator, "\u001b\\B");
        assertEquals("AB", emulator.getScreen().getTranscriptText().trim());
    }

    private static TerminalEmulator create() {
        return new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        }, 80, 5, 100, null);
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }
}
