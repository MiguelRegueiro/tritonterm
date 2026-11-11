package com.tritonterm.android.terminal

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.security.MessageDigest

internal data class PacmanConfigMigrationResult(
    val changed: Boolean,
    val backup: File? = null
)

/** Applies only the Android/PRoot pacman settings needed by this rootfs. */
internal object PacmanConfigCompatibility {
    private const val FILESYSTEM_SANDBOX = "DisableSandboxFilesystem"
    private val activeDownloadUser = Regex("^(\\s*)DownloadUser(?=\\s|=|#|$)(.*)$")
    private val activeFilesystemSandbox = Regex("^(\\s*)DisableSandboxFilesystem(?=\\s|=|#|$)(.*)$")
    private val commentedFilesystemSandbox = Regex("^(\\s*)#\\s*DisableSandboxFilesystem(?=\\s|=|#|$)(.*)$")

    @Synchronized
    fun migrate(rootfs: File): PacmanConfigMigrationResult {
        val config = File(rootfs, "etc/pacman.conf")
        val configPath = config.toPath()
        check(Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) {
            "Arch pacman configuration is missing or is not a regular file: $config"
        }

        val original = Files.readAllBytes(configPath)
        val text = String(original, Charsets.UTF_8)
        val lines = text.split('\n')
        val hasOptionsSection = lines.any(::isOptionsSection)
        val hasFilesystemSetting = hasSettingInOptions(
            lines,
            activeFilesystemSandbox
        ) || hasSettingInOptions(lines, commentedFilesystemSandbox)

        val migratedLines = ArrayList<String>(lines.size + 2)
        var inOptions = false
        var insertedFilesystemSetting = false
        var changed = false

        for (line in lines) {
            val lineBody = line.removeSuffix("\r")
            val lineEnding = if (line.endsWith('\r')) "\r" else ""
            if (lineBody.trimStart().startsWith("[") && lineBody.trimEnd().endsWith("]")) {
                inOptions = isOptionsSection(lineBody)
            }

            if (!inOptions) {
                migratedLines += line
                continue
            }

            val downloadUser = activeDownloadUser.matchEntire(lineBody)
            if (downloadUser != null) {
                val indentation = downloadUser.groupValues[1]
                val originalSetting = lineBody.removePrefix(indentation)
                migratedLines += "$indentation# TritonTerm Android compatibility: $originalSetting$lineEnding"
                changed = true
                continue
            }

            val activeSandbox = activeFilesystemSandbox.matchEntire(lineBody)
            if (activeSandbox != null) {
                val afterKey = activeSandbox.groupValues[2]
                if (!isEnabled(afterKey)) {
                    migratedLines.addAll(enabledSetting(activeSandbox.groupValues[1], afterKey, lineEnding))
                    changed = true
                } else {
                    migratedLines += line
                }
                insertedFilesystemSetting = true
                continue
            }

            val commentedSandbox = commentedFilesystemSandbox.matchEntire(lineBody)
            if (commentedSandbox != null && !insertedFilesystemSetting) {
                migratedLines.addAll(enabledSetting(commentedSandbox.groupValues[1], commentedSandbox.groupValues[2], lineEnding))
                insertedFilesystemSetting = true
                changed = true
                continue
            }

            migratedLines += line
            if (!hasFilesystemSetting && isOptionsSection(lineBody) && !insertedFilesystemSetting) {
                migratedLines += "$FILESYSTEM_SANDBOX$lineEnding"
                insertedFilesystemSetting = true
                changed = true
            }
        }

        if (!hasOptionsSection) {
            if (migratedLines.lastOrNull()?.isNotEmpty() == true) migratedLines += ""
            migratedLines += "[options]"
            migratedLines += FILESYSTEM_SANDBOX
            changed = true
        } else if (!hasFilesystemSetting && !insertedFilesystemSetting) {
            // An options header may be the last line without a terminating newline.
            migratedLines += FILESYSTEM_SANDBOX
            changed = true
        }

        if (!changed) return PacmanConfigMigrationResult(changed = false)

        val backup = backupBeforeChange(config, original)
        val migrated = migratedLines.joinToString("\n").toByteArray(Charsets.UTF_8)
        replaceAtomically(config, migrated)
        return PacmanConfigMigrationResult(changed = true, backup = backup)
    }

    fun migrate(context: Context): PacmanConfigMigrationResult {
        val startedAt = System.nanoTime()
        return try {
            val result = migrate(ArchLinuxArmBootstrap.rootfs(context))
            TerminalDiagnostics.record(
                context,
                "pacman_config_compatibility",
                if (result.changed) "migrated" else "already_compatible",
                buildMap {
                    put("elapsed_ms", (System.nanoTime() - startedAt) / 1_000_000)
                    result.backup?.let { put("backup", it.absolutePath) }
                }
            )
            result
        } catch (failure: Exception) {
            TerminalDiagnostics.record(
                context,
                "pacman_config_compatibility",
                "failed",
                mapOf("elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000, "error" to failure.message)
            )
            throw failure
        }
    }

    private fun isOptionsSection(line: String): Boolean =
        line.trim().equals("[options]", ignoreCase = true)

    private fun hasSettingInOptions(lines: List<String>, setting: Regex): Boolean {
        var inOptions = false
        return lines.any { line ->
            val body = line.removeSuffix("\r")
            if (body.trimStart().startsWith("[") && body.trimEnd().endsWith("]")) {
                inOptions = isOptionsSection(body)
            }
            inOptions && setting.matches(body)
        }
    }

    private fun isEnabled(afterKey: String): Boolean {
        // Pacman's boolean directives are presence-only; normalize assigned values and
        // inline comments to the bare directive (comments belong on their own lines).
        return afterKey.isBlank()
    }

    private fun enabledSetting(indentation: String, afterKey: String, lineEnding: String): List<String> {
        val comment = afterKey.indexOf('#').takeIf { it >= 0 }?.let { afterKey.substring(it).trimStart() }
        return buildList {
            add("$indentation$FILESYSTEM_SANDBOX$lineEnding")
            if (!comment.isNullOrEmpty()) add("$indentation# TritonTerm preserved prior inline comment: ${comment.removePrefix("#").trim()}$lineEnding")
        }
    }

    private fun backupBeforeChange(config: File, original: ByteArray): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(original)
            .joinToString("") { "%02x".format(it) }
        val backup = File(config.parentFile, "pacman.conf.tritonterm-backup-$digest")
        if (!backup.exists()) {
            Files.copy(config.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }
        check(Files.readAllBytes(backup.toPath()).contentEquals(original)) {
            "Pacman configuration backup does not match the original file: $backup"
        }
        return backup
    }

    private fun replaceAtomically(config: File, contents: ByteArray) {
        val parent = requireNotNull(config.parentFile) { "Pacman configuration has no parent directory: $config" }
        val originalPermissions = runCatching {
            Files.getPosixFilePermissions(config.toPath(), LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
        val temporary = Files.createTempFile(parent.toPath(), "pacman.conf.", ".tmp")
        try {
            Files.write(temporary, contents)
            originalPermissions?.let { permissions -> Files.setPosixFilePermissions(temporary, permissions) }
            try {
                Files.move(temporary, config.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, config.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
