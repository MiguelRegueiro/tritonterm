package com.tritonterm.android.terminal

import android.content.Context
import android.system.Os
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Downloads and authenticates the Arch Linux ARM rootfs before pr-cli is allowed to extract it. */
internal object ArchLinuxArmBootstrap {
    const val ROOTFS_URL =
        "https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz"
    const val DISTRO_ALIAS = "archlinuxarm"

    private const val SIGNING_FINGERPRINT = "68B3537F39A313B3E574D06777193F152BDBE6A6"
    private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 1024L * 1024
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    fun isInstalled(context: Context): Boolean {
        val root = rootfs(context)
        return ArchRootfsReadiness.isPrepared(root, installingMarker(context))
    }

    /** A completed extraction can be resumed through pacman preparation after an app/process interruption. */
    fun isRootfsExtracted(context: Context): Boolean {
        val root = rootfs(context)
        return ArchRootfsReadiness.isExtracted(root, installingMarker(context))
    }

    private fun installingMarker(context: Context) =
        File(rootfs(context).parentFile, ".${DISTRO_ALIAS}.pr-cli-installing")

    fun rootfs(context: Context) =
        File(context.filesDir, "usr/var/lib/pr/installed-rootfs/$DISTRO_ALIAS")

    fun archive(context: Context): File = File(cacheDir(context), archiveName())

    fun signature(context: Context): File = File(archive(context).absolutePath + ".sig")

    /** Leaves the signature-verified archive in pr-cli's expected cache location. */
    suspend fun prepare(
        context: Context,
        onProgress: (TerminalSetupProgress) -> Unit
    ) {
        if (isInstalled(context)) {
            TerminalDiagnostics.record(context, "rootfs_check", "already_installed", mapOf("rootfs" to rootfs(context).absolutePath))
            return
        }
        val existingRootfs = rootfs(context)
        if (existingRootfs.exists() || runCatching { Os.lstat(existingRootfs.absolutePath) }.isSuccess) {
            if (isRootfsExtracted(context)) {
                TerminalDiagnostics.record(
                    context,
                    "rootfs_check",
                    "extraction_complete_preparation_resumable",
                    mapOf("rootfs" to existingRootfs.absolutePath)
                )
                return
            }
            TerminalDiagnostics.record(
                context,
                "rootfs_check",
                "incomplete_rootfs_preserved",
                mapOf(
                    "rootfs" to existingRootfs.absolutePath,
                    "marker_present" to File(existingRootfs.parentFile, ".${DISTRO_ALIAS}.pr-cli-installing").exists()
                )
            )
            error("An existing Arch rootfs was found but is not marked complete. It was preserved; export Diagnostics before retrying.")
        }

        val prefix = File(context.filesDir, "usr")
        val cache = cacheDir(context)
        check(cache.mkdirs() || cache.isDirectory) { "Could not create rootfs download cache." }
        val archive = archive(context)
        val signature = signature(context)
        TerminalDiagnostics.record(
            context,
            "bootstrap",
            "started",
            mapOf("archive" to archive.absolutePath, "signature" to signature.absolutePath, "rootfs" to rootfs(context).absolutePath)
        )

        // Keep downloads resumable, but only trust complete files after checking the detached sig.
        for (attempt in 0..0) {
            onProgress(TerminalSetupProgress(TerminalSetupStage.DOWNLOADING))
            val downloadStarted = System.nanoTime()
            TerminalDiagnostics.record(context, "archive_download", "started", mapOf("attempt" to attempt + 1))
            try {
                download(ROOTFS_URL + ".sig", signature, MAX_SIGNATURE_BYTES) {}
                download(ROOTFS_URL, archive, MAX_ARCHIVE_BYTES) { progress ->
                    onProgress(TerminalSetupProgress(TerminalSetupStage.DOWNLOADING, progress.downloaded, progress.total))
                }
            } catch (failure: Exception) {
                TerminalDiagnostics.record(
                    context,
                    "archive_download",
                    "failed",
                    mapOf("elapsed_ms" to (System.nanoTime() - downloadStarted) / 1_000_000, "error" to failure.message)
                )
                throw failure
            }
            TerminalDiagnostics.record(
                context,
                "archive_download",
                "completed",
                mapOf("elapsed_ms" to (System.nanoTime() - downloadStarted) / 1_000_000, "archive_bytes" to archive.length())
            )
            val archiveSize = archive.length()
            onProgress(
                TerminalSetupProgress(
                    stage = TerminalSetupStage.VERIFYING,
                    extractionTotal = archiveSize
                )
            )
            val verifyStarted = System.nanoTime()
            TerminalDiagnostics.record(context, "signature_verification", "started", mapOf("archive_bytes" to archiveSize))
            val signatureValid = try {
                verifyDetachedSignature(context, archive, signature) { verifiedBytes ->
                    onProgress(
                        TerminalSetupProgress(
                            stage = TerminalSetupStage.VERIFYING,
                            extractedBytes = verifiedBytes,
                            extractionTotal = archiveSize
                        )
                    )
                }
            } catch (failure: Exception) {
                TerminalDiagnostics.record(
                    context,
                    "signature_verification",
                    "failed",
                    mapOf("elapsed_ms" to (System.nanoTime() - verifyStarted) / 1_000_000, "error" to failure.message)
                )
                throw failure
            }
            TerminalDiagnostics.record(
                context,
                "signature_verification",
                if (signatureValid) "passed" else "rejected",
                mapOf("elapsed_ms" to (System.nanoTime() - verifyStarted) / 1_000_000, "verified_bytes" to archiveSize)
            )
            if (signatureValid) {
                val plugin = File(prefix, "etc/pr/$DISTRO_ALIAS.sh")
                check(plugin.parentFile?.mkdirs() == true || plugin.parentFile?.isDirectory == true) {
                    "Could not create the Arch Linux ARM runtime configuration."
                }
                val expectedPlugin = "DISTRO_NAME=\"Arch Linux ARM\"\n" +
                    "TARBALL_URL_aarch64=\"$ROOTFS_URL\"\n"
                if (plugin.exists()) {
                    check(plugin.readText() == expectedPlugin) {
                        "An existing Arch Linux ARM plugin was preserved because its contents differ from TritonTerm's expected configuration."
                    }
                } else {
                    plugin.writeText(expectedPlugin)
                }
                TerminalDiagnostics.record(
                    context,
                    "plugin_and_cache_setup",
                    "completed",
                    mapOf(
                        "plugin_path" to plugin.absolutePath,
                        "plugin_bytes" to plugin.length(),
                        "expected_archive_path" to archive.absolutePath,
                        "archive_bytes" to archive.length()
                    )
                )
                onProgress(
                    TerminalSetupProgress(
                        stage = TerminalSetupStage.VERIFYING,
                        extractedBytes = archiveSize,
                        extractionTotal = archiveSize
                    )
                )
                return
            }

            TerminalDiagnostics.record(context, "verified_archive_recovery", "invalid_archive_preserved", mapOf("archive_bytes" to archiveSize))
            error("Arch Linux ARM rootfs signature is invalid. The archive and signature were preserved for diagnosis.")
        }
    }

    fun archivePath(context: Context) = archive(context).absolutePath
    fun signaturePath(context: Context) = signature(context).absolutePath

    private fun cacheDir(context: Context) =
        File(context.filesDir, "usr/var/lib/pr/dlcache")

    internal fun archiveName(url: String = ROOTFS_URL): String {
        val basename = url.substringAfterLast('/')
        // pr-cli formats the same SHA-256 prefix with Rust's lowercase `{:x}`; the cache
        // filename must match byte-for-byte or pr-cli downloads an unauthenticated copy itself.
        val key = sha256(url.toByteArray(Charsets.UTF_8))
            .take(16)
            .lowercase(Locale.ROOT)
        return "$key-$basename"
    }

    private suspend fun download(
        address: String,
        destination: File,
        sizeLimit: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val partial = File(destination.absolutePath + ".partial")
        if (destination.isFile) return
        destination.parentFile?.mkdirs()

        var lastError: Exception? = null
        for (attempt in 0..2) {
            coroutineContext.ensureActive()
            var connection: HttpURLConnection? = null
            try {
                var offset = partial.takeIf { it.isFile }?.length() ?: 0L
                if (offset > sizeLimit) {
                    partial.delete()
                    offset = 0
                }

                connection = (URL(address).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TritonTerm/0.1")
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                val response = connection.responseCode
                if (response == HTTP_RANGE_NOT_SATISFIABLE && offset > 0) {
                    // A process may have been stopped after writing the final byte but before
                    // renaming .partial. Promote the candidate and let the signature check
                    // decide whether it is complete and authentic.
                    check(partial.renameTo(destination)) { "Could not finalize the resumed download." }
                    return
                }
                check(response == HttpURLConnection.HTTP_OK || response == HttpURLConnection.HTTP_PARTIAL) {
                    "Rootfs server returned HTTP $response."
                }

                val append = response == HttpURLConnection.HTTP_PARTIAL && offset > 0
                if (offset > 0 && !append) {
                    partial.delete()
                    offset = 0
                }
                if (append) {
                    val rangeStart = connection.getHeaderField("Content-Range")
                        ?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    check(rangeStart == offset) { "Rootfs server returned an invalid resume range." }
                }

                val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
                val total = contentLength?.let { it + offset }
                check(total == null || total <= sizeLimit) { "Rootfs download exceeds the allowed size." }
                var received = offset
                var lastProgressUpdateNanos = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= sizeLimit) { "Rootfs download exceeds the allowed size." }
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgressUpdateNanos >= 100_000_000L || received == total) {
                                onProgress(DownloadProgress(received, total))
                                lastProgressUpdateNanos = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(contentLength == null || received - offset == contentLength) {
                    "Rootfs download ended before all bytes arrived."
                }
                check(partial.renameTo(destination)) { "Could not finalize the rootfs download." }
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                if (attempt < 2) delay(1_000L * (attempt + 1))
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException("Rootfs download failed: ${lastError?.message ?: "unknown error"}", lastError)
    }

    private fun verifyDetachedSignature(
        context: Context,
        archive: File,
        signatureFile: File,
        onProgress: (Long) -> Unit
    ): Boolean = context.assets.open("archlinuxarm-builder.asc").use { keyRing ->
        OpenPgpDetachedVerifier.verifyOrThrow(
            keyRing,
            archive,
            signatureFile,
            SIGNING_FINGERPRINT,
            onProgress
        )
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

internal object ArchRootfsReadiness {
    private const val PROFILE_MARKER = "root/.tritonterm-userland-profile-v1"

    fun isExtracted(root: File, installingMarker: File): Boolean =
        File(root, "etc/os-release").isFile && !installingMarker.exists()

    fun isPrepared(root: File, installingMarker: File): Boolean =
        isExtracted(root, installingMarker) && File(root, PROFILE_MARKER).isFile
}

private data class DownloadProgress(val downloaded: Long, val total: Long?)

/** Small, streaming detached-signature verifier; it never loads the rootfs archive into memory. */
internal object OpenPgpDetachedVerifier {
    fun verify(
        keyRingInput: InputStream,
        signedFile: File,
        signatureFile: File,
        expectedFingerprint: String
    ): Boolean = runCatching {
        verifyOrThrow(keyRingInput, signedFile, signatureFile, expectedFingerprint)
    }.getOrDefault(false)

    internal fun verifyOrThrow(
        keyRingInput: InputStream,
        signedFile: File,
        signatureFile: File,
        expectedFingerprint: String,
        onProgress: (Long) -> Unit = {}
    ): Boolean {
        val verifierProvider = JcaPGPContentVerifierBuilderProvider()
            .setProvider(BouncyCastleProvider())
        val keyRing = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(keyRingInput),
            JcaKeyFingerprintCalculator()
        )
        val trustedKey = keyRing.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .firstOrNull { it.fingerprint.toHex() == expectedFingerprint.uppercase(Locale.ROOT) }
            ?: error("The OpenPGP keyring does not contain the pinned signing fingerprint.")
        check(trustedKey.isMasterKey) { "Pinned signer is not a primary key." }

        val signatureList = FileInputStream(signatureFile).use { raw ->
            PGPObjectFactory(PGPUtil.getDecoderStream(raw), JcaKeyFingerprintCalculator())
                .nextObject() as? PGPSignatureList
                ?: error("The detached signature file is malformed.")
        }
        check(signatureList.size() == 1) { "Expected one detached signature." }
        val detached = signatureList[0]
        check(detached.keyID == trustedKey.keyID) { "Signature has an unexpected signer." }
        detached.init(verifierProvider, trustedKey)
        BufferedInputStream(FileInputStream(signedFile)).use { input ->
            val buffer = ByteArray(1024 * 1024)
            var verifiedBytes = 0L
            var nextProgress = 8L * 1024 * 1024
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                detached.update(buffer, 0, count)
                verifiedBytes += count
                if (verifiedBytes >= nextProgress) {
                    onProgress(verifiedBytes)
                    nextProgress = verifiedBytes + 8L * 1024 * 1024
                }
            }
            onProgress(verifiedBytes)
        }
        return detached.verify()
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02X".format(Locale.ROOT, it) }
