package com.tritonterm.android.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Upgrade only the unmodified policy shipped in the Arch image. */
internal object SudoPolicyMigration {
    private const val OLD = "triton ALL=(root) NOPASSWD: /usr/bin/pacman\n"
    private const val NEW = "triton ALL=(root) NOPASSWD: ALL\n"

    @Synchronized
    fun migrate(root: File): Boolean {
        val policy = File(root, "etc/sudoers.d/10-tritonterm-pacman")
        if (!Files.isRegularFile(policy.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        if (policy.readText() != OLD) return false
        val path = policy.toPath()
        val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        val temp = Files.createTempFile(path.parent, ".tritonterm-sudo-", ".tmp")
        try {
            Files.write(temp, NEW.toByteArray(Charsets.UTF_8))
            Files.setPosixFilePermissions(temp, permissions)
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
        return true
    }
}
