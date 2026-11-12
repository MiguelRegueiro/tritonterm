package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TerminalSessionTest {

    @Test
    public void hiddenCompletionMessageDoesNotDependOnExitStatus() {
        assertNull(TerminalSession.processCompletionMessage(false, 0));
        assertNull(TerminalSession.processCompletionMessage(false, 23));
        assertNull(TerminalSession.processCompletionMessage(false, -9));
    }

    @Test
    public void enabledCompletionMessageStillDescribesExitAndSignalStatuses() {
        assertEquals("\r\n[Process completed - press Enter]",
            TerminalSession.processCompletionMessage(true, 0));
        assertEquals("\r\n[Process completed (code 23) - press Enter]",
            TerminalSession.processCompletionMessage(true, 23));
        assertEquals("\r\n[Process completed (signal 9) - press Enter]",
            TerminalSession.processCompletionMessage(true, -9));
    }
}
