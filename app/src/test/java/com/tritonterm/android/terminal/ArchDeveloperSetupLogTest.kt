package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArchDeveloperSetupLogTest {
    @Test
    fun failureDetailsIgnoreOutputFromEarlierAttempts() {
        val log = """
            [Arch setup] FAILED at developer_environment (exit 23): old failure
            [Arch setup] FAILED at developer_environment (exit 23): command: old command
            [stderr] old stderr
            [Arch setup] ATTEMPT_STARTED phase=account_personalization username=regueiro started=2026-11-13T12:00:00+01:00
            [stderr] usermod: user dev is currently used by process 123
            [Arch setup] FAILED at account_rename (exit 1): command: usermod --login regueiro dev
            [Arch setup] FAILED at account_rename (exit 1): command: usermod --login regueiro dev
        """.trimIndent()

        val details = ArchDeveloperSetupLog.parse(log, fallbackExitCode = 9)

        assertEquals("account_rename", details.stage)
        assertEquals(1, details.exitCode)
        assertEquals("usermod --login regueiro dev", details.command)
        assertEquals(listOf("usermod: user dev is currently used by process 123"), details.stderr)
    }

    @Test
    fun currentAttemptWithoutTrapOutputDoesNotShowStaleFailureOrStderr() {
        val log = """
            [Arch setup] FAILED at developer_environment (exit 23): old failure
            [Arch setup] FAILED at developer_environment (exit 23): command: old command
            [stderr] old stderr
            [Arch setup] ATTEMPT_STARTED phase=account_personalization username=regueiro started=2026-11-13T12:00:00+01:00
            [Arch setup][account_rename] Renaming template login dev to regueiro.
        """.trimIndent()

        val details = ArchDeveloperSetupLog.parse(log, fallbackExitCode = 1)

        assertEquals(null, details.stage)
        assertEquals(null, details.command)
        assertEquals(1, details.exitCode)
        assertFalse(details.stderr.any { it.contains("old stderr") })
    }
}
