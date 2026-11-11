package com.tritonterm.android.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

internal class TerminalSessionController(
    private val context: Context,
    backend: TerminalBackend? = null
) {
    private val backend = backend ?: TerminalBackendFactory.create()
    val client = TritonSessionClient(context)
    val runtimeLabel: String get() = backend.label
    private var activeSession: TerminalSession? = null

    fun isReady(context: Context) = backend.isReady(context)

    suspend fun prepare(
        context: Context,
        onProgress: (TerminalSetupProgress) -> Unit
    ) = backend.prepare(context, onProgress)

    fun start(
        onSetupStage: ((TerminalSetupStage) -> Unit)? = null,
        onSetupFailure: ((String) -> Unit)? = null
    ): TerminalSession {
        client.beginSetup(captureOutput = onSetupStage != null || onSetupFailure != null)
        client.setupObserver = onSetupStage
        client.setupFailureObserver = onSetupFailure
        return (activeSession ?: backend.start(context, client)).also {
            activeSession = it
        }
    }

    fun close() {
        client.terminalView = null
        resetSession()
    }

    fun resetSession() {
        client.setupObserver = null
        activeSession?.finishIfRunning()
        activeSession = null
    }
}

internal enum class TerminalSetupStage { IDLE, DOWNLOADING, VERIFYING, CLEANING, INSTALLING, INITIALIZING, READY, FAILED }

internal data class TerminalSetupProgress(
    val stage: TerminalSetupStage,
    val downloaded: Long = 0L,
    val total: Long? = null,
    val extractedEntries: Long = 0L,
    val extractedBytes: Long = 0L,
    val extractionTotal: Long = 0L
)

internal object TerminalSetupProgressFile {
    fun file(context: Context) = File(context.filesDir, "usr/var/lib/pr/tritonterm-install-progress")

    /** Clear only the installer-owned progress counter before a new explicit install attempt. */
    fun begin(context: Context) { file(context).delete() }

    fun read(context: Context): TerminalSetupProgress? = runCatching {
        val record = file(context).readText().trim().split('|')
        if (record.size != 2) return null
        val stage = when (record[0]) {
            "VERIFYING" -> TerminalSetupStage.VERIFYING
            "CLEANING" -> TerminalSetupStage.CLEANING
            "INSTALLING" -> TerminalSetupStage.INSTALLING
            else -> return null
        }
        val fields = record[1].split('/')
        if (fields.size != 3) return null
        val bytes = fields[0].toLongOrNull() ?: return null
        val total = fields[1].toLongOrNull() ?: return null
        val entries = fields[2].toLongOrNull() ?: return null
        TerminalSetupProgress(
            stage = stage,
            extractedEntries = entries,
            extractedBytes = bytes,
            extractionTotal = total
        )
    }.getOrNull()
}

internal interface TerminalBackend {
    val label: String
    fun isReady(context: Context): Boolean
    suspend fun prepare(context: Context, onProgress: (TerminalSetupProgress) -> Unit)
    fun start(context: Context, client: TerminalSessionClient): TerminalSession
}

internal object TerminalBackendFactory {
    fun create(): TerminalBackend =
        if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) ArchLinuxArmBackend() else AndroidShellBackend()
}

internal class AndroidShellBackend : TerminalBackend {
    override val label = "local"
    override fun isReady(context: Context) = true
    override suspend fun prepare(context: Context, onProgress: (TerminalSetupProgress) -> Unit) = Unit

    override fun start(context: Context, client: TerminalSessionClient): TerminalSession {
        val home = context.filesDir.absolutePath
        val environment = arrayOf(
            "HOME=$home",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "XDG_CONFIG_HOME=${File(context.filesDir, ".config").absolutePath}",
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8"
        )
        return TerminalSession("/system/bin/sh", home, emptyArray(), environment, 2000, client)
    }
}

/** Launches the downloaded Arch Linux ARM rootfs through the bundled Android PRoot runtime. */
internal class ArchLinuxArmBackend : TerminalBackend {
    override val label = "Arch ARM"
    override fun isReady(context: Context) = ArchLinuxArmBootstrap.isInstalled(context)
    override suspend fun prepare(
        context: Context,
        onProgress: (TerminalSetupProgress) -> Unit
    ) = ArchLinuxArmBootstrap.prepare(context, onProgress)

    override fun start(context: Context, client: TerminalSessionClient): TerminalSession {
        val prefix = File(context.filesDir, "usr")
        val bin = File(prefix, "bin")
        val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
        val cache = File(context.cacheDir, "proot")
        listOf(
            bin,
            File(prefix, "tmp"),
            File(prefix, "etc/pr"),
            File(prefix, "var/lib/pr/installed-rootfs"),
            cache
        )
            .forEach { directory ->
                check(directory.mkdirs() || directory.isDirectory) {
                    "Could not create TritonTerm environment directory: $directory"
                }
            }
        linkNativeExecutable(nativeLibs, "libpr-cli.so", File(bin, "pr-cli"))
        linkNativeExecutable(nativeLibs, "libproot.so", File(bin, "proot"))
        val cli = File(nativeLibs, "libpr-cli.so").absolutePath
        val environment = arrayOf(
            "APP_PREFIX=${prefix.absolutePath}",
            "APP_HOME=${File(context.filesDir, "home").absolutePath}",
            "APP_PACKAGE=${context.packageName}",
            "HOME=${File(context.filesDir, "home").absolutePath}",
            "TMPDIR=${cache.absolutePath}",
            "PROOT_TMP_DIR=${cache.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "TRITONTERM_DIAGNOSTICS_FILE=${TerminalDiagnostics.file(context).absolutePath}",
            "PATH=${bin.absolutePath}:/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8"
        )
        File(context.filesDir, "home").mkdirs()
        val startedAt = System.nanoTime()
        TerminalDiagnostics.record(context, "shell_startup", "launch_requested", mapOf("executable" to cli))
        return try {
            TerminalSession(
                cli,
                context.filesDir.absolutePath,
                // TerminalSession's JNI execvp(cmd, argv) passes this array verbatim;
                // unlike ProcessBuilder, it does not prepend cmd as argv[0].
                arrayOf(cli, "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/bash", "-l"),
                environment,
                2000,
                client
            ).also {
                TerminalDiagnostics.record(
                    context,
                    "shell_startup",
                    "started",
                    mapOf(
                        "elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000,
                        "pid" to it.pid,
                        "is_running" to it.isRunning
                    )
                )
            }
        } catch (failure: Exception) {
            TerminalDiagnostics.record(
                context,
                "session_process",
                "failed",
                mapOf("elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000, "error" to failure.message)
            )
            throw failure
        }
    }

    private fun linkNativeExecutable(nativeLibs: File, library: String, link: File) {
        val target = File(nativeLibs, library)
        check(target.isFile) { "Bundled PRoot component is missing: $library" }
        val mode = runCatching { Os.lstat(link.absolutePath).st_mode }.getOrNull()
        if (mode != null && android.system.OsConstants.S_ISLNK(mode)) {
            Os.remove(link.absolutePath)
        } else if (link.exists()) {
            error("Runtime path is occupied by an unexpected file; it was preserved: $link")
        }
        Os.symlink(target.absolutePath, link.absolutePath)
    }
}

internal class TritonSessionClient(private val context: Context) : TerminalSessionClient {
    var terminalView: TerminalView? = null
    var setupObserver: ((TerminalSetupStage) -> Unit)? = null
    var setupFailureObserver: ((String) -> Unit)? = null
    private var setupReady = false
    private var previousTranscript = ""
    private val emittedMarkers = mutableSetOf<String>()
    private var shellOutputRecorded = false

    fun beginSetup(captureOutput: Boolean) {
        setupReady = !captureOutput
        previousTranscript = ""
        emittedMarkers.clear()
        shellOutputRecorded = false
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        val screenText = runCatching {
            changedSession.emulator?.screen?.transcriptText?.takeLast(4096).orEmpty()
        }.getOrDefault("")
        if (setupReady && screenText.isNotBlank() && !shellOutputRecorded) {
            shellOutputRecorded = true
            TerminalDiagnostics.record(context, "shell_startup", "terminal_output_received")
        }
        if (!setupReady) captureSetupOutput(screenText)
        recordMarker(screenText, "__TRITONTERM_INSTALLER_LAUNCH__", "installer_launch", "started")
        recordMarkerExit(screenText, "__TRITONTERM_INSTALLER_EXIT:(\\d+)__", "installer_process")
        recordMarker(screenText, "__TRITONTERM_PACMAN_INIT_START__", "pacman_key_init", "started")
        recordMarkerExit(screenText, "__TRITONTERM_PACMAN_INIT_EXIT:(\\d+)__", "pacman_key_init")
        recordMarker(screenText, "__TRITONTERM_PACMAN_POPULATE_START__", "pacman_key_populate", "started")
        recordMarkerExit(screenText, "__TRITONTERM_PACMAN_POPULATE_EXIT:(\\d+)__", "pacman_key_populate")
        recordMarker(screenText, "__TRITONTERM_PACMAN_KEYRING_REUSED__", "pacman_keyring", "reused")
        when {
            screenText.contains("__TRITONTERM_STAGE_INSTALLING__") ->
                setupObserver?.invoke(TerminalSetupStage.INSTALLING)
            screenText.contains("__TRITONTERM_STAGE_INITIALIZING__") ->
                setupObserver?.invoke(TerminalSetupStage.INITIALIZING)
            screenText.contains("__TRITONTERM_SHELL_START__") -> {
                setupReady = true
                TerminalDiagnostics.record(context, "shell_startup", "exec_requested")
                setupObserver?.invoke(TerminalSetupStage.READY)
                setupObserver = null
            }
        }
        terminalView?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) {
        TerminalDiagnostics.record(
            context,
            "terminal_process",
            "exited",
            mapOf("exit_code" to finishedSession.exitStatus)
        )
        if (!setupReady && setupObserver != null) {
            val diagnostic = runCatching {
                finishedSession.emulator?.screen?.transcriptText.orEmpty()
                    .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                    .takeLast(12)
                    .joinToString("\n")
                    .takeLast(900)
            }.getOrDefault("")
            setupObserver?.invoke(TerminalSetupStage.FAILED)
            setupFailureObserver?.invoke(diagnostic)
            setupObserver = null
            setupFailureObserver = null
        }
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit

    private fun captureSetupOutput(transcript: String) {
        val overlap = if (previousTranscript.isEmpty()) {
            0
        } else {
            (minOf(previousTranscript.length, transcript.length) downTo 1)
                .firstOrNull { size ->
                    previousTranscript.regionMatches(previousTranscript.length - size, transcript, 0, size)
                } ?: 0
        }
        val delta = transcript.substring(overlap)
        previousTranscript = transcript
        if (delta.isNotBlank()) TerminalDiagnostics.appendProcessOutput(context, "terminal", delta)
    }

    private fun recordMarker(transcript: String, marker: String, phase: String, status: String) {
        if (transcript.contains(marker) && emittedMarkers.add(marker)) {
            TerminalDiagnostics.record(context, phase, status)
        }
    }

    private fun recordMarkerExit(transcript: String, pattern: String, phase: String) {
        val match = Regex(pattern).findAll(transcript).lastOrNull() ?: return
        if (!emittedMarkers.add(match.value)) return
        val exitCode = match.groupValues.getOrNull(1)?.toIntOrNull()
        TerminalDiagnostics.record(
            context,
            phase,
            if (exitCode == 0) "completed" else "failed",
            mapOf("exit_code" to exitCode)
        )
    }

}

internal class TerminalCallbacks(
    private val session: TerminalSession,
    private val zoom: (Boolean) -> Unit
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (e.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_MINUS -> { zoom(false); return true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> { zoom(true); return true }
            }
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        session.writeCodePoint(ctrlDown, codePoint)
        return true
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}
