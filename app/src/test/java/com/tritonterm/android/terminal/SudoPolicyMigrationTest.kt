package com.tritonterm.android.terminal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SudoPolicyMigrationTest {
    @Test fun upgradesOnlyTheOriginalPolicyAndIsIdempotent() {
        val root = Files.createTempDirectory("sudo-policy").toFile()
        try {
            val policy = root.resolve("etc/sudoers.d/10-tritonterm-pacman")
            policy.parentFile!!.mkdirs()
            policy.writeText("triton ALL=(root) NOPASSWD: /usr/bin/pacman\n")
            assertTrue(SudoPolicyMigration.migrate(root))
            assertEquals("triton ALL=(root) NOPASSWD: ALL\n", policy.readText())
            assertFalse(SudoPolicyMigration.migrate(root))
            policy.writeText("# custom\ntriton ALL=(root) NOPASSWD: /usr/bin/pacman\n")
            assertFalse(SudoPolicyMigration.migrate(root))
            assertTrue(policy.readText().startsWith("# custom"))
        } finally { root.deleteRecursively() }
    }
}
