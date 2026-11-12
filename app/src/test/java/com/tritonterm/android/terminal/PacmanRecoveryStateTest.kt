package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
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
}
