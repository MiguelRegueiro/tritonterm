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
    val ROOTFS_URL: String get() = "${ArchRootfsRelease.releaseBaseUrl()}/${ArchRootfsRelease.archiveName()}"
    const val DISTRO_ALIAS = "archlinuxarm"

    private const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 1024L * 1024
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    fun isInstalled(context: Context): Boolean {
        val root = rootfs(context)
        return ArchRootfsReadiness.isPrepared(root, installingMarker(context)) &&
            ArchRootfsRelease.isPreparedImage(root) &&
            ArchDeveloperEnvironment.isReady(root)
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

    fun archive(context: Context): File = File(cacheDir(context), archiveName(ROOTFS_URL))

    fun manifest(context: Context): File = File(cacheDir(context), "${ArchRootfsRelease.archiveName()}.manifest")

    fun signature(context: Context): File = File(manifest(context).absolutePath + ".sig")

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
        val manifestFile = manifest(context)
        val signature = signature(context)
        check(ArchRootfsRelease.isConfigured()) {
            "This APK has no pinned Arch development image release. Configure its rootfs version and HTTPS release URL before distribution."
        }
        TerminalDiagnostics.record(
            context,
            "bootstrap",
            "started",
            mapOf(
                "archive" to archive.absolutePath,
                "manifest" to manifestFile.absolutePath,
                "signature" to signature.absolutePath,
                "rootfs" to rootfs(context).absolutePath,
                "pinned_version" to ArchRootfsRelease.pinnedVersion(),
                "release_tag" to ArchRootfsRelease.releaseTag()
            )
        )

        val releaseBase = ArchRootfsRelease.releaseBaseUrl()
        val downloadStarted = System.nanoTime()
        TerminalDiagnostics.record(context, "archive_download", "started", mapOf("release_base" to releaseBase))
        try {
            onProgress(TerminalSetupProgress(TerminalSetupStage.DOWNLOADING))
            download("$releaseBase/${manifestFile.name}", manifestFile, MAX_SIGNATURE_BYTES) {}
            download("$releaseBase/${signature.name}", signature, MAX_SIGNATURE_BYTES) {}
            val releaseManifest = ArchRootfsRelease.parseAndVerify(context, manifestFile, signature)
            check(releaseManifest.archive == archive.name) { "Signed rootfs asset name does not match the APK's pinned image." }
            download("$releaseBase/${releaseManifest.archive}", archive, MAX_ARCHIVE_BYTES) { progress ->
                onProgress(
                    TerminalSetupProgress(
                        stage = TerminalSetupStage.DOWNLOADING,
                        downloaded = progress.downloaded,
                        total = progress.total ?: releaseManifest.size,
                        downloadBytesPerSecond = progress.bytesPerSecond
                    )
                )
            }
            onProgress(TerminalSetupProgress(TerminalSetupStage.VERIFYING, extractionTotal = releaseManifest.size))
            ArchRootfsRelease.verifyArchive(archive, releaseManifest)
            onProgress(TerminalSetupProgress(TerminalSetupStage.VERIFYING, extractedBytes = releaseManifest.size, extractionTotal = releaseManifest.size))
            TerminalDiagnostics.record(
                context,
                "archive_download",
                "authenticated",
                mapOf(
                    "elapsed_ms" to (System.nanoTime() - downloadStarted) / 1_000_000,
                    "archive_bytes" to archive.length(),
                    "sha256" to releaseManifest.sha256,
                    "base_archive_sha256" to releaseManifest.baseArchiveSha256,
                    "rootfs_version" to releaseManifest.version
                )
            )
        } catch (failure: Exception) {
            TerminalDiagnostics.record(
                context,
                "archive_download",
                "failed",
                mapOf("elapsed_ms" to (System.nanoTime() - downloadStarted) / 1_000_000, "error" to failure.message)
            )
            throw failure
        }

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
            mapOf("plugin_path" to plugin.absolutePath, "plugin_bytes" to plugin.length(), "archive_path" to archive.absolutePath, "archive_bytes" to archive.length())
        )
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
                val speedStartNanos = System.nanoTime()
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
                                val seconds = ((now - speedStartNanos).coerceAtLeast(1L)) / 1_000_000_000.0
                                onProgress(DownloadProgress(received, total, ((received - offset) / seconds).toLong()))
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

/** Readiness is committed only after the complete default developer toolchain passes smoke checks. */
internal object ArchDeveloperEnvironment {
    const val VERSION = "3"
    const val SOURCE_SHA256 = "0822bd5a9f0cf19d2ba0546b88b0432d4d3d9917db62c57b74044ccadba06e49"
    const val PARU_VERSION = "2.2.0"
    const val FAKEROOT_VERSION = "2.1.4"
    const val READY_MARKER = "root/.tritonterm-developer-environment-ready"
    const val FAKEROOT_PREFIX = "opt/tritonterm/fakeroot-tcp"

    fun isReady(root: File): Boolean {
        val marker = File(root, READY_MARKER)
        val prefix = File(root, "$FAKEROOT_PREFIX/$FAKEROOT_VERSION")
        val passwd = File(root, "etc/passwd")
        val selectedUser = ArchLoginName.fromRootfs(root) ?: return false
        val devHome = runCatching {
            passwd.useLines { lines ->
                lines.mapNotNull { line ->
                    val fields = line.split(':')
                    fields.takeIf { it.size >= 7 && it[0] == selectedUser && it[2] == "1000" && it[3] == "1000" && it[6] == "/usr/bin/fish" }?.get(5)
                }.firstOrNull()
            }
        }.getOrNull()
        val shim = devHome?.let { File(root, "${it.trimStart('/')}/.local/bin/fakeroot") }
        val sudoers = File(root, "etc/sudoers.d/10-tritonterm-pacman")
        val home = devHome?.trimStart('/') ?: return false
        val homeFile = File(root, home)
        val makepkgConfig = File(homeFile, ".config/pacman/makepkg.conf")
        val paruConfig = File(homeFile, ".config/paru/paru.conf")
        return marker.isFile && runCatching { marker.readText().trim() == VERSION }.getOrDefault(false) &&
            File(prefix, "bin/fakeroot").canExecute() && File(prefix, "bin/faked").canExecute() &&
            File(prefix, "lib/libfakeroot/libfakeroot-0.so").isFile &&
            File(root, "$FAKEROOT_PREFIX/current").let { it.isSymbolicLinkTo(FAKEROOT_VERSION) } &&
            shim?.let { it.isSymbolicLinkTo("/opt/tritonterm/fakeroot-tcp/current/bin/fakeroot") } == true &&
            File(root, "etc/profile.d/tritonterm-devtools.sh").let { profile ->
                profile.isFile && runCatching { profile.readText().contains("export SYSTEMD_OFFLINE=1") }.getOrDefault(false)
            } &&
            File(homeFile, ".config/fish/conf.d/20-tritonterm-path.fish").let { fishEnv ->
                fishEnv.isFile && runCatching { fishEnv.readText().contains("set -gx SYSTEMD_OFFLINE 1") }.getOrDefault(false)
            } &&
            listOf("makepkg", "sudo", "rustup", "rustc", "cargo", "fish", "starship", "paru", "fakeroot")
                .all { File(root, "usr/bin/$it").canExecute() } &&
            File(homeFile, ".rustup/toolchains/stable-aarch64-unknown-linux-gnu/bin/rustc").canExecute() &&
            File(homeFile, ".config/starship.toml").isFile &&
            File(homeFile, ".config/fish/conf.d/90-tritonterm-starship.fish").isFile &&
            makepkgConfig.isFile && runCatching { makepkgConfig.readText().contains("PACMAN_AUTH=(/usr/bin/sudo -n)") }.getOrDefault(false) &&
            paruConfig.isFile && runCatching { paruConfig.readText().contains("Sudo = /usr/bin/sudo") }.getOrDefault(false) &&
            sudoers.isFile && runCatching { sudoers.readText().lines().contains("%wheel ALL=(root) NOPASSWD: /usr/bin/pacman") }.getOrDefault(false) &&
            File(root, "etc/group").let { groups ->
                groups.isFile && runCatching {
                    groups.readLines().any { line ->
                        val fields = line.split(':')
                        fields.size >= 4 && fields[0] == "wheel" && selectedUser in fields[3].split(',')
                    }
                }.getOrDefault(false)
            }
    }

    private fun File.isSymbolicLinkTo(target: String): Boolean =
        runCatching { java.nio.file.Files.readSymbolicLink(toPath()).toString() == target }.getOrDefault(false)
}

private data class DownloadProgress(val downloaded: Long, val total: Long?, val bytesPerSecond: Long)

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
