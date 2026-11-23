package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSetupProgressTest {
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
