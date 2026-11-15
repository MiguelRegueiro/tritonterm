package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KittyUserVariableTest {

    @Test
    public void userVariablesCanBeSetAndUnsetAcrossOutputChunks() {
        TerminalEmulator emulator = new TerminalEmulator(new RecordingOutput(), 20, 5, 100, null);

        append(emulator, "\u001b]1337;SetUserVar=elio=MQ==\u0007");
        assertTrue(emulator.getUserVariables().contains("elio"));

        append(emulator, "\u001b]1337;SetUserVar=elio\u0007");
        assertFalse(emulator.getUserVariables().contains("elio"));
    }

    @Test
    public void malformedUserVariableDoesNotActivateAKeyMap() {
        TerminalEmulator emulator = new TerminalEmulator(new RecordingOutput(), 20, 5, 100, null);

        append(emulator, "\u001b]1337;SetUserVar=elio=%%%\u0007");

        assertFalse(emulator.getUserVariables().contains("elio"));
    }

    private static void append(TerminalEmulator emulator, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class RecordingOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }
}
