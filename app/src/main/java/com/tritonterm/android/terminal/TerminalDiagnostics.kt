package com.tritonterm.android.terminal

import android.content.Context
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object TerminalDiagnostics {
    private const val MAX_OUTPUT_CHARS = 16_000
    private const val MAX_OUTPUT_TOTAL_CHARS = 300_000L
    private val outputCharacters = AtomicLong()
    private val outputWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terminal-diagnostics-output").apply { isDaemon = true }
    }

    fun file(context: Context) = File(context.noBackupFilesDir, "tritonterm-diagnostics.jsonl")

    @Synchronized
    fun record(context: Context, phase: String, status: String, details: Map<String, Any?> = emptyMap()) {
        runCatching {
            val event = JSONObject()
                .put("time", Instant.now().toString())
                .put("source", "android")
                .put("phase", phase)
                .put("status", status)
                .put("details", JSONObject(details))
                .toString() + "\n"
            FileOutputStream(file(context), true).use { output ->
                output.write(event.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }
    }

    fun appendProcessOutput(context: Context, stream: String, text: String) {
        val bounded = text.takeLast(MAX_OUTPUT_CHARS)
        if (bounded.isNotBlank() && outputCharacters.addAndGet(bounded.length.toLong()) <= MAX_OUTPUT_TOTAL_CHARS) {
            outputWriter.execute { record(context, "process_output", "captured", mapOf("stream" to stream, "text" to bounded)) }
        }
    }

    fun flushProcessOutput() {
        runCatching { outputWriter.submit {}.get(10, TimeUnit.SECONDS) }
    }

    fun read(context: Context): String = runCatching { file(context).readText() }
        .getOrDefault("Diagnostics are not available yet.\n")
}

internal data class ProotSmokeResult(
    val name: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val output: String,
    val error: String? = null,
    val passed: Boolean
) {
    fun summary(): String = if (passed) {
        "$name: PASS · exit $exitCode · ${output.trim().take(400)}"
    } else {
        "$name: FAIL · exit $exitCode · timeout=$timedOut · ${error.orEmpty()} ${output.trim().take(700)}"
    }
}

internal object RuntimeDiagnosticsProbes {
    fun run(context: Context): List<ProotSmokeResult> = listOf(
        runRootProot(context),
        runIsolatedSystemBinds(context),
        runPrCliHelp(context)
    )

    private fun runRootProot(context: Context): ProotSmokeResult {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        val shell = File("/system/bin/sh")
        val linker = File("/system/bin/linker64")
        val linkerTarget = runCatching { Os.readlink(linker.absolutePath) }.getOrNull()
        return runProbe(
            context = context,
            name = "proot_root",
            phase = "proot_root_smoke",
            proot = proot,
            loader = loader,
            args = listOf(
                "--rootfs=/",
                "--cwd=/",
                "--kill-on-exit",
                shell.absolutePath,
                "-c",
                "printf 'TRITONTERM_PROOT_ROOT_OK\\n'"
            ),
            extra = mapOf(
                "proot_rootfs" to "/",
                "host_shell_exists" to shell.isFile,
                "host_shell_executable" to shell.canExecute(),
                "host_linker_exists" to linker.exists(),
                "host_linker_executable" to linker.canExecute(),
                "host_linker_symlink_target" to linkerTarget,
                "host_linker_canonical_path" to runCatching { linker.canonicalPath }.getOrNull()
            ),
            successMarker = "TRITONTERM_PROOT_ROOT_OK"
        )
    }

    private fun runIsolatedSystemBinds(context: Context): ProotSmokeResult {
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        val system = File("/system")
        val apex = File("/apex")
        val linker = File("/system/bin/linker64")
        val linkerTarget = runCatching { Os.readlink(linker.absolutePath) }.getOrNull()
        val baseDetails = mapOf(
            "proot_rootfs" to "isolated_temp_root",
            "system_exists" to system.exists(),
            "system_readable" to system.canRead(),
            "apex_exists" to apex.exists(),
            "apex_readable" to apex.canRead(),
            "apex_searchable" to apex.canExecute(),
            "host_linker_exists" to linker.exists(),
            "host_linker_executable" to linker.canExecute(),
            "host_linker_symlink_target" to linkerTarget,
            "host_linker_canonical_path" to runCatching { linker.canonicalPath }.getOrNull()
        )
        if (!system.isDirectory || !system.canRead() || !apex.isDirectory) {
            val missing = buildList {
                if (!system.isDirectory || !system.canRead()) add("/system is missing or unreadable")
                if (!apex.isDirectory) add("/apex is missing")
            }.joinToString("; ")
            TerminalDiagnostics.record(
                context,
                "proot_isolated_apex_smoke",
                "failed_precondition",
                baseDetails + ("error" to missing)
            )
            return ProotSmokeResult("proot_isolated_apex", null, false, "", missing, false)
        }

        val work = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "proot-isolated-").toFile()
        return try {
            val rootfs = File(work, "rootfs")
            val temp = File(rootfs, "tmp")
            listOf("system", "apex", "proc", "dev", "sys", "tmp").forEach { name ->
                check(File(rootfs, name).mkdirs() || File(rootfs, name).isDirectory) {
                    "Could not create isolated mountpoint: /$name"
                }
            }
            runProbe(
                context = context,
                name = "proot_isolated_apex",
                phase = "proot_isolated_apex_smoke",
                proot = proot,
                loader = loader,
                temp = temp,
                args = listOf(
                    "--rootfs=${rootfs.absolutePath}",
                    "--bind=/system:/system",
                    "--bind=/apex:/apex",
                    "--bind=/proc:/proc",
                    "--bind=/dev:/dev",
                    "--bind=/sys:/sys",
                    "--cwd=/",
                    "--kill-on-exit",
                    "/system/bin/sh",
                    "-c",
                    "printf 'TRITONTERM_PROOT_ISOLATED_OK\\n'; " +
                        "if [ -x /system/bin/linker64 ]; then " +
                        "printf 'TRITONTERM_GUEST_LINKER_OK\\n'; " +
                        "else printf 'TRITONTERM_GUEST_LINKER_MISSING\\n'; exit 42; fi; " +
                        "if [ -x ${shellQuote(runCatching { linker.canonicalPath }.getOrDefault("/apex/com.android.runtime/bin/linker64"))} ]; then " +
                        "printf 'TRITONTERM_GUEST_APEX_LINKER_OK\\n'; " +
                        "else printf 'TRITONTERM_GUEST_APEX_LINKER_MISSING\\n'; exit 44; fi; " +
                        "if [ -d /apex ]; then printf 'TRITONTERM_GUEST_APEX_OK\\n'; " +
                        "else printf 'TRITONTERM_GUEST_APEX_MISSING\\n'; exit 43; fi"
                ),
                extra = baseDetails,
                successMarker = "TRITONTERM_GUEST_LINKER_OK",
                alsoRequire = listOf("TRITONTERM_GUEST_APEX_OK", "TRITONTERM_GUEST_APEX_LINKER_OK")
            )
        } catch (failure: Exception) {
            val message = failure.message ?: failure.javaClass.simpleName
            TerminalDiagnostics.record(
                context,
                "proot_isolated_apex_smoke",
                "failed_precondition",
                baseDetails + ("error" to message)
            )
            ProotSmokeResult("proot_isolated_apex", null, false, "", message, false)
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    private fun runPrCliHelp(context: Context): ProotSmokeResult {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libpr-cli.so")
        return runProbe(
            context = context,
            name = "pr_cli_help_direct",
            phase = "pr_cli_help_direct",
            executable = binary,
            args = listOf("--help"),
            extra = mapOf(
                "binary_path" to binary.absolutePath,
                "binary_exists" to binary.isFile,
                "binary_executable" to binary.canExecute(),
                "binary_bytes" to binary.length()
            ),
            successMarker = "Usage:"
        )
    }

    private fun runProbe(
        context: Context,
        name: String,
        phase: String,
        executable: File,
        args: List<String>,
        extra: Map<String, Any?>,
        successMarker: String,
        alsoRequire: List<String> = emptyList()
    ): ProotSmokeResult = runProbe(
        context,
        name,
        phase,
        executable,
        loader = null,
        temp = null,
        args,
        extra,
        successMarker,
        alsoRequire
    )

    private fun runProbe(
        context: Context,
        name: String,
        phase: String,
        proot: File,
        loader: File,
        args: List<String>,
        extra: Map<String, Any?>,
        successMarker: String,
        alsoRequire: List<String> = emptyList()
    ): ProotSmokeResult = runProbe(
        context,
        name,
        phase,
        proot,
        loader,
        null,
        args,
        extra,
        successMarker,
        alsoRequire
    )

    private fun runProbe(
        context: Context,
        name: String,
        phase: String,
        proot: File,
        loader: File?,
        temp: File?,
        args: List<String>,
        extra: Map<String, Any?>,
        successMarker: String,
        alsoRequire: List<String> = emptyList()
    ): ProotSmokeResult {
        val started = System.nanoTime()
        var result: ProcessResult
        var tempWork: File? = null
        TerminalDiagnostics.record(context, phase, "started", extra)
        try {
            check(proot.isFile && proot.canExecute()) { "Executable is missing or not executable: ${proot.path}" }
            if (loader != null) check(loader.isFile) { "Bundled PRoot loader is missing." }
            val actualTemp = if (loader == null) {
                null
            } else if (temp != null) {
                temp
            } else {
                tempWork = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "cli-help-").toFile()
                File(tempWork, "tmp")
            }
            if (actualTemp != null) {
                check(actualTemp.mkdirs() || actualTemp.isDirectory) {
                    "Could not create the probe scratch directory: ${actualTemp.path}"
                }
            }
            val processArgs = if (loader == null) {
                listOf(proot.absolutePath) + args
            } else {
                listOf(proot.absolutePath) + args
            }
            val env = buildMap {
                if (loader != null) put("PROOT_LOADER", loader.absolutePath)
                if (loader != null && actualTemp != null) {
                    put("PROOT_TMP_DIR", actualTemp.absolutePath)
                    put("TMPDIR", actualTemp.absolutePath)
                    put("PROOT_NO_SECCOMP", "1")
                }
                put("TRITONTERM_DIAGNOSTICS_FILE", TerminalDiagnostics.file(context).absolutePath)
            }
            result = runCommand(processArgs, env, PROBE_TIMEOUT_SECONDS)
        } catch (failure: Exception) {
            result = ProcessResult(null, false, "", failure.message ?: failure.javaClass.simpleName)
        } finally {
            runCatching { tempWork?.deleteRecursively() }
        }
        val passed = !result.timedOut && result.error == null && result.exitCode == 0 &&
            result.output.contains(successMarker) && alsoRequire.all(result.output::contains)
        val probe = ProotSmokeResult(name, result.exitCode, result.timedOut, result.output, result.error, passed)
        TerminalDiagnostics.record(
            context,
            phase,
            if (passed) "passed" else "failed",
            extra + mapOf(
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000,
                "exit_code" to result.exitCode,
                "timed_out" to result.timedOut,
                "output" to result.output,
                "error" to result.error,
                "required_markers" to (listOf(successMarker) + alsoRequire)
            )
        )
        return probe
    }

    private fun runCommand(command: List<String>, env: Map<String, String>, timeoutSeconds: Long): ProcessResult {
        var process: Process? = null
        val output = StringBuilder()
        var timedOut = false
        var exitCode: Int? = null
        var error: String? = null
        try {
            val started = ProcessBuilder(command).redirectErrorStream(true).apply {
                environment().putAll(env)
            }.start()
            process = started
            val reader = Thread {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < MAX_PROBE_OUTPUT_CHARS) output.append(line).append('\n')
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (started.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                exitCode = started.exitValue()
            } else {
                timedOut = true
                started.destroyForcibly()
                started.waitFor()
            }
            reader.join(2_000)
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
            process?.destroyForcibly()
        }
        return ProcessResult(exitCode, timedOut, synchronized(output) { output.toString() }, error)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private data class ProcessResult(
        val exitCode: Int?,
        val timedOut: Boolean,
        val output: String,
        val error: String?
    )

    private const val PROBE_TIMEOUT_SECONDS = 45L
    private const val MAX_PROBE_OUTPUT_CHARS = 8_000
}
