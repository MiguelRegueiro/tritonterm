package com.tritonterm.android.terminal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val error: String? = null,
    val featureWarnings: List<String> = emptyList()
)

/** Owns the one noninteractive install process independently of Compose and terminal sessions. */
internal object ArchLinuxArmInstaller {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(ArchInstallState())
    val state: StateFlow<ArchInstallState> = mutableState.asStateFlow()

    fun start(context: Context): Boolean {
        val appContext = context.applicationContext
        if (ArchLinuxArmBootstrap.isInstalled(appContext)) {
            mutableState.value = mutableState.value.copy(
                stage = TerminalSetupStage.READY, running = false, ready = true,
                error = null, featureWarnings = emptyList()
            )
            return true
        }
        return runSingleFlight(appContext, "setup") { app ->
            TerminalDiagnostics.record(app, "setup", "started", mapOf("launch_mode" to "explicit_process"))
            val progress: (TerminalSetupProgress) -> Unit = { value ->
                mutableState.value = mutableState.value.copy(stage = value.stage, progress = value)
            }
            ArchLinuxArmBootstrap.prepare(app, progress)
            if (!ArchLinuxArmBootstrap.isRootfsExtracted(app)) {
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
            }
            check(ArchLinuxArmBootstrap.isRootfsExtracted(app)) {
                "pr-cli exited successfully, but required rootfs validation failed. Existing data was preserved. See Logs."
            }
            check(ArchRootfsRelease.isPreparedImage(ArchLinuxArmBootstrap.rootfs(app))) {
                "This Arch rootfs is not the pinned TritonTerm development image. It was left untouched. Remove the previous Arch rootfs or reinstall TritonTerm, then install the signed image."
            }
            check(ArchGuestUser.hasUsableShell(ArchLinuxArmBootstrap.rootfs(app))) {
                "The signed image does not contain the required triton login shell (UID/GID 1000, /home/triton, Fish). The image was preserved."
            }
            TerminalDiagnostics.record(
                app,
                "rootfs_validation",
                if (ArchLinuxArmBootstrap.isInstalled(app)) "already_prepared" else "extraction_complete",
                mapOf("rootfs" to ArchLinuxArmBootstrap.rootfs(app).absolutePath)
            )
            val featureWarnings = runCatching { verifyDeveloperImage(app).toMutableList() }
                .getOrElse { failure ->
                    TerminalDiagnostics.record(
                        app, "arch_developer_image_validation", "feature_check_runner_failed",
                        mapOf("error" to failure.toString())
                    )
                    mutableListOf("Feature checks could not run: ${failure.message ?: failure.javaClass.simpleName}")
                }
            runCatching { initializePacmanKeys(app) }
                .onSuccess { warning -> warning?.let(featureWarnings::add) }
                .onFailure { failure ->
                    TerminalDiagnostics.record(app, "pacman_keyring", "feature_check_failed", mapOf("error" to failure.toString()))
                    featureWarnings += "Pacman keyring initialization could not run: ${failure.message ?: failure.javaClass.simpleName}"
                }
            mutableState.value = mutableState.value.copy(featureWarnings = featureWarnings.distinct())
            TerminalDiagnostics.record(
                app,
                "developer_image",
                if (featureWarnings.isEmpty()) "feature_checks_passed" else "feature_checks_completed_with_warnings",
                mapOf(
                    "version" to ArchRootfsRelease.pinnedVersion(),
                    "account" to ArchGuestUser.NAME,
                    "feature_warning_count" to featureWarnings.distinct().size
                )
            )
            TerminalDiagnostics.record(app, "setup", "completed")
        }
    }

    private fun runSingleFlight(context: Context, phase: String, work: suspend (Context) -> Unit): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        val appContext = context.applicationContext
        mutableState.value = mutableState.value.copy(running = true, error = null)
        if (phase == "setup") TerminalForegroundService.startSetup(appContext)
        scope.launch {
            var succeeded = false
            try {
                work(appContext)
                succeeded = true
            } catch (failure: Exception) {
                val message = failure.message ?: failure.javaClass.simpleName
                mutableState.value = mutableState.value.copy(
                    stage = TerminalSetupStage.FAILED,
                    ready = false,
                    error = message
                )
                TerminalDiagnostics.record(appContext, phase, "failed", mapOf("error" to message))
            } finally {
                if (phase == "setup") TerminalForegroundService.stop(appContext)
                mutableState.value = mutableState.value.copy(
                    stage = if (succeeded && phase == "setup") TerminalSetupStage.READY else mutableState.value.stage,
                    progress = if (succeeded && phase == "setup") TerminalSetupProgress(TerminalSetupStage.READY) else mutableState.value.progress,
                    ready = succeeded && phase == "setup",
                    running = false
                )
                inFlight.set(false)
            }
        }
        return true
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
            put("TRITONTERM_PROOT_USERLAND_METADATA", "1")
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
                    "TRITONTERM_PROOT_USERLAND_METADATA" to "1",
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
                        if (phase == "arch_developer_environment" &&
                            ArchLinuxArmInstaller.state.value.stage == TerminalSetupStage.DEVELOPER_TOOLS
                        ) {
                            val current = ArchLinuxArmInstaller.state.value
                            val nextProgress = current.progress.withCommandOutput(line)
                            if (nextProgress !== current.progress) {
                                mutableState.value = current.copy(progress = nextProgress)
                            }
                        }
                        TerminalDiagnostics.appendProcessOutput(context, streamName, "$line\n")
                    }
                }
            }.onFailure { failure ->
                TerminalDiagnostics.record(context, phase, "${streamName}_read_failed", mapOf("error" to failure.message))
            }
        }, "tritonterm-$phase-$streamName").apply { isDaemon = true; start() }

    private fun initializePacmanKeys(context: Context): String? {
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val marker = File(root, "root/.tritonterm-pacman-keyring-ready")
        if (marker.isFile) {
            TerminalDiagnostics.record(context, "pacman_keyring", "reused")
            return null
        }
        mutableState.value = mutableState.value.copy(stage = TerminalSetupStage.PREPARING)
        TerminalDiagnostics.record(context, "pacman_key_init", "started")
        val init = runProcess(context, "pacman_key_init", "pacman-key --init", timeoutMs = 120_000, args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman-key --init"
        ))
        if (init.exitCode != 0 || init.timedOut) {
            val warning = "pacman-key --init failed (exit ${init.exitCode})"
            TerminalDiagnostics.record(context, "pacman_key_init", "feature_check_failed", mapOf("exit_code" to init.exitCode, "timed_out" to init.timedOut))
            return warning
        }
        TerminalDiagnostics.record(context, "pacman_key_populate", "started")
        val populate = runProcess(context, "pacman_key_populate", "pacman-key --populate", timeoutMs = 60_000, args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman-key --populate archlinuxarm"
        ))
        if (populate.exitCode != 0 || populate.timedOut) {
            val warning = "pacman-key --populate failed (exit ${populate.exitCode})"
            TerminalDiagnostics.record(context, "pacman_key_populate", "feature_check_failed", mapOf("exit_code" to populate.exitCode, "timed_out" to populate.timedOut))
            return warning
        }
        val mark = runProcess(context, "pacman_keyring_marker", "write keyring marker", timeoutMs = 20_000, args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc",
            "touch /root/.tritonterm-pacman-keyring-ready"
        ))
        if (mark.exitCode != 0 || mark.timedOut) {
            val warning = "Could not persist pacman-key initialization state (exit ${mark.exitCode})"
            TerminalDiagnostics.record(context, "pacman_keyring_marker", "feature_check_failed", mapOf("exit_code" to mark.exitCode, "timed_out" to mark.timedOut))
            return warning
        }
        TerminalDiagnostics.record(context, "pacman_keyring", "completed")
        return null
    }

    private fun verifyDeveloperImage(context: Context): List<String> {
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val warnings = mutableListOf<String>()
        val missing = ArchDeveloperEnvironment.missingRequirements(root)
        missing.forEach { requirement ->
            warnings += requirement
            TerminalDiagnostics.record(
                context, "arch_developer_image_validation", "feature_check_failed",
                mapOf("check" to requirement, "kind" to "rootfs_content")
            )
        }
        val guestIdentitySmokeScript = context.assets.open("arch-guest-identity-smoke.fish")
            .bufferedReader()
            .use { it.readText() }
        TerminalDiagnostics.record(
            context,
            "arch_developer_image_validation",
            "runtime_smoke_started",
            mapOf("account" to ArchGuestUser.NAME, "uid" to ArchGuestUser.UID, "home" to ArchGuestUser.HOME)
        )
        val result = runProcess(
            context,
            "arch_developer_image_smoke",
            "verify fixed triton account, developer tools, fakeroot, pacman, and Fish configuration",
            timeoutMs = 60_000,
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", ArchGuestUser.NAME,
                "/usr/bin/fish", "-l", "-i", "-c",
                guestIdentitySmokeScript
            )
        )
        val failedChecks = Regex("(?m)^CHECK_FAILED ([^\\s]+)(?: (.*))?$")
            .findAll(result.output)
            .map { match -> match.groupValues[1] + match.groupValues[2].takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty() }
            .toList()
        failedChecks.forEach { failure ->
            warnings += failure
            TerminalDiagnostics.record(
                context, "arch_developer_image_validation", "feature_check_failed",
                mapOf("check" to failure, "exit_code" to result.exitCode, "timed_out" to result.timedOut)
            )
        }
        if (result.timedOut || !result.output.contains("TRITONTERM_GUEST_CHECKS_COMPLETED")) {
            warnings += "Fish feature-check runner did not complete (exit ${result.exitCode}, timed_out=${result.timedOut})"
            TerminalDiagnostics.record(
                context,
                "arch_developer_image_validation",
                "feature_check_runner_failed",
                mapOf("account" to ArchGuestUser.NAME, "exit_code" to result.exitCode, "timed_out" to result.timedOut, "output" to result.output.takeLast(4000))
            )
        }
        TerminalDiagnostics.record(
            context,
            "arch_developer_image_validation",
            if (warnings.isEmpty()) "passed" else "completed_with_warnings",
            mapOf(
                "account" to ArchGuestUser.NAME,
                "uid" to ArchGuestUser.UID,
                "gid" to ArchGuestUser.GID,
                "cwd" to ArchGuestUser.HOME,
                "feature_warning_count" to warnings.distinct().size,
                "path" to Regex("(?m)^CHECK_INFO PATH=(.*)$").find(result.output)?.groupValues?.get(1)
            )
        )
        return warnings.distinct()
    }
}
