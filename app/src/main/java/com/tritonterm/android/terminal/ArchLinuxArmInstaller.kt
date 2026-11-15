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
            return runSingleFlight(appContext, "setup") { app ->
                TerminalDiagnostics.record(app, "setup", "started", mapOf("launch_mode" to "validate_existing_rootfs"))
                check(ArchRootfsRelease.isPreparedImage(ArchLinuxArmBootstrap.rootfs(app))) {
                    "The installed Arch rootfs does not match the APK's pinned development image. It was left untouched."
                }
                check(ArchGuestUser.hasUsableShell(ArchLinuxArmBootstrap.rootfs(app))) {
                    "The installed rootfs does not contain the triton Fish login. It was left untouched."
                }
                val featureWarnings = runCatching { verifyDeveloperImage(app).toMutableList() }
                    .getOrElse { failure ->
                        TerminalDiagnostics.record(
                            app, "arch_developer_image_validation", "feature_check_runner_failed",
                            mapOf("error" to failure.toString())
                        )
                        mutableListOf("Feature checks could not run: ${failure.message ?: failure.javaClass.simpleName}")
                    }
                check(initializePacmanKeys(app) == null) {
                    "Arch Linux ARM signing keys could not be initialized and verified. Setup is not ready; retry setup after checking the setup log."
                }
                requireTcpFakeroot(featureWarnings)
                requireRustToolchain(featureWarnings)
                mutableState.value = mutableState.value.copy(featureWarnings = featureWarnings.distinct())
                TerminalDiagnostics.record(
                    app,
                    "developer_image",
                    if (featureWarnings.isEmpty()) "feature_checks_passed" else "feature_checks_completed_with_warnings",
                    mapOf(
                        "version" to ArchRootfsRelease.pinnedVersion(),
                        "account" to ArchGuestUser.NAME,
                        "feature_warning_count" to featureWarnings.distinct().size,
                        "feature_warnings" to featureWarnings.distinct()
                    )
                )
                TerminalDiagnostics.record(app, "setup", "completed", mapOf("rootfs_reused" to true))
            }
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
            check(initializePacmanKeys(app) == null) {
                "Arch Linux ARM signing keys could not be initialized and verified. Setup is not ready; retry setup after checking the setup log."
            }
            requireTcpFakeroot(featureWarnings)
            requireRustToolchain(featureWarnings)
            mutableState.value = mutableState.value.copy(featureWarnings = featureWarnings.distinct())
            TerminalDiagnostics.record(
                app,
                "developer_image",
                if (featureWarnings.isEmpty()) "feature_checks_passed" else "feature_checks_completed_with_warnings",
                mapOf(
                    "version" to ArchRootfsRelease.pinnedVersion(),
                    "account" to ArchGuestUser.NAME,
                    "feature_warning_count" to featureWarnings.distinct().size,
                    "feature_warnings" to featureWarnings.distinct()
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
                if (succeeded && phase == "setup") {
                    ArchLinuxArmBootstrap.removeDownloadedArchiveAfterSuccess(appContext)
                }
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
        args: List<String>,
        environmentOverrides: Map<String, String> = emptyMap(),
        recordOutput: Boolean = true
    ): ProcessResult {
        val cli = File(context.applicationInfo.nativeLibraryDir, "libpr-cli.so")
        val prefix = File(context.filesDir, "usr")
        val home = File(context.filesDir, "home")
        val temp = ProotTempDirectory.forApp(context)
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
            put("LANG", ArchGuestEnvironment.LANG)
            put("TZ", ArchGuestEnvironment.timezoneId())
            environmentOverrides.forEach { (key, value) -> put(key, value) }
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
                    "LANG" to ArchGuestEnvironment.LANG,
                    "TZ" to ArchGuestEnvironment.timezoneId()
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
        val stdout = drain(context, phase, "stdout", process.inputStream, combinedOutput, recordOutput)
        val stderr = drain(context, phase, "stderr", process.errorStream, combinedOutput, recordOutput)
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

    private fun drain(
        context: Context,
        phase: String,
        streamName: String,
        input: InputStream,
        output: StringBuffer,
        recordOutput: Boolean
    ): Thread =
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
                        if (recordOutput) TerminalDiagnostics.appendProcessOutput(context, streamName, "$line\n")
                    }
                }
            }.onFailure { failure ->
                TerminalDiagnostics.record(context, phase, "${streamName}_read_failed", mapOf("error" to failure.message))
            }
        }, "tritonterm-$phase-$streamName").apply { isDaemon = true; start() }

    private fun initializePacmanKeys(context: Context): String? {
        val progress = TerminalSetupProgress(TerminalSetupStage.PREPARING)
        mutableState.value = mutableState.value.copy(stage = progress.stage, progress = progress)
        return runCatching { repairPacmanKeyring(context); null }
            .onFailure { failure ->
                TerminalDiagnostics.record(context, "pacman_keyring", "feature_check_failed", mapOf("error" to failure.toString()))
            }
            .getOrElse { failure -> "Pacman keyring initialization/population failed: ${failure.message ?: failure.javaClass.simpleName}" }
    }

    private fun requireTcpFakeroot(featureWarnings: List<String>) {
        val failures = featureWarnings.filter { warning ->
            warning.contains("fakeroot", ignoreCase = true) ||
                warning.contains("faked", ignoreCase = true) ||
                warning.contains("feature checks could not run", ignoreCase = true) ||
                warning.contains("feature-check runner did not complete", ignoreCase = true)
        }
        check(failures.isEmpty()) {
            "TCP fakeroot did not pass its installed-image checks: ${failures.joinToString("; ")}"
        }
    }

    private fun requireRustToolchain(featureWarnings: List<String>) {
        val failures = featureWarnings.filter { warning ->
            warning.contains("rust", ignoreCase = true) || warning.contains("cargo", ignoreCase = true)
        }
        check(failures.isEmpty()) {
            "The preinstalled Rustup stable toolchain did not pass its guest checks: ${failures.joinToString("; ")}"
        }
    }

    private fun verifyDeveloperImage(context: Context): List<String> {
        val progress = TerminalSetupProgress(TerminalSetupStage.DEVELOPER_TOOLS)
        mutableState.value = mutableState.value.copy(stage = progress.stage, progress = progress)
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val warnings = mutableListOf<String>()
        recordPermissionModelProbe(context, root)
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
                mapOf("account" to ArchGuestUser.NAME, "exit_code" to result.exitCode, "timed_out" to result.timedOut, "output_tail" to result.output.takeLast(4000))
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
                "feature_warnings" to warnings.distinct(),
                "path" to Regex("(?m)^CHECK_INFO PATH=(.*)$").find(result.output)?.groupValues?.get(1),
                "smoke_output_tail" to result.output.takeLast(4000)
            )
        )
        return warnings.distinct()
    }

    /** Compare Android inode/sidecar metadata with stat, access, and exec under the shipped PRoot. */
    internal fun runPermissionModelProbe(context: Context) {
        val appContext = context.applicationContext
        val root = ArchLinuxArmBootstrap.rootfs(appContext)
        check(ArchLinuxArmBootstrap.isRootfsExtracted(appContext)) { "Arch rootfs is not installed." }
        recordPermissionModelProbe(appContext, root)
    }

    /** Read-only package environment probe using the same login Fish as the interactive terminal. */
    internal fun runPackageManagerProbe(context: Context) {
        val appContext = context.applicationContext
        check(ArchLinuxArmBootstrap.isRootfsExtracted(appContext)) { "Arch rootfs is not installed." }
        recordPermissionModelProbe(appContext, ArchLinuxArmBootstrap.rootfs(appContext))
        val args = listOf("login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", ArchGuestUser.NAME,
            "/usr/bin/fish", "-l", "-i", "-c", ProotPermissionProbe.packageManagerScript())
        val result = runProcess(
            appContext,
            "package_manager_probe",
            "selected fakeroot, library access, signing-key inventory, and independent DNS checks",
            timeoutMs = 30_000,
            args = args,
            recordOutput = false
        )
        TerminalDiagnostics.record(
            appContext,
            "package_manager_probe",
            if (result.exitCode == 0 && !result.timedOut) "completed" else "failed",
            mapOf("exit_code" to result.exitCode, "timed_out" to result.timedOut, "output" to result.output.takeLast(5000))
        )
        check(result.exitCode == 0 && !result.timedOut) {
            "Package environment probe failed (exit ${result.exitCode}, timed_out=${result.timedOut}): ${result.output.takeLast(1200)}"
        }
    }

    /** Rebuild device-local pacman trust from the official keyring packaged in the rootfs. */
    internal fun repairPacmanKeyring(context: Context): String {
        val appContext = context.applicationContext
        check(ArchLinuxArmBootstrap.isRootfsExtracted(appContext)) { "Arch rootfs is not installed." }
        val fingerprint = ProotPermissionProbe.ALARM_SIGNING_FINGERPRINT
        val command = ProotPermissionProbe.pacmanKeyringRepairScript()
        TerminalDiagnostics.record(appContext, "pacman_keyring_repair", "started", mapOf("fingerprint" to fingerprint))
        val result = runProcess(
            appContext,
            "pacman_keyring_repair",
            "initialize and populate the official Arch Linux ARM keyring",
            timeoutMs = 180_000,
            args = listOf("login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", command),
            recordOutput = false
        )
        val passed = !result.timedOut && result.exitCode == 0 &&
            result.output.contains("TRITONTERM_PACMAN_KEYRING_REPAIRED fingerprint=$fingerprint")
        TerminalDiagnostics.record(
            appContext,
            "pacman_keyring_repair",
            if (passed) "completed" else "failed",
            mapOf("exit_code" to result.exitCode, "timed_out" to result.timedOut, "output" to result.output.takeLast(5000))
        )
        check(passed) {
            "Pacman keyring repair failed (exit ${result.exitCode}, timed_out=${result.timedOut}): ${result.output.takeLast(2000)}"
        }
        return result.output
    }

    private fun recordPermissionModelProbe(context: Context, root: File) {
        val targets = ProotPermissionProbe.targets
        val hostSnapshot = targets.associate { relative ->
            val file = File(root, relative)
            val sidecar = ProotPermissionProbe.sidecarPath(root, relative)
            val inode = runCatching { android.system.Os.stat(file.absolutePath) }.getOrNull()
            val sidecarStat = runCatching { android.system.Os.stat(sidecar.absolutePath) }.getOrNull()
            relative to mapOf(
                "exists" to file.exists(),
                "host_mode_octal" to inode?.st_mode?.and(0xFFF)?.let(Integer::toOctalString),
                "host_uid" to inode?.st_uid,
                "host_gid" to inode?.st_gid,
                "host_access_x_ok" to runCatching {
                    android.system.Os.access(file.absolutePath, android.system.OsConstants.X_OK)
                    true
                }.getOrDefault(false),
                "host_access_x_errno" to runCatching {
                    android.system.Os.access(file.absolutePath, android.system.OsConstants.X_OK)
                    0
                }.getOrElse { failure ->
                    (failure as? android.system.ErrnoException)?.errno ?: -1
                },
                "sidecar_exists" to sidecar.isFile,
                "sidecar_mode_octal" to sidecarStat?.st_mode?.and(0xFFF)?.let(Integer::toOctalString),
                "sidecar_contents" to runCatching { sidecar.readText().trim() }.getOrNull()
            )
        }
        TerminalDiagnostics.record(
            context,
            "proot_permission_probe",
            "android_host_snapshot",
            mapOf("entries" to hostSnapshot)
        )

        val login = listOf("login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", ArchGuestUser.NAME)
        val statResult = runProcess(
            context,
            "proot_permission_probe",
            "guest stat modes and owners",
            timeoutMs = 15_000,
            args = login + listOf("/usr/bin/stat", "-c", "%A %a %u:%g %n") + targets.map { "/$it" },
            recordOutput = false
        )
        TerminalDiagnostics.record(
            context,
            "proot_permission_probe",
            "guest_stat_result",
            mapOf(
                "exit_code" to statResult.exitCode,
                "timed_out" to statResult.timedOut,
                "output" to statResult.output.takeLast(6000)
            )
        )

        val accessResult = runProcess(
            context,
            "proot_permission_probe",
            "Fish test -x through PRoot",
            timeoutMs = 15_000,
            args = login + listOf("/usr/bin/fish", "-l", "-c", ProotPermissionProbe.fishAccessScript()),
            recordOutput = false
        )
        TerminalDiagnostics.record(
            context,
            "proot_permission_probe",
            "access_result",
            mapOf(
                "exit_code" to accessResult.exitCode,
                "timed_out" to accessResult.timedOut,
                "output" to accessResult.output.takeLast(6000)
            )
        )

        val probeName = ".tritonterm-permission-probe-${android.os.Process.myPid()}.pl"
        // PRoot bind-mounts the app's PROOT_TMP_DIR over guest /tmp. Staging
        // this under rootfs/tmp makes the host file invisible to the guest.
        val guestSource = File(ProotTempDirectory.forApp(context), probeName)
        val sourceAvailable = runCatching {
            context.assets.open("proot-permission-syscall-probe.pl").use { input ->
                guestSource.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { failure ->
            TerminalDiagnostics.record(
                context, "proot_permission_probe", "syscall_source_copy_failed",
                mapOf("error" to failure.toString(), "guest_source" to guestSource.absolutePath)
            )
        }.isSuccess
        if (sourceAvailable) {
            try {
                listOf(
                    "/usr/bin/id",
                    "/opt/tritonterm/fakeroot-tcp/2.1.4/bin/faked",
                    "/opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot.so",
                    "/opt/tritonterm/fakeroot-tcp/2.1.4/lib/libfakeroot-0.so"
                ).forEach { target ->
                    val syscallResult = runProcess(
                        context,
                        "proot_permission_probe",
                        "stat/faccessat/faccessat2 errno probe on $target",
                        timeoutMs = 15_000,
                        args = login + listOf("/usr/bin/perl", "/tmp/$probeName", target),
                        recordOutput = false
                    )
                    TerminalDiagnostics.record(
                        context,
                        "proot_permission_probe",
                        "syscall_probe_result",
                        mapOf("path" to target, "exit_code" to syscallResult.exitCode,
                            "timed_out" to syscallResult.timedOut, "output" to syscallResult.output.takeLast(3000))
                    )
                }
            } finally {
                guestSource.delete()
            }
        }

        val execResult = runProcess(
            context,
            "proot_permission_probe",
            "direct PRoot execve of /usr/bin/id",
            timeoutMs = 15_000,
            args = login + listOf("/usr/bin/id"),
            recordOutput = false
        )
        TerminalDiagnostics.record(
            context,
            "proot_permission_probe",
            "execve_result",
            mapOf(
                "exit_code" to execResult.exitCode,
                "timed_out" to execResult.timedOut,
                "output" to execResult.output.takeLast(6000)
            )
        )
    }
}
