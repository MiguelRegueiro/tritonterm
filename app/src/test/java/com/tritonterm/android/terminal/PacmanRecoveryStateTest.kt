package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacmanRecoveryStateTest {
    @Test
    fun lockWarningDistinguishesObservedTransactionFromPossibleStaleLock() {
        assertEquals(PacmanRecoveryState.LockState.CLEAR, PacmanRecoveryState.classify(false, false))
        assertEquals(PacmanRecoveryState.LockState.ACTIVE_TRANSACTION, PacmanRecoveryState.classify(true, true))
        assertEquals(PacmanRecoveryState.LockState.POSSIBLY_STALE, PacmanRecoveryState.classify(true, false))
    }

    @Test
    fun onlyWarnsForALockThatPersistsWithoutPacman() {
        assertEquals(false, PacmanRecoveryState.shouldWarn(PacmanRecoveryState.LockState.CLEAR, 60_000))
        assertEquals(false, PacmanRecoveryState.shouldWarn(PacmanRecoveryState.LockState.ACTIVE_TRANSACTION, 60_000))
        assertEquals(false, PacmanRecoveryState.shouldWarn(PacmanRecoveryState.LockState.POSSIBLY_STALE, 4_999))
        assertEquals(true, PacmanRecoveryState.shouldWarn(PacmanRecoveryState.LockState.POSSIBLY_STALE, 5_000))
    }

    @Test
    fun processCheckMatchesOnlyPacmanArgvZeroNotShellCommandText() {
        assertEquals(true, PacmanRecoveryState.isPacmanArgv0("/usr/bin/pacman\u0000-Syu\u0000"))
        assertEquals(true, PacmanRecoveryState.isPacmanArgv0("pacman\u0000-Qq\u0000"))
        assertEquals(false, PacmanRecoveryState.isPacmanArgv0("/bin/bash\u0000-lc\u0000pacman -Syu"))
        assertEquals(false, PacmanRecoveryState.isPacmanArgv0("/usr/bin/pgrep\u0000-x\u0000pacman"))
    }

    @Test
    fun installerProgressStopsPollingExtractionCountersWhenDeveloperSetupBegins() {
        assertEquals(
            true,
            TerminalSetupProgressFile.shouldPollInstaller(
                ArchInstallState(running = true, stage = TerminalSetupStage.INSTALLING)
            )
        )
        assertEquals(
            false,
            TerminalSetupProgressFile.shouldPollInstaller(
                ArchInstallState(running = true, stage = TerminalSetupStage.DEVELOPER_TOOLS)
            )
        )
        assertEquals(
            false,
            TerminalSetupProgressFile.shouldPollInstaller(
                ArchInstallState(running = false, stage = TerminalSetupStage.INSTALLING)
            )
        )
    }

    @Test
    fun setupHeartbeatDoesNotCountAsCommandOutputProgress() {
        val initial = TerminalSetupProgress(TerminalSetupStage.DEVELOPER_TOOLS)
        val heartbeat = "command_still_running label=pacman -Syu pid=12 elapsed_seconds=30"
        val status = initial.withCommandOutput(heartbeat)
        assertEquals(0L, status.activitySequence)
        assertEquals(heartbeat, status.commandStatus)
        val started = status.withCommandOutput("[Arch setup] Running package transaction: pacman -Syu")
        assertEquals(0L, started.activitySequence)
        assertTrue(started.commandStatus!!.contains("pacman -Syu"))
        val updated = started.withCommandOutput("upgrading openssl...")
        assertEquals(1L, updated.activitySequence)
        assertEquals("upgrading openssl...", updated.lastActivity)
    }
}
