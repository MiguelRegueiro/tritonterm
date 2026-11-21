package com.tritonterm.android.terminal

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.os.Debug
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import com.tritonterm.android.BuildConfig
import com.tritonterm.android.config.TerminalConfig
import android.graphics.Color
import kotlin.math.roundToInt
import java.io.File
import java.util.TimeZone

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
        prepareForStart()
        return startPrepared(onSetupStage, onSetupFailure)
    }

    /** File-system setup can be done on an I/O dispatcher before creating the PTY on the main thread. */
    fun prepareForStart() = backend.prepareForStart(context)

    fun startPrepared(
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

    /** Start a replacement PTY only after the previous top-level session process has exited. */
    fun restartFinishedSession(): TerminalSession {
        prepareForStart()
        return restartFinishedSessionPrepared()
    }

    fun restartFinishedSessionPrepared(): TerminalSession {
        check(TerminalSessionRecovery.canRestart(activeSession?.isRunning == true)) {
            "The terminal session is still running."
        }
        activeSession = null
        return startPrepared()
    }

    fun close() {
        client.terminalView = null
        resetSession()
    }

    /** A screen may be recreated while the PTY remains owned by the application process. */
    fun detachView() {
        client.terminalView = null
        client.setupObserver = null
        client.setupFailureObserver = null
        client.finishObserver = null
    }

    fun resetSession() {
        client.setupObserver = null
        activeSession?.finishIfRunning()
        activeSession = null
    }
}

internal object TerminalSessionRecovery {
    fun canRestart(isRunning: Boolean): Boolean = !isRunning

    fun signalNumber(exitStatus: Int): Int? = exitStatus.takeIf { it < 0 }?.unaryMinus()

    fun processPidForExit(observedWhileRunning: Int?, pidAtFinish: Int): Int? =
        observedWhileRunning?.takeIf { it > 0 } ?: pidAtFinish.takeIf { it > 0 }
}

/** Process-scoped PTY owner; Compose screens attach and detach views without ending the shell. */
internal object TerminalSessionHost {
    @Volatile private var controller: TerminalSessionController? = null

    fun controller(context: Context): TerminalSessionController =
        controller ?: synchronized(this) {
            controller ?: TerminalSessionController(context.applicationContext).also {
                inspectPreviousProcess(context.applicationContext)
                controller = it
            }
        }

    fun markSessionActive(context: Context) {
        runCatching { File(context.filesDir, "usr/var/lib/pr/tritonterm-active-shell").writeText("${System.currentTimeMillis()}\n") }
    }

    fun markSessionInactive(context: Context) {
        runCatching { File(context.filesDir, "usr/var/lib/pr/tritonterm-active-shell").delete() }
    }

    private fun inspectPreviousProcess(context: Context) {
        recordAppProcessExitHistory(context)
        val marker = File(context.filesDir, "usr/var/lib/pr/tritonterm-active-shell")
        if (!marker.isFile) return
        val lock = File(ArchLinuxArmBootstrap.rootfs(context), "var/lib/pacman/db.lck")
        TerminalDiagnostics.record(
            context,
            "process_recovery",
            "shell_process_was_interrupted",
            mapOf("marker_time_ms" to marker.readText().trim().toLongOrNull(), "pacman_lock_present" to lock.exists())
        )
        marker.delete()
    }

    /** Android reports app-process exits here; it cannot attribute a killed PRoot child. */
    private fun recordAppProcessExitHistory(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 5).forEach { info ->
                TerminalDiagnostics.record(
                    context,
                    "android_app_process_exit_history",
                    "historical_exit",
                    mapOf(
                        "scope" to "application_process_only",
                        "process_name" to info.processName,
                        "pid" to info.pid,
                        "reason_code" to info.reason,
                        "reason" to processExitReasonName(info.reason),
                        "status" to info.status,
                        "description" to info.description,
                        "timestamp_ms" to info.timestamp,
                        "pss_kb" to info.pss,
                        "rss_kb" to info.rss,
                        "importance" to info.importance
                    )
                )
            }
        }.onFailure { failure ->
            TerminalDiagnostics.record(
                context,
                "android_app_process_exit_history",
                "unavailable",
                mapOf("scope" to "application_process_only", "error" to failure.toString())
            )
        }
    }

    private fun processExitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason_$reason"
    }
}

internal object PacmanRecoveryState {
    enum class LockState { CLEAR, ACTIVE_TRANSACTION, POSSIBLY_STALE }

    const val STALE_LOCK_WARNING_DELAY_MS = 5_000L

    fun state(context: Context): LockState {
        val lock = File(ArchLinuxArmBootstrap.rootfs(context), "var/lib/pacman/db.lck")
        return classify(lock.exists(), lock.exists() && hasPacmanProcess())
    }

    fun classify(lockPresent: Boolean, pacmanProcessPresent: Boolean): LockState = when {
        !lockPresent -> LockState.CLEAR
        pacmanProcessPresent -> LockState.ACTIVE_TRANSACTION
        else -> LockState.POSSIBLY_STALE
    }

    fun shouldWarn(state: LockState, noProcessDurationMs: Long): Boolean =
        state == LockState.POSSIBLY_STALE && noProcessDurationMs >= STALE_LOCK_WARNING_DELAY_MS

    fun hasDatabaseLock(context: Context): Boolean = state(context) != LockState.CLEAR

    private fun hasPacmanProcess(): Boolean {
        return File("/proc").listFiles().orEmpty().any { process ->
            if (!process.name.all(Char::isDigit)) return@any false
            val command = runCatching { File(process, "cmdline").readBytes().toString(Charsets.UTF_8) }.getOrNull()
                ?: return@any false
            isPacmanArgv0(command)
        }
    }

    internal fun isPacmanArgv0(cmdline: String): Boolean {
        val argv0 = cmdline.substringBefore('\u0000').trim()
        return argv0.substringAfterLast('/') == "pacman"
    }
}

internal enum class TerminalSetupStage {
    IDLE, DOWNLOADING, VERIFYING, CLEANING, INSTALLING, PREPARING, INITIALIZING, DEVELOPER_TOOLS, READY, FAILED
}

internal data class TerminalSetupProgress(
    val stage: TerminalSetupStage,
    val downloaded: Long = 0L,
    val total: Long? = null,
    val downloadBytesPerSecond: Long = 0L,
    val extractedEntries: Long = 0L,
    val extractedBytes: Long = 0L,
    val extractionTotal: Long = 0L,
    val stepCompleted: Int = 0,
    val stepTotal: Int = 0,
    val activitySequence: Long = 0L,
    val lastActivity: String? = null,
    val commandStatus: String? = null
)

internal fun TerminalSetupProgress.withCommandOutput(line: String): TerminalSetupProgress = when {
    line.contains("command_still_running") || line.contains("Running package transaction:") ->
        copy(commandStatus = line.take(240))
    else -> copy(activitySequence = activitySequence + 1, lastActivity = line.take(240))
}

internal object TerminalSetupProgressFile {
    fun file(context: Context) = File(context.filesDir, "usr/var/lib/pr/tritonterm-install-progress")

    fun shouldPollInstaller(state: ArchInstallState): Boolean =
        state.running && state.stage == TerminalSetupStage.INSTALLING

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

internal object ArchGuestUser {
    const val NAME = "triton"
    const val UID = 1000
    const val GID = 1000
    const val HOME = "/home/triton"

    /** Shell availability is independent of optional developer-tool checks. */
    fun hasUsableShell(root: File): Boolean {
        val account = runCatching {
            File(root, "etc/passwd").useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    val fields = line.split(':')
                    fields.takeIf { it.size >= 7 && it[0] == NAME }
                }
            }
        }.getOrNull() ?: return false
        return account[2] == UID.toString() && account[3] == GID.toString() &&
            account[5] == HOME && account[6] == "/usr/bin/fish" &&
            File(root, "usr/bin/fish").canExecute() && File(root, HOME.removePrefix("/")).isDirectory
    }
}

internal interface TerminalBackend {
    val label: String
    fun isReady(context: Context): Boolean
    suspend fun prepare(context: Context, onProgress: (TerminalSetupProgress) -> Unit)
    fun prepareForStart(context: Context) = Unit
    fun start(context: Context, client: TerminalSessionClient): TerminalSession
}

internal object TerminalBackendFactory {
    fun create(): TerminalBackend =
        if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) ArchLinuxArmBackend() else AndroidShellBackend()
}

/** Values refreshed for each guest process so Android timezone changes take effect on restart. */
internal object ArchGuestEnvironment {
    const val LANG = "C.UTF-8"
    fun timezoneId(): String {
        // Force a fresh platform lookup: the app can stay alive across Android's
        // ACTION_TIMEZONE_CHANGED broadcast while a later session is launched.
        TimeZone.setDefault(null)
        return TimeZone.getDefault().id
    }
    fun variables(timezoneId: String = timezoneId()): Map<String, String> =
        mapOf("LANG" to LANG, "TZ" to timezoneId)
}

/** Give elio the same focus marker Kitty supplies, without advertising Kitty graphics support. */
private val ELIO_KITTY_KEYMAP_INIT = """
    if not functions -q elio; and not test -e ${'$'}HOME/.config/fish/functions/elio.fish
        function elio --description 'TritonTerm Kitty-compatible elio key maps'
            printf '\e]1337;SetUserVar=elio=MQ==\a'
            command /usr/bin/elio ${'$'}argv
            set -l elio_status ${'$'}status
            printf '\e]1337;SetUserVar=elio\a'
            return ${'$'}elio_status
        end
    end
    if status is-interactive; and test -r /tmp/tritonterm-async-prompt.fish
        source /tmp/tritonterm-async-prompt.fish
    end
""".trimIndent()

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
        return TerminalSession("/system/bin/sh", home, emptyArray(), environment, 2000, client, false)
    }
}

/** Launches the downloaded Arch Linux ARM rootfs through the bundled Android PRoot runtime. */
internal class ArchLinuxArmBackend : TerminalBackend {
    private fun performanceStatsEnabled() = BuildConfig.DEBUG || BuildConfig.VERSION_NAME.contains("-exp.")

    override val label = "Arch ARM"
    override fun isReady(context: Context) = ArchLinuxArmBootstrap.isInstalled(context)
    override suspend fun prepare(
        context: Context,
        onProgress: (TerminalSetupProgress) -> Unit
    ) = ArchLinuxArmBootstrap.prepare(context, onProgress)

    override fun prepareForStart(context: Context) {
        // Keep the existing tested pacman/PRoot settings in place for fresh and
        // previously prepared rootfs images; this does not run a package transaction.
        PacmanConfigCompatibility.migrate(context)
        val prefix = File(context.filesDir, "usr")
        val bin = File(prefix, "bin")
        val cache = ProotTempDirectory.forApp(context)
        listOf(
            bin,
            File(prefix, "tmp"),
            File(prefix, "etc/pr"),
            File(prefix, "var/lib/pr/installed-rootfs"),
            cache
        ).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "Could not create TritonTerm environment directory: $directory"
            }
        }
        context.assets.open("profile-chroot-ng.fish").use { source ->
            File(cache, "tritonterm-profile.fish").outputStream().use { destination ->
                source.copyTo(destination)
            }
        }
        context.assets.open("tritonterm-async-prompt.fish").use { source ->
            File(cache, "tritonterm-async-prompt.fish").outputStream().use { destination ->
                source.copyTo(destination)
            }
        }
        context.assets.open("tritonterm-starship-theme.toml").use { source ->
            File(cache, "tritonterm-starship-theme.toml").outputStream().use { destination ->
                source.copyTo(destination)
            }
        }
        if (performanceStatsEnabled()) {
            context.assets.open("measure-proot.py").use { source ->
                File(cache, "tritonterm-measure-proot.py").outputStream().use { destination ->
                    source.copyTo(destination)
                }
            }
        }
        val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
        linkNativeExecutable(nativeLibs, "libpr-cli.so", File(bin, "pr-cli"))
        linkNativeExecutable(nativeLibs, "libproot.so", File(bin, "proot"))
        File(context.filesDir, "home").mkdirs()
        val rustDiagnostic = File(context.filesDir, "home/.tritonterm-diagnose-arch-rust.sh")
        context.assets.open("diagnose-arch-rust.sh").use { source ->
            rustDiagnostic.outputStream().use { destination -> source.copyTo(destination) }
        }
    }

    override fun start(context: Context, client: TerminalSessionClient): TerminalSession {
        val prefix = File(context.filesDir, "usr")
        val bin = File(prefix, "bin")
        val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
        val cache = ProotTempDirectory.forApp(context)
        val cli = File(nativeLibs, "libpr-cli.so").absolutePath
        val guestRoot = ArchLinuxArmBootstrap.rootfs(context)
        val loginUser = ArchGuestUser.NAME
        val runtime = TerminalConfig.loadOrCreate(TerminalConfig.file(context.filesDir)).archRuntime
        TerminalDiagnostics.record(context, "arch_runtime", "selected", mapOf("engine" to runtime))
        val environment = arrayOf(
            "APP_PREFIX=${prefix.absolutePath}",
            "APP_HOME=${File(context.filesDir, "home").absolutePath}",
            "APP_PACKAGE=${context.packageName}",
            "TRITONTERM_ARCH_ENGINE=$runtime",
            "CNG_PROFILE_SUPPORT=1",
            "CNG_IMAGE_CACHE=1",
            "HOME=${File(context.filesDir, "home").absolutePath}",
            "TMPDIR=${cache.absolutePath}",
            "PROOT_TMP_DIR=${cache.absolutePath}",
            "PROOT_NO_SECCOMP=1",
            "PROOT_FAST_IO=1",
            // The pinned Arch PRoot build uses this flag to read archive UID/GID
            // and mode records from the sibling metadata tree. Without it,
            // guest commands such as id and setuid sudo see Android inode data.
            "TRITONTERM_PROOT_USERLAND_METADATA=1",
            "PROOT_CRASH_DIAGNOSTICS=1",
            "PROOT_CRASH_DIAGNOSTICS_FILE=${TerminalDiagnostics.crashFile(context).absolutePath}",
            "TRITONTERM_DIAGNOSTICS_FILE=${TerminalDiagnostics.file(context).absolutePath}",
            "PATH=${bin.absolutePath}:/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=${ArchGuestEnvironment.LANG}",
            "TZ=${ArchGuestEnvironment.timezoneId()}"
        ) + ArchGuestNetworkDns.environment(context) +
            if (performanceStatsEnabled()) arrayOf(
                "PROOT_PERF_STATS_DIR=${cache.absolutePath}",
                "PROOT_PERF_TIMING=1"
            ) else emptyArray()
        val startedAt = System.nanoTime()
        TerminalDiagnostics.record(
            context,
            "proot_crash_recorder",
            "configured",
            mapOf(
                "enabled" to true,
                "output" to TerminalDiagnostics.crashFile(context).absolutePath,
                "output_parent_exists" to TerminalDiagnostics.crashFile(context).parentFile?.isDirectory,
                "output_parent_writable" to TerminalDiagnostics.crashFile(context).parentFile?.canWrite(),
                "forwarded_by_pr_cli" to true
            )
        )
        TerminalDiagnostics.record(context, "shell_startup", "launch_requested", mapOf("executable" to cli))
        val crashLogOffset = TerminalDiagnostics.crashFile(context).takeIf(File::isFile)?.length() ?: 0L
        return try {
            TerminalSession(
                cli,
                context.filesDir.absolutePath,
                // TerminalSession's JNI execvp(cmd, argv) passes this array verbatim;
                // unlike ProcessBuilder, it does not prepend cmd as argv[0].
                arrayOf(
                    cli, "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", loginUser,
                    "/usr/bin/fish", "-l", "--init-command", ELIO_KITTY_KEYMAP_INIT
                ),
                environment,
                2000,
                client,
                false
            ).also {
                TerminalDiagnostics.confirmCrashRecorder(context, crashLogOffset)
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
        set(value) {
            field = value
            if (value != null) applyCursorAppearance(value)
        }
    private var terminalConfig = TerminalConfig()
    private var cursorBlinkRequested = false
    var finishObserver: (() -> Unit)? = null
    var setupObserver: ((TerminalSetupStage) -> Unit)? = null
    var setupFailureObserver: ((String) -> Unit)? = null
    private var setupReady = false
    private var previousTranscript = ""
    private val emittedMarkers = mutableSetOf<String>()
    private var shellOutputRecorded = false
    private var observedProcessPid: Int? = null

    fun setTerminalConfig(config: TerminalConfig) {
        val blinkIntervalChanged = terminalConfig.cursorBlinkInterval != config.cursorBlinkInterval
        val shapeChanged = terminalConfig.cursorShape != config.cursorShape
        terminalConfig = config
        if (blinkIntervalChanged) cursorBlinkRequested = config.cursorBlinkInterval > 0
        if (shapeChanged) terminalView?.mEmulator?.setCursorStyle()
        terminalView?.let(::applyCursorAppearance)
    }

    private fun applyCursorAppearance(view: TerminalView) {
        val blinkRate = (terminalConfig.cursorBlinkInterval * 1000).roundToInt()
        if (blinkRate == 0) cursorBlinkRequested = false
        view.setTerminalCursorBlinkerRate(blinkRate)
        view.setCursorAppearance(
            Color.parseColor(terminalConfig.cursorColor),
            Color.parseColor(terminalConfig.cursorTextColor),
            terminalConfig.cursorBeamThickness.toFloat(),
            terminalConfig.cursorUnderlineThickness.toFloat()
        )
        view.setTerminalCursorBlinkerState(cursorBlinkRequested && blinkRate > 0, true)
    }

    private fun updateCursorBlinker() {
        val interval = (terminalConfig.cursorBlinkInterval * 1000).roundToInt()
        terminalView?.setTerminalCursorBlinkerRate(interval)
        terminalView?.setTerminalCursorBlinkerState(cursorBlinkRequested && interval > 0, true)
    }

    fun beginSetup(captureOutput: Boolean) {
        setupReady = !captureOutput
        previousTranscript = ""
        emittedMarkers.clear()
        shellOutputRecorded = false
        observedProcessPid = null
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        val livePid = changedSession.pid.takeIf { it > 0 }
        if (livePid != null && observedProcessPid == null) {
            observedProcessPid = livePid
            TerminalDiagnostics.record(
                context,
                "terminal_process",
                "identity_observed",
                mapOf("pid" to livePid, "scope" to "PTY_managed_top_level_process_pr_cli_execs_into_proot")
            )
        }
        // Startup markers and captured setup output are only relevant until the shell is
        // ready. Avoid building the full scrollback transcript on every ordinary terminal
        // redraw; it can contain thousands of rows.
        if (!setupReady || !shellOutputRecorded) {
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
        }
        terminalView?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) {
        TerminalSessionHost.markSessionInactive(context)
        TerminalForegroundService.stop(context)
        val status = finishedSession.exitStatus
        val pidAtFinish = finishedSession.pid
        val reportedPid = TerminalSessionRecovery.processPidForExit(observedProcessPid, pidAtFinish)
        val memory = runCatching {
            val info = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
            mapOf(
                "host_available_memory_bytes" to info.availMem,
                "host_low_memory_threshold_bytes" to info.threshold,
                "host_low_memory_flag" to info.lowMemory,
                "app_process_pss_kb" to Debug.getPss()
            )
        }.getOrDefault(emptyMap())
        TerminalDiagnostics.record(
            context,
            "terminal_process",
            "exited",
            mapOf(
                "exit_code" to status,
                "termination_kind" to if (status < 0) "signal_observed_by_terminal_session" else "exit_status",
                "signal_number" to TerminalSessionRecovery.signalNumber(status),
                "observed_process_scope" to "PTY_managed_top_level_process_pr_cli_execs_into_proot",
                "terminal_session_pid" to reportedPid,
                "terminal_session_pid_source" to when {
                    observedProcessPid != null -> "captured_while_session_was_alive"
                    pidAtFinish > 0 -> "available_at_finish_callback"
                    else -> "unavailable"
                },
                "terminal_session_pid_at_finish" to pidAtFinish,
                "cause" to "unknown_from_wait_status_alone"
            ) + memory
        )
        finishObserver?.invoke()
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
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = copyText(text)
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        pasteFromClipboard(session)
    }

    fun copySelection(): Boolean {
        val view = terminalView ?: return false
        if (!view.isSelectingText) return false
        val selected = runCatching {
            val controllerField = view.javaClass.getDeclaredField("mTextSelectionCursorController").apply {
                isAccessible = true
            }
            val selectionController = controllerField.get(view) ?: return false
            fun coordinate(name: String) = selectionController.javaClass.getDeclaredField(name).let { field ->
                field.isAccessible = true
                field.getInt(selectionController)
            }
            view.mEmulator?.getSelectedText(
                coordinate("mSelX1"), coordinate("mSelY1"), coordinate("mSelX2"), coordinate("mSelY2")
            )?.trim()
        }.getOrNull()?.takeIf(String::isNotEmpty) ?: return false
        copyText(selected)
        return true
    }

    fun pasteFromClipboard(session: TerminalSession?): Boolean {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = TerminalClipboardSupport.clipboardText(item?.coerceToText(context)) ?: return false
        val emulator = session?.emulator ?: return false
        // TerminalEmulator.paste preserves UTF-8 text and wraps it in bracketed-paste
        // delimiters when the guest has enabled DECSET 2004.
        terminalView?.scrollToBottom()
        emulator.paste(text)
        return true
    }

    private fun copyText(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("TritonTerm terminal", text))
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTerminalCursorStateChange(state: Boolean) = updateCursorBlinker()
    override fun onTerminalCursorBlinkChange(blinking: Boolean) {
        cursorBlinkRequested = blinking
        updateCursorBlinker()
    }
    override fun getTerminalCursorBlinkInterval(): Int = (terminalConfig.cursorBlinkInterval * 1000).roundToInt()
    override fun getTerminalCursorStyle(): Int = when (terminalConfig.cursorShape) {
        "beam" -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
        "underline" -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
        else -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
    }
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
    private var lastZoomAtMillis = 0L
    private val zoomRepeatIntervalMillis = 100L

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
                KeyEvent.KEYCODE_MINUS -> {
                    applyZoomRepeat(e, false)
                    return true
                }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> {
                    applyZoomRepeat(e, true)
                    return true
                }
            }
        }
        return false
    }

    private fun applyZoomRepeat(event: KeyEvent, increase: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (event.repeatCount == 0 || now - lastZoomAtMillis >= zoomRepeatIntervalMillis) {
            lastZoomAtMillis = now
            zoom(increase)
        }
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
