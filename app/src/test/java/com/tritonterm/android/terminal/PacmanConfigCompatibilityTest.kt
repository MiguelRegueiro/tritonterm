package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PacmanConfigCompatibilityTest {
    @Test
    fun migrationChangesOnlyAndroidIncompatibleDownloadUserAndFilesystemSandboxSettings() = withRootfs { rootfs ->
        val original = """# Keep this administrator note
[options]
Architecture = auto
DownloadUser = alpm
#DisableSandboxFilesystem
DisableSandboxSyscalls
SigLevel = Required DatabaseOptional

[core]
Include = /etc/pacman.d/mirrorlist
Server = https://example.invalid/${'$'}repo/os/${'$'}arch
"""
        val config = writeConfig(rootfs, original)

        val result = PacmanConfigCompatibility.migrate(rootfs)
        val migrated = config.readText()

        assertTrue(result.changed)
        assertNotNull(result.backup)
        assertEquals(original, result.backup!!.readText())
        assertTrue(migrated.contains("# TritonTerm Android compatibility: DownloadUser = alpm"))
        assertTrue(migrated.contains("DisableSandboxFilesystem\n"))
        assertTrue(migrated.contains("DisableSandboxSyscalls\n"))
        assertTrue(migrated.contains("SigLevel = Required DatabaseOptional"))
        assertTrue(migrated.contains("[core]\nInclude = /etc/pacman.d/mirrorlist"))
        assertFalse(migrated.lineSequence().any { it.trimStart().startsWith("DownloadUser") })
    }

    @Test
    fun migrationIsIdempotentAndDoesNotCreateAdditionalBackups() = withRootfs { rootfs ->
        val config = writeConfig(
            rootfs,
            "[options]\nDownloadUser = alpm\n# DisableSandboxFilesystem\n[core]\nServer = https://example.invalid/\$repo/os/\$arch\n"
        )
        val first = PacmanConfigCompatibility.migrate(rootfs)
        val migratedText = config.readText()
        val backupFiles = config.parentFile!!.listFiles()!!.filter { it.name.startsWith("pacman.conf.tritonterm-backup-") }

        val second = PacmanConfigCompatibility.migrate(rootfs)

        assertTrue(first.changed)
        assertFalse(second.changed)
        assertEquals(migratedText, config.readText())
        assertEquals(1, backupFiles.size)
        assertEquals(backupFiles, config.parentFile!!.listFiles()!!.filter { it.name.startsWith("pacman.conf.tritonterm-backup-") })
    }

    @Test
    fun existingDisabledSandboxValueIsEnabledWithoutChangingOtherSections() = withRootfs { rootfs ->
        val original = "[options]\nDisableSandboxFilesystem = no # device override\n[extra]\nSigLevel = Optional\n"
        val config = writeConfig(rootfs, original)

        val result = PacmanConfigCompatibility.migrate(rootfs)

        assertTrue(result.changed)
        assertEquals(original, result.backup!!.readText())
        assertTrue(config.readText().contains("DisableSandboxFilesystem\n# TritonTerm preserved prior inline comment: device override"))
        assertTrue(config.readText().contains("[extra]\nSigLevel = Optional"))
    }

    @Test
    fun absentDownloadUserAndCommentedSandboxAreHandledWithoutOtherEdits() = withRootfs { rootfs ->
        val config = writeConfig(rootfs, "[options]\n# user comment\n#CheckSpace\n[core]\nServer = mirror\n")

        val result = PacmanConfigCompatibility.migrate(rootfs)

        assertTrue(result.changed)
        assertTrue(config.readText().contains("#CheckSpace"))
        assertTrue(config.readText().contains("[options]\nDisableSandboxFilesystem\n# user comment"))
        assertTrue(config.readText().contains("[core]\nServer = mirror"))
    }

    @Test
    fun existingUserChangesReceiveTheirOwnNonOverwritingBackup() = withRootfs { rootfs ->
        val config = writeConfig(rootfs, "[options]\n#DisableSandboxFilesystem\n")
        val first = PacmanConfigCompatibility.migrate(rootfs)
        val userEdited = config.readText() + "# later user note\nDownloadUser = alpm\n"
        config.writeText(userEdited)

        val second = PacmanConfigCompatibility.migrate(rootfs)

        assertTrue(first.backup!!.isFile)
        assertTrue(second.changed)
        assertNotNull(second.backup)
        assertEquals(userEdited, second.backup!!.readText())
        assertTrue(config.readText().contains("# later user note"))
        assertTrue(config.readText().contains("# TritonTerm Android compatibility: DownloadUser = alpm"))
    }

    private fun writeConfig(rootfs: File, text: String): File {
        val etc = File(rootfs, "etc")
        assertTrue(etc.mkdirs() || etc.isDirectory)
        return File(etc, "pacman.conf").apply { writeText(text) }
    }

    private fun withRootfs(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("tritonterm-pacman-config").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
