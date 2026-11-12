package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files

class TerminalDiagnosticsExportTest {
    @Test
    fun event_summary_is_human_readable_and_keeps_failure_detail() {
        val summary = TerminalDiagnostics.summarizeDiagnosticEvent(
            phase = "pacman_database_refresh",
            status = "failed",
            detail = "mirror returned 404"
        )

        assertEquals("Pacman database refresh · Failed — mirror returned 404", summary)
    }

    @Test
    fun json_null_error_is_not_rendered_as_the_word_null() {
        val detail = TerminalDiagnostics.diagnosticDetail("null")
        val summary = TerminalDiagnostics.summarizeDiagnosticEvent("controlled_sigsegv_test", "passed", detail)

        assertEquals(null, detail)
        assertEquals("Controlled sigsegv test · Passed", summary)
        assertTrue(!summary.contains("null", ignoreCase = true))
    }

    @Test
    fun provider_export_requires_written_readback_and_reported_sizes_to_agree() {
        val payload = "verified diagnostics".toByteArray(Charsets.UTF_8)
        TerminalDiagnostics.verifyDestination(payload, payload.size.toLong(), payload, payload.size.toLong())

        val mismatch = runCatching {
            TerminalDiagnostics.verifyDestination(payload, payload.size.toLong(), payload, 0L)
        }.exceptionOrNull()
        assertTrue(mismatch?.message.orEmpty().contains("Provider reports 0 bytes"))

        val unavailable = runCatching {
            TerminalDiagnostics.verifyDestination(payload, payload.size.toLong(), payload, null)
        }.exceptionOrNull()
        assertTrue(unavailable?.message.orEmpty().contains("cannot be independently verified"))
    }

    @Test
    fun export_contains_known_android_and_native_events_and_round_trips_nonzero_bytes() {
        val crashRecord = "PROOT_CRASH_RECORDER active output=/data/app/no_backup/crash.log\n" +
            "GUEST_SIGSEGV scope=guest_tracee pid=42 si_code=-6 si_addr=0x0\npc=0x1234 sp=0x5678\n"
        val payload = TerminalDiagnostics.exportPayload("{\"phase\":\"known-test-event\"}\n", crashRecord)
        val target = Files.createTempFile("tritonterm-diagnostics-export", ".jsonl").toFile()

        try {
            val byteCount = TerminalDiagnostics.writeAndVerify(
                payload.toByteArray(Charsets.UTF_8),
                openOutput = { FileOutputStream(target) },
                openInput = { FileInputStream(target) }
            )

            assertTrue(byteCount > 0)
            assertEquals(byteCount, target.length())
            val exported = target.readText(Charsets.UTF_8)
            assertTrue(exported.contains("known-test-event"))
            assertTrue(exported.contains("\"source\":\"proot_crash_recorder\""))
            assertTrue(exported.contains("PROOT_CRASH_RECORDER active"))
            assertTrue(exported.contains("\"record_type\":\"guest_sigsegv\""))
            assertTrue(exported.contains("GUEST_SIGSEGV scope=guest_tracee pid=42"))
            assertTrue("native multiline log must be JSON-string escaped", exported.contains("\\npc=0x1234"))
            assertEquals("one Android JSONL event, one activation, one guest signal", 3, exported.trimEnd().lines().size)
        } finally {
            target.delete()
        }
    }

    @Test
    fun export_rejects_a_destination_that_does_not_round_trip_the_written_bytes() {
        val failure = runCatching {
            TerminalDiagnostics.writeAndVerify(
                "diagnostics\n".toByteArray(Charsets.UTF_8),
                openOutput = { java.io.ByteArrayOutputStream() },
                openInput = { "".byteInputStream() }
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("Export verification failed"))
    }
}
