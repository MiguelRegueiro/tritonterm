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
    val preflightFailed: Boolean = false,
    val error: String? = null,
    val failureDetails: SetupFailureDetails? = null
)

internal data class SetupFailureDetails(
    val stage: String?,
    val command: String?,
    val exitCode: Int,
    val message: String?,
    val stderr: List<String>
) {
    val displayMessage: String
        get() = buildString {
            append("Setup failed")
            stage?.let { append(" at $it") }
            append(" (exit $exitCode)")
            command?.let { append(" · $it") }
            message?.let { append("\n$it") }
            if (stderr.isNotEmpty()) append("\n\nRecent stderr:\n").append(stderr.joinToString("\n"))
        }
}

internal object ArchDeveloperSetupLog {
    private val failure = Regex("^\\[Arch setup\\] FAILED at ([^ ]+) \\(exit ([0-9]+)\\): (.*)$")
    private val command = Regex("^\\[Arch setup\\] FAILED at .*command: (.*)$")

    fun file(context: Context): File = File(
        ArchLinuxArmBootstrap.rootfs(context),
        "var/cache/tritonterm/developer-setup.log"
    )

    fun read(context: Context): String {
        val log = file(context)
        if (!log.isFile || java.nio.file.Files.isSymbolicLink(log.toPath())) {
            return "The setup log has not been created yet."
        }
        return runCatching { log.readText() }.getOrElse { "Could not read the setup log: ${it.message}" }
    }

    fun parse(text: String, fallbackExitCode: Int): SetupFailureDetails {
        val lines = text.lineSequence().toList()
        val failureLine = lines.lastOrNull { failure.matches(it) }
        val match = failureLine?.let(failure::matchEntire)
        val errorCommand = lines.asReversed().firstNotNullOfOrNullCompat { command.matchEntire(it)?.groupValues?.get(1) }
        val message = match?.groupValues?.get(3)
        val packageCommand = Regex("package transaction failed: (.*)").find(message.orEmpty())?.groupValues?.get(1)
        val stderr = lines.filter { it.startsWith("[stderr] ") }
            .takeLast(12).map { it.removePrefix("[stderr] ").take(300) }
        return SetupFailureDetails(
            stage = match?.groupValues?.get(1),
            command = errorCommand ?: packageCommand,
            exitCode = match?.groupValues?.get(2)?.toIntOrNull() ?: fallbackExitCode,
            message = message,
            stderr = stderr
        )
    }
}

private inline fun <T, R : Any> List<T>.firstNotNullOfOrNullCompat(transform: (T) -> R?): R? {
    for (element in this) transform(element)?.let { return it }
    return null
}

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

    fun start(context: Context, username: String = "dev"): Boolean {
        val appContext = context.applicationContext
        val root = ArchLinuxArmBootstrap.rootfs(appContext)
        val passwd = File(root, "etc/passwd")
        val group = File(root, "etc/group")
        val invalidUsername = ArchLoginName.validate(
            username,
            passwd.takeIf(File::isFile)?.readText().orEmpty(),
            allowPendingRename = ArchLoginName.isPersonalizationPending(root, username) || ArchLoginName.fromRootfs(root) == username,
            groupText = group.takeIf(File::isFile)?.readText().orEmpty()
        )
        if (invalidUsername != null) {
            mutableState.value = mutableState.value.copy(error = invalidUsername)
            return false
        }
        appContext.getSharedPreferences("arch_setup", Context.MODE_PRIVATE)
            .edit().putString("username", username).apply()
        if (ArchLinuxArmBootstrap.isInstalled(appContext)) {
            if (repositoryDatabasesRefreshed(appContext)) {
                mutableState.value = mutableState.value.copy(stage = TerminalSetupStage.READY, running = false, ready = true, error = null)
                return true
            }
            return refreshRepositoryDatabasesOnce(appContext)
        }
        val reusableRootfs = ArchLinuxArmBootstrap.isRootfsExtracted(appContext)
        if (!mutableState.value.preflightPassed && !reusableRootfs) {
            mutableState.value = mutableState.value.copy(error = "Run the harmless installer launcher test before installing Arch Linux ARM.")
            return false
        }
        return runSingleFlight(appContext, "setup") { app ->
            TerminalDiagnostics.record(app, "setup", "started", mapOf("launch_mode" to "explicit_process"))
            val resumingExtractedRootfs = ArchLinuxArmBootstrap.isRootfsExtracted(app)
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
            } else {
                reportPreparationProgress(app, 0, "rootfs_extraction_already_complete")
            }
            check(ArchLinuxArmBootstrap.isRootfsExtracted(app)) {
                "pr-cli exited successfully, but required rootfs validation failed. Existing data was preserved. See Logs."
            }
            check(ArchRootfsRelease.isPreparedImage(ArchLinuxArmBootstrap.rootfs(app))) {
                "This Arch rootfs is not the pinned TritonTerm development image. It was left untouched. Remove the previous Arch rootfs or reinstall TritonTerm, then install the signed image."
            }
            if (resumingExtractedRootfs) validateInterruptedRootfsBeforeResume(app)
            reportPreparationProgress(app, 1, "rootfs_validated")
            TerminalDiagnostics.record(
                app,
                "rootfs_validation",
                if (ArchLinuxArmBootstrap.isInstalled(app)) "already_prepared" else "extraction_complete",
                mapOf("rootfs" to ArchLinuxArmBootstrap.rootfs(app).absolutePath)
            )
            PacmanConfigCompatibility.migrate(app)
            reportPreparationProgress(app, 2, "pacman_configuration_ready")
            initializePacmanKeys(app)
            reportPreparationProgress(app, 10, "prebuilt_developer_tools_validated")
            TerminalDiagnostics.record(
                app,
                "developer_image",
                "prebuilt_image_selected",
                mapOf("version" to ArchRootfsRelease.pinnedVersion(), "username" to username)
            )
            personalizeDeveloperAccount(app, username)
            TerminalDiagnostics.record(app, "setup", "completed")
        }
    }

    fun ensureRepositoryDatabases(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!ArchLinuxArmBootstrap.isInstalled(appContext)) return false
        if (repositoryDatabasesRefreshed(appContext)) return true
        return refreshRepositoryDatabasesOnce(appContext)
    }

    fun repositoryRefreshComplete(context: Context): Boolean = repositoryDatabasesRefreshed(context)

    private fun refreshRepositoryDatabasesOnce(context: Context): Boolean = runSingleFlight(context, "repository_refresh") { app ->
        val progress = TerminalSetupProgress(TerminalSetupStage.PREPARING, stepCompleted = 0, stepTotal = 1)
        mutableState.value = mutableState.value.copy(stage = progress.stage, progress = progress)
        synchronizeRepositoryDatabases(app, reportPreparationProgress = false)
        val complete = progress.copy(stepCompleted = 1)
        mutableState.value = mutableState.value.copy(progress = complete)
        TerminalDiagnostics.record(app, "repository_database_refresh", "completed", mapOf("upgrade_performed" to false))
    }

    private fun runSingleFlight(context: Context, phase: String, work: suspend (Context) -> Unit): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        val appContext = context.applicationContext
        mutableState.value = mutableState.value.copy(running = true, error = null)
        if (phase == "setup" || phase == "repository_refresh") TerminalForegroundService.startSetup(appContext)
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
                    preflightFailed = phase == "installer_preflight",
                    error = if (phase == "setup") {
                        ArchDeveloperSetupLog.file(appContext).takeIf(File::isFile)?.let { log ->
                            runCatching { ArchDeveloperSetupLog.parse(log.readText(), 1).displayMessage }.getOrNull()
                        } ?: message
                    } else message,
                    failureDetails = if (phase == "setup") {
                        ArchDeveloperSetupLog.file(appContext).takeIf(File::isFile)?.let { log ->
                            runCatching { ArchDeveloperSetupLog.parse(log.readText(), 1) }.getOrNull()
                        }
                    } else null
                )
                TerminalDiagnostics.record(appContext, phase, "failed", mapOf("error" to message))
            } finally {
                if (phase == "setup" || phase == "repository_refresh") TerminalForegroundService.stop(appContext)
                mutableState.value = mutableState.value.copy(
                    stage = if (succeeded && (phase == "setup" || phase == "repository_refresh")) TerminalSetupStage.READY else mutableState.value.stage,
                    progress = if (succeeded && (phase == "setup" || phase == "repository_refresh")) TerminalSetupProgress(TerminalSetupStage.READY) else mutableState.value.progress,
                    preflightPassed = mutableState.value.preflightPassed || phase == "setup",
                    preflightFailed = mutableState.value.preflightFailed && phase == "installer_preflight",
                    ready = succeeded && (phase == "setup" || phase == "repository_refresh"),
                    running = false
                )
                inFlight.set(false)
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

    private fun initializePacmanKeys(context: Context) {
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val marker = File(root, "root/.tritonterm-pacman-keyring-ready")
        if (marker.isFile) {
            TerminalDiagnostics.record(context, "pacman_keyring", "reused")
            reportPreparationProgress(context, 5, "pacman_keyring_reused")
            return
        }
        mutableState.value = mutableState.value.copy(stage = TerminalSetupStage.PREPARING)
        TerminalDiagnostics.record(context, "pacman_key_init", "started")
        val init = runProcess(context, "pacman_key_init", "pacman-key --init", args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman-key --init"
        ))
        check(init.exitCode == 0 && !init.timedOut) { "pacman-key --init failed (exit ${init.exitCode}). See Logs." }
        reportPreparationProgress(context, 3, "pacman_keyring_initialized")
        TerminalDiagnostics.record(context, "pacman_key_populate", "started")
        val populate = runProcess(context, "pacman_key_populate", "pacman-key --populate", args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman-key --populate archlinuxarm"
        ))
        check(populate.exitCode == 0 && !populate.timedOut) { "pacman-key --populate failed (exit ${populate.exitCode}). See Logs." }
        reportPreparationProgress(context, 4, "pacman_keyring_populated")
        val mark = runProcess(context, "pacman_keyring_marker", "write keyring marker", args = listOf(
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc",
            "touch /root/.tritonterm-pacman-keyring-ready"
        ))
        check(mark.exitCode == 0 && !mark.timedOut) { "Could not persist pacman-key initialization state. See Logs." }
        reportPreparationProgress(context, 5, "pacman_keyring_ready")
        TerminalDiagnostics.record(context, "pacman_keyring", "completed")
    }

    private fun validateInterruptedRootfsBeforeResume(context: Context) {
        recoverOwnedPacmanLock(context)
        val result = runProcess(
            context,
            "interrupted_rootfs_database_check",
            "pacman -Dk before resuming package preparation",
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/sh", "-lc", "pacman -Dk"
            )
        )
        check(result.exitCode == 0 && !result.timedOut) {
            "The extracted rootfs pacman database did not pass pacman -Dk, so package preparation was not resumed. See Logs; a clean install may be required."
        }
        TerminalDiagnostics.record(context, "interrupted_rootfs_database_check", "passed")
    }

    /** Remove only the generic image's kernel, firmware bundles, and initramfs generator via pacman. */
    private fun prepareDevelopmentUserland(context: Context) {
        val query = runProcess(
            context,
            "userland_package_inventory",
            "pacman -Qq",
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/sh", "-lc", "pacman -Qq"
            )
        )
        check(query.exitCode == 0 && !query.timedOut) {
            "Could not inspect the installed pacman package database (exit ${query.exitCode}). See Logs."
        }
        val installed = query.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        val removalTargets = ArchUserlandProfile.removalCandidates(installed)
        TerminalDiagnostics.record(
            context,
            "userland_package_inventory",
            "captured",
            mapOf("package_count" to installed.size, "packages" to installed.sorted())
        )
        TerminalDiagnostics.record(
            context,
            "userland_profile",
            "removal_plan",
            mapOf("installed_package_count" to installed.size, "packages" to removalTargets)
        )
        reportPreparationProgress(context, 6, "package_inventory_ready")
        if (removalTargets.isNotEmpty()) {
            val remove = runManagedPacman(
                context,
                "userland_package_removal",
                "pacman -Rns ${removalTargets.joinToString(" ")}",
                args = listOf(
                    "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                    "/bin/sh", "-lc",
                    "pacman -Rns --noconfirm -- ${removalTargets.joinToString(" ")}"
                )
            )
            check(remove.exitCode == 0 && !remove.timedOut) {
                "Could not remove the generic image's kernel/firmware packages safely (exit ${remove.exitCode}). Pacman left dependency checks enabled. See Logs."
            }
        }
        reportPreparationProgress(context, 7, "boot_packages_removed")

        val after = runProcess(
            context,
            "userland_package_validation",
            "validate package inventory and pacman database",
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/sh", "-lc", "pacman -Qq && pacman -Dk"
            )
        )
        check(after.exitCode == 0 && !after.timedOut) {
            "Pacman's local database is inconsistent after userland preparation (exit ${after.exitCode}). See Logs."
        }
        val remaining = ArchUserlandProfile.removalCandidates(
            after.output.lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        )
        check(remaining.isEmpty()) {
            "Boot-only packages remain after preparation: ${remaining.joinToString()}. See Logs."
        }
        synchronizeRepositoryDatabases(context)
        val mark = runProcess(
            context,
            "userland_profile_marker",
            "write validated userland profile marker",
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/sh", "-lc", "touch /root/.tritonterm-userland-profile-v1"
            )
        )
        check(mark.exitCode == 0 && !mark.timedOut) { "Could not mark the validated Arch userland profile. See Logs." }
        reportPreparationProgress(context, 9, "userland_profile_validated")
        TerminalDiagnostics.record(
            context,
            "userland_profile",
            "validated",
            mapOf("removed_packages" to removalTargets, "database_check" to "pacman -Dk")
        )
    }

    /** Provisions the complete default dev environment after the signed rootfs is prepared. */
    private fun provisionDeveloperEnvironment(context: Context) {
        reportDeveloperToolsProgress(context, 9, "installing_arch_developer_environment")
        TerminalDiagnostics.record(
            context,
            "arch_developer_environment",
            "started",
            mapOf(
                "fakeroot_version" to ArchDeveloperEnvironment.FAKEROOT_VERSION,
                "rustup" to "stable",
                "paru_version" to ArchDeveloperEnvironment.PARU_VERSION,
                "source_sha256" to ArchDeveloperEnvironment.SOURCE_SHA256
            )
        )
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val assetDir = File(root, "root/.tritonterm-setup")
        check(!java.nio.file.Files.isSymbolicLink(assetDir.toPath())) {
            "A symbolic link occupies TritonTerm's reserved setup directory. Remove it from the guest rootfs and retry."
        }
        check(assetDir.mkdirs() || assetDir.isDirectory) { "Could not create the setup asset directory in the Arch rootfs." }
        listOf("setup-arch-developer.sh", "starship.toml", "paru-bootstrap.PKGBUILD").forEach { name ->
            val destination = File(assetDir, name)
            val staged = File(assetDir, "$name.new-${System.currentTimeMillis()}")
            context.assets.open(name).use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
            try {
                java.nio.file.Files.move(
                    staged.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(staged.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val result = runProcess(
            context,
            "arch_developer_environment",
            "install fish, Starship, rustup stable, TCP fakeroot, and Paru",
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/bash", "-lc", "bash /root/.tritonterm-setup/setup-arch-developer.sh"
            )
        )
        check(result.exitCode == 0 && !result.timedOut) {
            "Arch developer environment setup failed (exit ${result.exitCode}). See the precise failing stage in Logs and /var/cache/tritonterm/developer-setup.log."
        }
        check(ArchDeveloperEnvironment.isReady(ArchLinuxArmBootstrap.rootfs(context))) {
            "Developer setup exited successfully, but the complete toolchain did not pass readiness validation. See Logs."
        }
        reportPreparationProgress(context, 10, "arch_developer_tools_validated")
        TerminalDiagnostics.record(
            context,
            "arch_developer_environment",
            "validated",
            mapOf(
                "fakeroot_version" to ArchDeveloperEnvironment.FAKEROOT_VERSION,
                "rustup_default" to "stable-aarch64-unknown-linux-gnu",
                "paru_version" to ArchDeveloperEnvironment.PARU_VERSION,
                "pacman_fakeroot_unchanged" to true,
                "makepkg_smoke" to true,
                "paru_smoke" to true,
                "rust_build_smoke" to true
            )
        )
    }

    private fun personalizeDeveloperAccount(context: Context, username: String) {
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val script = File(root, "root/.tritonterm-setup/personalize-arch-account.sh")
        check(script.parentFile?.mkdirs() == true || script.parentFile?.isDirectory == true) {
            "Could not prepare account personalization."
        }
        context.assets.open("personalize-arch-account.sh").use { input ->
            val staged = File(script.parentFile, "${script.name}.new-${System.currentTimeMillis()}")
            staged.outputStream().use { output -> input.copyTo(output) }
            try {
                java.nio.file.Files.move(
                    staged.toPath(), script.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(staged.toPath(), script.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        reportPreparationProgress(context, 11, "personalizing_development_account")
        TerminalDiagnostics.record(
            context,
            "arch_account_personalization",
            "started",
            mapOf("username" to username, "template_uid" to 1000, "home_move" to true)
        )
        val result = runProcess(
            context,
            "arch_account_personalization",
            "rename template account to $username",
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/bash", script.absolutePath, username
            )
        )
        check(result.exitCode == 0 && !result.timedOut) {
            "Could not safely personalize the Arch account (exit ${result.exitCode}). The rootfs was preserved; see the setup log."
        }
        check(ArchLoginName.fromRootfs(root) == username) {
            "Account personalization returned success without persisting the selected username."
        }
        TerminalDiagnostics.record(context, "arch_account_personalization", "completed", mapOf("username" to username))
    }

    /** Force fresh official repository databases once; this does not upgrade installed packages. */
    private fun synchronizeRepositoryDatabases(context: Context, reportPreparationProgress: Boolean = true) {
        val marker = repositoryRefreshMarker(context)
        if (marker.isFile) {
            if (reportPreparationProgress) reportPreparationProgress(context, 8, "repository_databases_already_refreshed")
            return
        }
        recoverOwnedPacmanLock(context)
        TerminalDiagnostics.record(context, "repository_database_refresh", "started", mapOf("command" to "pacman -Syy --noconfirm"))
        val sync = runManagedPacman(
            context,
            "repository_database_refresh",
            "pacman -Syy --noconfirm",
            args = listOf("login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "pacman -Syy --noconfirm")
        )
        check(sync.exitCode == 0 && !sync.timedOut) {
            "Could not refresh official Arch Linux ARM repository databases (exit ${sync.exitCode}). No system packages were upgraded. See Logs."
        }
        val mark = runProcess(
            context,
            "repository_database_refresh_marker",
            "mark repository databases refreshed",
            args = listOf("login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root", "/bin/sh", "-lc", "touch /root/.tritonterm-repo-databases-v1")
        )
        check(mark.exitCode == 0 && !mark.timedOut) { "Repository databases refreshed, but refresh state could not be saved. See Logs." }
        if (reportPreparationProgress) reportPreparationProgress(context, 8, "repository_databases_refreshed")
    }

    private fun runManagedPacman(
        context: Context,
        phase: String,
        operation: String,
        args: List<String>
    ): ProcessResult {
        recoverOwnedPacmanLock(context)
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val marker = File(root, "var/cache/tritonterm/pacman-transaction")
        check(marker.parentFile?.mkdirs() == true || marker.parentFile?.isDirectory == true) {
            "Could not prepare the pacman recovery marker."
        }
        marker.writeText("owner=tritonterm\nstage=$phase\ncommand=$operation\n")
        return try {
            runProcess(context, phase, operation, args = args)
        } finally {
            val lock = File(root, "var/lib/pacman/db.lck")
            if (!lock.exists()) marker.delete()
        }
    }

    private fun recoverOwnedPacmanLock(context: Context) {
        val root = ArchLinuxArmBootstrap.rootfs(context)
        val lock = File(root, "var/lib/pacman/db.lck")
        if (!lock.exists()) return
        val state = PacmanRecoveryState.state(context)
        check(state != PacmanRecoveryState.LockState.ACTIVE_TRANSACTION) {
            "A pacman process is still active; its database lock was preserved and setup will not start another package operation."
        }
        val marker = File(root, "var/cache/tritonterm/pacman-transaction")
        val markerOwned = marker.isFile && runCatching { marker.readLines().contains("owner=tritonterm") }.getOrDefault(false)
        val legacyOwned = PacmanRecoveryState.hasLegacySetupEvidence(root)
        check(markerOwned || legacyOwned) {
            "A stale-looking pacman lock has no TritonTerm transaction record. It was preserved for manual inspection."
        }
        val mode = runCatching { Os.lstat(lock.absolutePath).st_mode }.getOrNull()
        check(mode != null && OsConstants.S_ISREG(mode) && !OsConstants.S_ISLNK(mode)) {
            "The pacman lock path is not a regular file. It was preserved for inspection."
        }
        check(state == PacmanRecoveryState.LockState.POSSIBLY_STALE) {
            "Pacman's lock state could not be established; the lock was preserved."
        }
        val guestProcessCheck = runProcess(
            context,
            "pacman_interruption_recovery",
            "check for a live guest pacman process",
            timeoutMs = 10_000L,
            args = listOf(
                "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
                "/bin/sh", "-lc", "pgrep -x pacman >/dev/null"
            )
        )
        check(!guestProcessCheck.timedOut && guestProcessCheck.exitCode == 1) {
            "A pacman process is still active or its state could not be checked; the lock was preserved."
        }
        Os.remove(lock.absolutePath)
        marker.delete()
        TerminalDiagnostics.record(
            context,
            "pacman_interruption_recovery",
            "stale_owned_lock_removed",
            mapOf(
                "lock_path" to lock.absolutePath,
                "ownership_evidence" to if (markerOwned) "transaction_marker" else "legacy_setup_log",
                "database_check_follows" to true
            )
        )
    }

    private fun repositoryDatabasesRefreshed(context: Context): Boolean = repositoryRefreshMarker(context).isFile

    private fun repositoryRefreshMarker(context: Context): File =
        File(ArchLinuxArmBootstrap.rootfs(context), "root/.tritonterm-repo-databases-v1")

    private fun reportPreparationProgress(context: Context, completed: Int, checkpoint: String) {
        val progress = TerminalSetupProgress(
            stage = TerminalSetupStage.PREPARING,
            stepCompleted = completed,
            stepTotal = PREPARATION_STEP_COUNT
        )
        mutableState.value = mutableState.value.copy(stage = progress.stage, progress = progress)
        TerminalDiagnostics.record(
            context,
            "userland_preparation_progress",
            checkpoint,
            mapOf("completed_steps" to completed, "total_steps" to PREPARATION_STEP_COUNT)
        )
    }

    private fun reportDeveloperToolsProgress(context: Context, completed: Int, checkpoint: String) {
        val progress = TerminalSetupProgress(
            stage = TerminalSetupStage.DEVELOPER_TOOLS,
            stepCompleted = completed,
            stepTotal = PREPARATION_STEP_COUNT
        )
        mutableState.value = mutableState.value.copy(stage = progress.stage, progress = progress)
        TerminalDiagnostics.record(
            context,
            "userland_preparation_progress",
            checkpoint,
            mapOf("completed_steps" to completed, "total_steps" to PREPARATION_STEP_COUNT)
        )
    }

    private const val PREPARATION_STEP_COUNT = 11
}

/** Package names are discovered from pacman; unrelated linux-* libraries and tools are retained. */
internal object ArchUserlandProfile {
    fun removalCandidates(installedPackages: Set<String>): List<String> = installedPackages
        .filter { packageName ->
            packageName == "linux-aarch64" ||
                packageName == "linux-firmware" ||
                (packageName.startsWith("linux-firmware-") && packageName != "linux-firmware-tools") ||
                packageName == "mkinitcpio" ||
                packageName == "mkinitcpio-busybox"
        }
        .sorted()
}
