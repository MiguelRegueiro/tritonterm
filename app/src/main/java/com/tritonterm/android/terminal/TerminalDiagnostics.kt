package com.tritonterm.android.terminal

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class DiagnosticsExportResult(
    val destinationUri: String,
    val expectedBytes: Long,
    val bytesWritten: Long,
    val uriReadbackBytes: Long,
    val providerReportedBytes: Long?,
    val providerMetadataError: String?,
    val providerMetadataAttempts: Int,
    val providerMetadataWaitMs: Long
)

internal data class DiagnosticEvent(
    val id: String,
    val time: String,
    val source: String,
    val phase: String,
    val status: String,
    val summary: String,
    val rawJson: String,
    val isProblem: Boolean
)

internal data class GuestCrashRecord(val id: Int, val summary: String, val raw: String)

internal object TerminalDiagnostics {
    private const val MAX_OUTPUT_CHARS = 16_000
    private const val MAX_OUTPUT_TOTAL_CHARS = 300_000L
    private val logTimestampFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT)
    private const val MAX_RECENT_EVENTS = 120
    private const val MAX_RECENT_EVENT_CHARS = 80_000
    private const val MAX_NATIVE_LOG_BYTES = 512 * 1024
    private val outputCharacters = AtomicLong()
    private val outputWriter = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "terminal-diagnostics-output").apply { isDaemon = true }
    }

    fun file(context: Context) = File(context.noBackupFilesDir, "tritonterm-diagnostics.jsonl")

    fun crashFile(context: Context) = File(context.noBackupFilesDir, "tritonterm-proot-crash.log")

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

    fun clear(context: Context) {
        check(flushProcessOutput()) { "Timed out while flushing terminal output before clearing logs." }
        synchronized(this) {
            listOf(file(context), crashFile(context)).forEach { logFile ->
                FileOutputStream(logFile, false).use { it.fd.sync() }
            }
            outputCharacters.set(0L)
            record(context, "diagnostics_logs", "cleared")
        }
    }

    fun appendProcessOutput(context: Context, stream: String, text: String) {
        val bounded = text.takeLast(MAX_OUTPUT_CHARS)
        if (bounded.isNotBlank() && outputCharacters.addAndGet(bounded.length.toLong()) <= MAX_OUTPUT_TOTAL_CHARS) {
            outputWriter.execute { record(context, "process_output", "captured", mapOf("stream" to stream, "text" to bounded)) }
        }
    }

    fun flushProcessOutput(): Boolean =
        runCatching { outputWriter.submit {}.get(10, TimeUnit.SECONDS) }.isSuccess

    fun recentEventsText(context: Context): String {
        check(flushProcessOutput()) { "Timed out while flushing terminal output into the diagnostics log." }
        val diagnosticsFile = file(context)
        check(diagnosticsFile.isFile) { "Internal diagnostics file is missing: ${diagnosticsFile.absolutePath}" }
        val selected = ArrayList<String>()
        var chars = 0
        for (line in diagnosticsFile.readLines(Charsets.UTF_8).asReversed()) {
            if (line.isBlank()) continue
            if (selected.size >= MAX_RECENT_EVENTS || chars + line.length > MAX_RECENT_EVENT_CHARS) break
            selected += line
            chars += line.length + 1
        }
        check(selected.isNotEmpty()) { "Internal diagnostics file contains no events." }
        return selected.joinToString("\n")
    }

    fun recentEvents(context: Context): List<DiagnosticEvent> = recentEventsText(context)
        .lineSequence()
        .mapIndexedNotNull { index, line ->
            runCatching {
                val json = JSONObject(line)
                val phase = json.optString("phase", "diagnostic event")
                val status = json.optString("status", "unknown")
                val details = json.optJSONObject("details") ?: JSONObject()
                val error = diagnosticDetail(details.opt("error"))
                val output = when (phase) {
                    "process_output" -> details.optString("text").replace(Regex("\\s+"), " ").trim().take(180)
                    else -> listOf("operation", "work", "command").firstNotNullOfOrNull { key ->
                        details.optString(key).takeIf(String::isNotBlank)
                    }
                }
                val summary = summarizeDiagnosticEvent(phase, status, error ?: output)
                val timestamp = json.optString("time")
                DiagnosticEvent(
                    id = "$timestamp:$index",
                    time = runCatching {
                        Instant.parse(timestamp).atZone(ZoneId.systemDefault()).format(logTimestampFormatter)
                    }.getOrElse { timestamp.substringAfter('T', timestamp).take(8) },
                    source = json.optString("source", "unknown"),
                    phase = phase,
                    status = status,
                    summary = summary,
                    rawJson = line,
                    isProblem = status.contains("fail", ignoreCase = true) ||
                        status.contains("error", ignoreCase = true) || status == "not_confirmed" || error != null
                )
            }.getOrNull()
        }.toList()

    internal fun summarizeDiagnosticEvent(phase: String, status: String, detail: String? = null): String {
        fun label(value: String) = value.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val safeDetail = detail?.takeUnless { it.equals("null", ignoreCase = true) }
        return buildString {
            append(label(phase))
            append(" · ")
            append(label(status))
            if (!safeDetail.isNullOrBlank()) append(" — ").append(safeDetail.take(180))
        }
    }

    internal fun diagnosticDetail(value: Any?): String? = value
        ?.takeIf { it != JSONObject.NULL }
        ?.toString()
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

    private data class NativeRecord(val type: String, val text: String)

    private fun nativeRecords(raw: String): List<NativeRecord> {
        val result = mutableListOf<NativeRecord>()
        var type: String? = null
        val content = StringBuilder()
        fun flush() {
            val currentType = type ?: return
            val value = content.toString().trimEnd()
            if (value.isNotBlank()) result += NativeRecord(currentType, value)
            content.clear()
        }
        raw.lineSequence().forEach { line ->
            val nextType = when {
                line.startsWith("PROOT_CRASH_RECORDER active") -> "recorder_activation"
                line.startsWith("GUEST_SIGSEGV ") || line.startsWith("SIGSEGV ") -> "guest_sigsegv"
                else -> null
            }
            if (nextType != null) {
                flush()
                type = nextType
            }
            if (type != null) content.append(line).append('\n')
        }
        flush()
        return result
    }

    private fun nativeLogText(context: Context): String {
        val crashLog = crashFile(context)
        if (!crashLog.isFile) return ""
        val bytes = crashLog.readBytes()
        return bytes.takeLast(MAX_NATIVE_LOG_BYTES).toByteArray().toString(Charsets.UTF_8)
    }

    fun recorderStatusText(context: Context): String {
        val activation = nativeRecords(nativeLogText(context)).lastOrNull { it.type == "recorder_activation" }
        val latestStatus = runCatching {
            recentEventsText(context).lineSequence().mapNotNull { line ->
                val event = JSONObject(line)
                if (event.optString("phase") == "proot_crash_recorder") event.optString("status") else null
            }.firstOrNull()
        }.getOrNull()
        return when {
            latestStatus == "active_confirmed" && activation != null ->
                "Recorder active · ${activation.text.lines().firstOrNull().orEmpty()}"
            latestStatus == "not_confirmed" -> "Recorder not confirmed · check the recent events for the host file status."
            activation != null -> "Native recorder activation exists · latest session confirmation is pending."
            else -> "Recorder has not reported an activation record."
        }
    }

    fun guestCrashRecordsText(context: Context): String =
        guestCrashRecordsFrom(nativeLogText(context))

    fun guestCrashRecords(context: Context): List<GuestCrashRecord> =
        nativeRecords(nativeLogText(context)).filter { it.type == "guest_sigsegv" }
            .mapIndexed { index, record ->
                GuestCrashRecord(index, record.text.lineSequence().firstOrNull().orEmpty(), record.text)
            }

    internal fun guestCrashRecordsFrom(raw: String): String =
        nativeRecords(raw).filter { it.type == "guest_sigsegv" }
            .joinToString("\n\n") { it.text }

    fun shareableLogText(context: Context): String =
        exportPayload(recentEventsText(context), nativeRecords(nativeLogText(context)))

    fun confirmCrashRecorder(context: Context, previousBytes: Long) {
        Thread({
            val crashLog = crashFile(context)
            repeat(20) {
                val current = runCatching {
                    crashLog.takeIf(File::isFile)?.let { file ->
                        val contents = file.readBytes()
                        contents.copyOfRange(previousBytes.coerceAtMost(contents.size.toLong()).toInt(), contents.size)
                            .toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                if (current?.contains("PROOT_CRASH_RECORDER active") == true) {
                    record(
                        context,
                        "proot_crash_recorder",
                        "active_confirmed",
                        mapOf("output" to crashLog.absolutePath, "bytes" to crashLog.length())
                    )
                    return@Thread
                }
                Thread.sleep(250)
            }
            record(
                context,
                "proot_crash_recorder",
                "not_confirmed",
                mapOf(
                    "output" to crashLog.absolutePath,
                    "previous_bytes" to previousBytes,
                    "host_file_exists" to crashLog.isFile,
                    "host_file_bytes" to crashLog.takeIf(File::isFile)?.length(),
                    "parent_exists" to crashLog.parentFile?.isDirectory,
                    "parent_writable" to crashLog.parentFile?.canWrite()
                )
            )
        }, "proot-crash-recorder-probe").apply { isDaemon = true }.start()
    }

    fun export(context: Context, uri: Uri): DiagnosticsExportResult {
        var stage = "snapshot"
        var expectedBytes = 0
        var bytesWritten = 0L
        var bytesReadBack = 0L
        var providerReportedBytes: Long? = null
        var providerMetadataError: String? = null
        var providerMetadataAttempts = 0
        var providerMetadataWaitMs = 0L
        val uriText = uri.toString()
        try {
            val events = recentEventsText(context)
            val native = nativeRecords(nativeLogText(context))
            val payload = exportPayload(events, native)
            val bytes = payload.toByteArray(Charsets.UTF_8)
            expectedBytes = bytes.size
            check(expectedBytes > 0) { "There are no diagnostic bytes to export." }
            record(
                context,
                "diagnostics_export",
                "writing",
                mapOf("destination_uri" to uriText, "expected_payload_bytes" to expectedBytes, "failure_stage" to stage)
            )

            stage = "open_destination_descriptor"
            val descriptor = context.contentResolver.openFileDescriptor(uri, "wt")
                ?: error("Android returned no writable file descriptor for the selected destination.")
            val descriptorStat = runCatching { Os.fstat(descriptor.fileDescriptor) }
            val descriptorInfo = descriptorStat.fold(
                onSuccess = { stat ->
                    mapOf(
                        "fd_mode" to stat.st_mode,
                        "fd_size_before_write" to stat.st_size,
                        "fd_is_regular_file" to OsConstants.S_ISREG(stat.st_mode)
                    )
                },
                onFailure = { mapOf("fd_stat_error" to (it.message ?: it.javaClass.simpleName)) }
            )
            var destinationSynced = false

            stage = "write_flush_close"
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                val counted = object : FilterOutputStream(output) {
                    override fun write(value: Int) {
                        out.write(value)
                        bytesWritten++
                    }
                    override fun write(buffer: ByteArray, offset: Int, length: Int) {
                        out.write(buffer, offset, length)
                        bytesWritten += length
                    }
                    override fun flush() = out.flush()
                }
                counted.write(bytes)
                counted.flush()
                stage = "sync_destination"
                if (descriptorStat.getOrNull()?.let { OsConstants.S_ISREG(it.st_mode) } == true) {
                    Os.fsync(descriptor.fileDescriptor)
                    destinationSynced = true
                }
            }
            record(
                context,
                "diagnostics_export",
                "descriptor_closed",
                mapOf(
                    "destination_uri" to uriText,
                    "expected_payload_bytes" to expectedBytes,
                    "bytes_written" to bytesWritten,
                    "destination_fsynced" to destinationSynced,
                    "failure_stage" to "write_flush_close"
                ) + descriptorInfo
            )

            stage = "await_provider_metadata_update"
            val metadataCheck = awaitDestinationMetadata(context, uri, expectedBytes.toLong())
            providerMetadataAttempts = metadataCheck.attempts
            providerMetadataWaitMs = metadataCheck.waitMs
            val metadata = metadataCheck.metadata
            providerReportedBytes = metadata.size
            providerMetadataError = metadata.error
            record(
                context,
                "diagnostics_export",
                if (metadata.size == expectedBytes.toLong()) "provider_metadata_updated" else "provider_metadata_still_stale",
                mapOf(
                    "destination_uri" to uriText,
                    "expected_payload_bytes" to expectedBytes,
                    "bytes_written" to bytesWritten,
                    "provider_reported_bytes" to providerReportedBytes,
                    "provider_display_name" to metadata.displayName,
                    "provider_metadata_error" to metadata.error,
                    "provider_metadata_attempts" to metadataCheck.attempts,
                    "provider_metadata_wait_ms" to metadataCheck.waitMs,
                    "failure_stage" to stage
                )
            )

            stage = "read_destination_uri_back"
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Android wrote the destination but returned no input stream for verification.")
            val actualBytes = input.use { source ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    bytesReadBack += count
                }
                output.toByteArray()
            }
            stage = "verify_provider_document"
            verifyDestination(bytes, bytesWritten, actualBytes, metadata.size)
            record(
                context,
                "diagnostics_export",
                "destination_verified",
                mapOf(
                    "destination_uri" to uriText,
                    "display_name" to metadata.displayName,
                    "expected_payload_bytes" to expectedBytes,
                    "bytes_written" to bytesWritten,
                    "bytes_read_back" to bytesReadBack,
                    "provider_reported_bytes" to metadata.size,
                    "provider_metadata_error" to metadata.error,
                    "failure_stage" to "complete"
                )
            )
            return DiagnosticsExportResult(
                uriText,
                expectedBytes.toLong(),
                bytesWritten,
                bytesReadBack,
                metadata.size,
                metadata.error,
                metadataCheck.attempts,
                metadataCheck.waitMs
            )
        } catch (failure: Exception) {
            record(
                context,
                "diagnostics_export",
                "failed",
                mapOf(
                    "destination_uri" to uriText,
                    "failure_stage" to stage,
                    "expected_payload_bytes" to expectedBytes,
                    "bytes_written" to bytesWritten,
                    "bytes_read_back" to bytesReadBack,
                    "provider_reported_bytes" to providerReportedBytes,
                    "provider_metadata_error" to providerMetadataError,
                    "provider_metadata_attempts" to providerMetadataAttempts,
                    "provider_metadata_wait_ms" to providerMetadataWaitMs,
                    "exception_type" to failure.javaClass.name,
                    "error" to (failure.message ?: failure.javaClass.simpleName)
                )
            )
            throw IllegalStateException(
                "Export failed during $stage for $uriText: expected $expectedBytes bytes, wrote $bytesWritten, " +
                    "read back $bytesReadBack, provider reports ${providerReportedBytes ?: "unknown"}; " +
                    "${failure.javaClass.simpleName}: ${failure.message ?: "no exception detail"}",
                failure
            )
        }
    }

    fun recordExportDestination(context: Context, uri: Uri?) {
        record(
            context,
            "diagnostics_export",
            if (uri == null) "destination_cancelled" else "destination_selected",
            mapOf("destination_uri" to uri?.toString(), "failure_stage" to "destination_selection")
        )
    }

    fun createShareableDiagnosticsFile(context: Context): File {
        val payload = shareableLogText(context)
        val directory = File(context.cacheDir, "diagnostics").apply { check(isDirectory || mkdirs()) }
        val output = File(directory, "tritonterm-diagnostics.jsonl")
        FileOutputStream(output).use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(output.isFile && output.length() > 0L) { "Shareable diagnostics file was not written." }
        return output
    }

    private data class DestinationMetadata(val displayName: String?, val size: Long?, val error: String? = null)
    private data class DestinationMetadataCheck(
        val metadata: DestinationMetadata,
        val attempts: Int,
        val waitMs: Long
    )

    private fun awaitDestinationMetadata(
        context: Context,
        uri: Uri,
        expectedBytes: Long,
        timeoutMs: Long = 3_000L,
        pollIntervalMs: Long = 100L
    ): DestinationMetadataCheck {
        val startedAt = SystemClock.elapsedRealtime()
        var attempts = 0
        var metadata = DestinationMetadata(null, null, "Provider metadata has not been queried.")
        do {
            attempts++
            metadata = queryDestinationMetadata(context, uri)
            if (metadata.size == expectedBytes) break
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= timeoutMs) break
            SystemClock.sleep(minOf(pollIntervalMs, timeoutMs - elapsed))
        } while (true)
        return DestinationMetadataCheck(metadata, attempts, SystemClock.elapsedRealtime() - startedAt)
    }

    private fun queryDestinationMetadata(context: Context, uri: Uri): DestinationMetadata {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        val name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString)
                        val size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getLong)
                            ?.takeIf { it >= 0L }
                        return DestinationMetadata(name, size)
                    }
                }
            DestinationMetadata(null, null, "Provider returned no metadata row.")
        } catch (failure: Exception) {
            DestinationMetadata(null, null, failure.message ?: failure.javaClass.simpleName)
            }
    }

    internal fun writeAndVerify(
        expectedBytes: ByteArray,
        openOutput: () -> OutputStream,
        openInput: () -> InputStream
    ): Long {
        check(expectedBytes.isNotEmpty()) { "There are no diagnostic bytes to export." }
        openOutput().use { output ->
            output.write(expectedBytes)
            output.flush()
        }
        val actualBytes = openInput().use { it.readBytes() }
        check(actualBytes.contentEquals(expectedBytes)) {
            "Export verification failed: wrote ${expectedBytes.size} bytes but read back ${actualBytes.size}."
        }
        check(actualBytes.isNotEmpty()) { "Export verification found a zero-byte document." }
        return actualBytes.size.toLong()
    }

    internal fun verifyDestination(
        expectedBytes: ByteArray,
        writtenBytes: Long,
        readBack: ByteArray,
        providerReportedBytes: Long?
    ) {
        check(writtenBytes == expectedBytes.size.toLong()) {
            "Destination write count differs: expected ${expectedBytes.size} bytes, wrote $writtenBytes."
        }
        check(readBack.isNotEmpty()) {
            "Destination URI read-back is 0 bytes after writing $writtenBytes bytes (provider size=$providerReportedBytes)."
        }
        check(readBack.contentEquals(expectedBytes)) {
            "Destination URI read-back differs: expected ${expectedBytes.size} bytes, read ${readBack.size} bytes (provider size=$providerReportedBytes)."
        }
        check(providerReportedBytes != null) {
            "Destination URI read-back matched, but the provider did not report a document size; export cannot be independently verified."
        }
        check(providerReportedBytes == readBack.size.toLong()) {
            "Provider reports $providerReportedBytes bytes but URI read-back contains ${readBack.size} bytes."
        }
    }

    internal fun exportPayload(logText: String, nativeCrashLog: String): String = exportPayload(
        logText,
        nativeRecords(nativeCrashLog)
    )

    private fun exportPayload(logText: String, native: List<NativeRecord>): String = buildString {
        append(logText)
        if (isNotEmpty() && last() != '\n') append('\n')
        native.forEach { record ->
            append("{\"source\":\"proot_crash_recorder\",\"record_type\":${jsonString(record.type)},\"record\":${jsonString(record.text)}}")
            append('\n')
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
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
        runPrCliHelp(context),
        runGuestCrashLogPath(context)
    )

    private fun runGuestCrashLogPath(context: Context): ProotSmokeResult {
        val cli = File(context.applicationInfo.nativeLibraryDir, "libpr-cli.so")
        val prefix = File(context.filesDir, "usr")
        val guestPath = TerminalDiagnostics.crashFile(context).absolutePath
        val command = listOf(
            cli.absolutePath,
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
            "/bin/sh", "-lc",
            "if [ -r ${shellQuote(guestPath)} ]; then " +
                "printf 'TRITONTERM_GUEST_CRASH_LOG_READABLE bytes='; wc -c < ${shellQuote(guestPath)}; " +
                "else printf 'TRITONTERM_GUEST_CRASH_LOG_UNREADABLE path=%s\\n' ${shellQuote(guestPath)}; exit 45; fi"
        )
        val env = mapOf(
            "APP_PREFIX" to prefix.absolutePath,
            "APP_HOME" to File(context.filesDir, "home").absolutePath,
            "APP_PACKAGE" to context.packageName,
            "HOME" to File(context.filesDir, "home").absolutePath,
            "TMPDIR" to ProotTempDirectory.forApp(context).absolutePath,
            "PROOT_TMP_DIR" to ProotTempDirectory.forApp(context).absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_CRASH_DIAGNOSTICS" to "1",
            "PROOT_CRASH_DIAGNOSTICS_FILE" to guestPath,
            "TRITONTERM_DIAGNOSTICS_FILE" to TerminalDiagnostics.file(context).absolutePath,
            "PATH" to "${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "LANG" to ArchGuestEnvironment.LANG,
            "TZ" to ArchGuestEnvironment.timezoneId()
        )
        val extra = mapOf(
            "guest_path" to guestPath,
            "host_file_exists" to File(guestPath).isFile,
            "host_file_bytes" to File(guestPath).takeIf(File::isFile)?.length(),
            "app_directory_bind_expected" to true
        )
        val started = System.nanoTime()
        TerminalDiagnostics.record(context, "proot_crash_log_guest_path", "started", extra)
        val result = runCatching { runCommand(command, env, PROBE_TIMEOUT_SECONDS) }
            .getOrElse { ProcessResult(null, false, "", it.message ?: it.javaClass.simpleName) }
        val passed = !result.timedOut && result.error == null && result.exitCode == 0 &&
            result.output.contains("TRITONTERM_GUEST_CRASH_LOG_READABLE")
        TerminalDiagnostics.record(
            context,
            "proot_crash_log_guest_path",
            if (passed) "readable" else "failed",
            extra + mapOf(
                "exit_code" to result.exitCode,
                "timed_out" to result.timedOut,
                "elapsed_ms" to (System.nanoTime() - started) / 1_000_000,
                "output" to result.output,
                "error" to result.error
            )
        )
        return ProotSmokeResult("guest_crash_log_path", result.exitCode, result.timedOut, result.output, result.error, passed)
    }

    fun runControlledGuestSegv(context: Context): ProotSmokeResult {
        val crashLog = TerminalDiagnostics.crashFile(context)
        val previousBytes = crashLog.takeIf(File::isFile)?.length() ?: 0L
        val prefix = File(context.filesDir, "usr")
        val cli = File(context.applicationInfo.nativeLibraryDir, "libpr-cli.so")
        val crashScript = """
            ulimit -c 0
            test_dir=${'$'}(mktemp -d /tmp/tritonterm-sigsegv.XXXXXX) || exit 2
            trap 'rm -rf "${'$'}test_dir"' EXIT
            cat > "${'$'}test_dir/crash.c" <<'TRITONTERM_C'
            #include <stdint.h>
            int main(void) {
                volatile uint32_t *fault = (volatile uint32_t *)(uintptr_t)0;
                *fault = 1;
                return 0;
            }
            TRITONTERM_C
            gcc -O0 -fno-delete-null-pointer-checks "${'$'}test_dir/crash.c" -o "${'$'}test_dir/guest-crash" || exit 3
            printf 'TRITONTERM_CONTROLLED_GUEST_SIGSEGV executable=%s\n' "${'$'}test_dir/guest-crash"
            "${'$'}test_dir/guest-crash"
            result=${'$'}?
            printf 'TRITONTERM_CONTROLLED_GUEST_SIGSEGV_EXIT=%s\n' "${'$'}result"
        """.trimIndent()
        val command = listOf(
            cli.absolutePath,
            "login", ArchLinuxArmBootstrap.DISTRO_ALIAS, "--user", "root",
            "/bin/bash", "-c", crashScript
        )
        val env = mapOf(
            "APP_PREFIX" to prefix.absolutePath,
            "APP_HOME" to File(context.filesDir, "home").absolutePath,
            "APP_PACKAGE" to context.packageName,
            "HOME" to File(context.filesDir, "home").absolutePath,
            "TMPDIR" to ProotTempDirectory.forApp(context).absolutePath,
            "PROOT_TMP_DIR" to ProotTempDirectory.forApp(context).absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_CRASH_DIAGNOSTICS" to "1",
            "PROOT_CRASH_DIAGNOSTICS_FILE" to crashLog.absolutePath,
            "TRITONTERM_DIAGNOSTICS_FILE" to TerminalDiagnostics.file(context).absolutePath,
            "PATH" to "${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin",
            "TERM" to "xterm-256color",
            "LANG" to ArchGuestEnvironment.LANG,
            "TZ" to ArchGuestEnvironment.timezoneId()
        )
        TerminalDiagnostics.record(
            context,
            "proot_controlled_sigsegv_test",
            "started",
            mapOf("guest_command" to command.takeLast(3), "crash_log_offset" to previousBytes)
        )
        val processResult = runCatching { runCommand(command, env, PROBE_TIMEOUT_SECONDS) }
            .getOrElse { ProcessResult(null, false, "", it.message ?: it.javaClass.simpleName) }

        val nativeLog = crashLog.takeIf(File::isFile)?.readBytes() ?: byteArrayOf()
        val suffix = nativeLog.copyOfRange(previousBytes.coerceAtMost(nativeLog.size.toLong()).toInt(), nativeLog.size)
            .toString(Charsets.UTF_8)
        val record = TerminalDiagnostics.guestCrashRecordsFrom(suffix)
            .takeUnless { it == "No guest SIGSEGV records captured." }
        val header = record?.lineSequence()?.firstOrNull().orEmpty()
        val hasSignalContext = record != null &&
            listOf("scope=guest_tracee", "pid=", "exe=", "si_code=", "si_addr=", "siginfo_status=0", "regs_status=0")
                .all(header::contains) &&
            !header.contains("exe=(unknown)") &&
            header.contains("si_code=1") &&
            Regex("\\bexe=[^ ]*guest-crash\\b").containsMatchIn(header) &&
            Regex("\\bpc=0x[0-9a-fA-F]+\\b").containsMatchIn(record) &&
            Regex("\\bsp=0x[0-9a-fA-F]+\\b").containsMatchIn(record)
        val passed = !processResult.timedOut && hasSignalContext
        TerminalDiagnostics.record(
            context,
            "proot_controlled_sigsegv_test",
            if (passed) "guest_sigsegv_captured" else "guest_sigsegv_not_captured",
            mapOf(
                "exit_code" to processResult.exitCode,
                "timed_out" to processResult.timedOut,
                "output" to processResult.output,
                "error" to processResult.error,
                "guest_sigsegv_captured" to (record != null),
                "signal_context_complete" to hasSignalContext,
                "crash_log_path" to crashLog.absolutePath,
                "crash_log_bytes" to crashLog.takeIf(File::isFile)?.length()
            )
        )
        val summary = when {
            passed -> "PASS · temporary guest C program caused SEGV_MAPERR; executable, PID, address and registers were captured."
            processResult.timedOut -> "FAIL · controlled guest SIGSEGV test timed out."
            record == null -> "FAIL · no new guest SIGSEGV record appeared; see the event output and recorder status."
            else -> "FAIL · a guest SIGSEGV was recorded but expected signal/register fields are missing."
        }
        return ProotSmokeResult(
            "controlled_guest_sigsegv",
            processResult.exitCode,
            processResult.timedOut,
            "$summary\n${record.orEmpty()}\n${processResult.output}",
            processResult.error,
            passed
        )
    }

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
            env["PROOT_TMP_DIR"]?.let { ProotTempDirectory.ensure(File(it)) }
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
