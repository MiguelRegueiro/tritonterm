package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionRecoveryTest {
    @Test
    fun restartIsAllowedOnlyAfterTheManagedSessionHasExited() {
        assertTrue(TerminalSessionRecovery.canRestart(isRunning = false))
        assertFalse(TerminalSessionRecovery.canRestart(isRunning = true))
    }

    @Test
    fun negativeTerminalExitStatusIsReportedAsObservedSignal() {
        assertEquals(9, TerminalSessionRecovery.signalNumber(-9))
        assertEquals(null, TerminalSessionRecovery.signalNumber(137))
        assertEquals(null, TerminalSessionRecovery.signalNumber(0))
    }

    @Test
    fun exitRecordUsesPidCapturedBeforeSessionCleanup() {
        assertEquals(28830, TerminalSessionRecovery.processPidForExit(28830, -1))
        assertEquals(28830, TerminalSessionRecovery.processPidForExit(null, 28830))
        assertEquals(null, TerminalSessionRecovery.processPidForExit(null, -1))
        assertEquals(null, TerminalSessionRecovery.processPidForExit(0, -1))
    }
}
