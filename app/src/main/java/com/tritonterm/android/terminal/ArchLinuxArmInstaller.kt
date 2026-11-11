package com.tritonterm.android.terminal

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class ArchInstallState(
    val stage: TerminalSetupStage = TerminalSetupStage.IDLE,
    val progress: TerminalSetupProgress = TerminalSetupProgress(TerminalSetupStage.IDLE),
    val running: Boolean = false,
    val ready: Boolean = false,
    val preflightPassed: Boolean = false,
    val error: String? = null
)

/** Owns the one noninteractive install process independently of Compose and terminal sessions. */
internal object ArchLinuxArmInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ArchInstallState())
    val state: StateFlow<ArchInstallState> = mutableState.asStateFlow()

    fun runPreflight(context: Context): Boolean = runSingleFlight(context, "installer_preflight") { appContext ->
        prepareRuntime(appContext)
        val result = runProcess(appContext, "installer_preflight", "--help", timeoutMs = 20_000L, args = listOf("--help"))
        check(result.exitCode == 0 && !result.timedOut && result.output.contains("Usage:")) {
            "The bundled pr-cli preflight failed (exit ${result.exitCode}${if (result.timedOut) ", timed out" else ""}). See Logs for output."
        }
        mutableState.value = ArchInstallState(preflightPassed = true)
        TerminalDiagnostics.record(appContext, "installer_preflight", "validated", mapOf("exit_code" to result.exitCode))
    }

    fun start(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!mutableState.value.preflightPassed) {
            mutableState.value = mutableState.value.copy(error = "Run the harmless installer launcher test before installing Arch Linux ARM.")
            return false
        }
        if (ArchLinuxArmBootstrap.isInstalled(appContext)) {
            mutableState.value = mutableState.value.copy(stage = TerminalSetupStage.READY, running = false, ready = true, error = null)
            return true
        }
        return runSingleFlight(appContext, "setup") { app ->
            TerminalDiagnostics.record(app, "setup", "started", mapOf("launch_mode" to "explicit_process"))
            val progress: (TerminalSetupProgress) -> Unit = { value ->
                mutableState.value = mutableState.value.copy(stage = value.stage, progress = value)
            }
            ArchLinuxArmBootstrap.prepare(app, progress)
            progress(TerminalSetupProgress(TerminalSetupStage.INSTALLING))
            TerminalSetupProgressFile.begin(app)
            val install = runProcess(
                app,
                "installer_process",
                "install",
                args = listOf("install", ArchLinuxArmBootstrap.DISTRO_ALIAS)
            )
            check(install.exitCode == 0 && !install.timedOut) {
                "Arch Linux ARM installation failed (exit ${install.exitCode}${if (install.timedOut) ", timed out" else ""}). The archive and partial rootfs were preserved. See Logs for output."
            }
            check(ArchLinuxArmBootstrap.isInstalled(app)) {
                "pr-cli exited successfully, but required rootfs validation failed. Existing data was preserved. See Logs."
            }
            TerminalDiagnostics.record(app, "rootfs_validation", "completed", mapOf("rootfs" to ArchLinuxArmBootstrap.rootfs(app).absolutePath))
            initializePacmanKeys(app)
            mutableState.value = ArchInstallState(
                stage = TerminalSetupStage.READY,
                progress = TerminalSetupProgress(TerminalSetupStage.READY),
                preflightPassed = true,
                ready = true
            )
            TerminalDiagnostics.record(app, "setup", "completed")
        }
    }

    private fun runSingleFlight(context: Context, phase: String, work: suspend (Context) -> Unit): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        val appContext = context.applicationContext
        mutableState.value = mutableState.value.copy(running = true, error = null)
        scope.launch {
            try {
                work(appContext)
            } catch (failure: Exception) {
                val message = failure.message ?: failure.javaClass.simpleName
                mutableState.value = mutableState.value.copy(stage = TerminalSetupStage.FAILED, ready = false, error = message)
                TerminalDiagnostics.record(appContext, phase, "failed", mapOf("error" to message))
            } finally {
                inFlight.set(false)
                mutableState.value = mutableState.value.copy(running = false)
            }
        }
        return true
    }

    private fun prepareRuntime(context: Context) {
        val prefix = File(context.filesDir, "usr")
        val bin = File(prefix, "bin")
        val nativeLibs = File(context.applicationInfo.nativeLibraryDir)
        val cache = File(context.cacheDir, "proot")
        listOf(bin, File(prefix, "tmp"), File(prefix, "etc/pr"), File(prefix, "var/lib/pr/installed-rootfs"), cache,
            File(context.filesDir, "home")).forEach { directory ->
            check(directory.mkdirs() || directory.isDirectory) { "Could not create TritonTerm runtime directory: $directory" }
        }
        linkNative(nativeLibs, "libproot.so", File(bin, "proot"))
        linkNative(nativeLibs, "libproot-loader.so", File(bin, "proot-loader"))
        val cli = File(nativeLibs, "libpr-cli.so")
        check(cli.isFile && cli.canExecute()) { "Bundled pr-cli is missing or not executable: $cli" }
        TerminalDiagnostics.record(
            context,
            "runtime_setup",
            "completed",
            mapOf("cli" to cli.absolutePath, "cli_bytes" to cli.length(), "prefix" to prefix.absolutePath, "proot" to File(bin, "proot").absolutePath)
        )
    }

    private fun linkNative(nativeLibs: File, library: String, destination: File) {
        val target = File(nativeLibs, library)
        check(target.isFile && target.canExecute()) { "Bundled runtime component is missing or not executable: $target" }
        val mode = runCatching { Os.lstat(destination.absolutePath).st_mode }.getOrNull()
        if (mode != null && OsConstants.S_ISLNK(mode)) {
            Os.remove(destination.absolutePath)
        } else if (destination.exists()) {
            error("Runtime path is occupied by an unexpected file; it was preserved: $destination")
        }
        Os.symlink(target.absolutePath, destination.absolutePath)
    }

    private data class ProcessResult(val exitCode: Int, val timedOut: Boolean, val output: String)

    private fun runProcess(
        context: Context,
        phase: String,
        operation: String,
        timeoutMs: Long? = null,
        args: List<String>
    ): ProcessResult {
        val cli = File(context.applicationInfo.nativeLibraryDir, "libpr-cli.so")
        val prefix = File(context.filesDir, "usr")
        val home = File(context.filesDir, "home")
        val temp = File(context.cacheDir, "proot")
        val command = listOf(cli.absolutePath) + args
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(false)
        processBuilder.environment().apply {
            put("APP_PREFIX", prefix.absolutePath)
            put("APP_HOME", home.absolutePath)
            put("APP_PACKAGE", context.packageName)
            put("HOME", home.absolutePath)
            put("TMPDIR", temp.absolutePath)
            put("PROOT_TMP_DIR", temp.absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put("TRITONTERM_DIAGNOSTICS_FILE", TerminalDiagnostics.file(context).absolutePath)
            put("TRITONTERM_PRESERVE_FAILED_ROOTFS", "1")
            put("PATH", "${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin")
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
        }
        val started = System.nanoTime()
        TerminalDiagnostics.record(
            context,
            phase,
            "launch_requested",
            mapOf(
                "operation" to operation,
                "executable" to cli.absolutePath,
                "args" to args,
                "cwd" to context.filesDir.absolutePath,
                "environment" to mapOf(
                    "APP_PREFIX" to prefix.absolutePath,
                    "APP_HOME" to home.absolutePath,
                    "APP_PACKAGE" to context.packageName,
                    "HOME" to home.absolutePath,
                    "TMPDIR" to temp.absolutePath,
                    "PROOT_TMP_DIR" to temp.absolutePath,
                    "PROOT_NO_SECCOMP" to "1",
                    "TRITONTERM_DIAGNOSTICS_FILE" to TerminalDiagnostics.file(context).absolutePath,
                    "TRITONTERM_PRESERVE_FAILED_ROOTFS" to "1",
                    "PATH" to "${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin",
                    "TERM" to "xterm-256color",
                    "LANG" to "en_US.UTF-8"
                )
            )
        )
        val process = try {
            processBuilder.start()
        } catch (failure: Exception) {
            TerminalDiagnostics.record(context, phase, "launch_failed", mapOf("operation" to operation, "error" to failure.message))
            throw failure
        }
        TerminalDiagnostics.record(
            context,
            phase,
            "started",
            mapOf(
                "operation" to operation,
                "pid" to runCatching { process.javaClass.getMethod("pid").invoke(process) }.getOrNull(),
                "app_pid" to android.os.Process.myPid(),
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000
            )
        )
        val combinedOutput = StringBuffer()
        val stdout = drain(context, phase, "stdout", process.inputStream, combinedOutput)
        val stderr = drain(context, phase, "stderr", process.errorStream, combinedOutput)
        val completed = timeoutMs?.let { process.waitFor(it, TimeUnit.MILLISECONDS) } ?: run { process.waitFor(); true }
        if (!completed) {
            process.destroyForcibly()
            process.waitFor()
        }
        stdout.join(5_000)
        stderr.join(5_000)
        TerminalDiagnostics.flushProcessOutput()
        if (stdout.isAlive || stderr.isAlive) {
            TerminalDiagnostics.record(context, phase, "output_reader_still_running", mapOf("stdout" to stdout.isAlive, "stderr" to stderr.isAlive))
        }
        val result = ProcessResult(if (completed) process.exitValue() else -1, !completed, combinedOutput.toString())
        TerminalDiagnostics.record(
            context,
            phase,
            if (result.exitCode == 0 && !result.timedOut) "completed" else "failed",
            mapOf("operation" to operation, "exit_code" to result.exitCode, "timed_out" to result.timedOut,
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000, "output_chars" to result.output.length)
        )
        return result
    }

    private fun drain(context: Context, phase: String, streamName: String, input: InputStream, output: StringBuffer): Thread =
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(output) {
                            if (output.length < 64_000) output.append(line).append('\n')
                        }
                        TerminalDiagnostics.appendProcessOutput(context, streamName, "$line\n")
                    }
                }
            }.onFailure { failure ->
                TerminalDiagnostics.record(context, phase, "${streamName}_read_failed", mapOf("error" to failure.message))
            }
        }, "tritonterm-$phase-$streamName").apply { isDaemon = true; start() }

    private fun initializePacmanKeys(context: Context) {
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val marker = File(root, "root/.tritonterm-pacman-keyring-ready")
        if (marker.isFile) {
            TerminalDiagnostics.record(context, "pacman_keyring", "reused")
            return
        }
        mutableState.value = mutableState.value.copy(stage = TerminalSetupStage.INITIALIZING)
        TerminalDiagnostics.record(context, "pacman_key_init", "started")
        val init = runProcess(context, "pacman_key_init", "pacman-key --init", args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman-key --init"
        ))
        check(init.exitCode == 0 && !init.timedOut) { "pacman-key --init failed (exit ${init.exitCode}). See Logs." }
        TerminalDiagnostics.record(context, "pacman_key_populate", "started")
        val populate = runProcess(context, "pacman_key_populate", "pacman-key --populate", args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman-key --populate archlinuxarm"
        ))
        check(populate.exitCode == 0 && !populate.timedOut) { "pacman-key --populate failed (exit ${populate.exitCode}). See Logs." }
        val mark = runProcess(context, "pacman_keyring_marker", "write keyring marker", args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc",
            "touch /root/.tritonterm-pacman-keyring-ready"
        ))
        check(mark.exitCode == 0 && !mark.timedOut) { "Could not persist pacman-key initialization state. See Logs." }
        TerminalDiagnostics.record(context, "pacman_keyring", "completed")
    }
}
