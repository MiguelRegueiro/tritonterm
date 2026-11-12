package com.tritonterm.android.terminal

import android.content.Context
import com.tritonterm.android.BuildConfig
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Base64

/** Authenticates a release manifest before trusting its asset name, size, or archive checksum. */
internal object ArchRootfsRelease {
    const val MANIFEST_FORMAT = "tritonterm-arch-rootfs-v1"
    const val ARCHIVE_PREFIX = "tritonterm-archlinuxarm-aarch64-"

    data class Manifest(
        val version: String,
        val archive: String,
        val size: Long,
        val sha256: String,
        val baseArchiveSha256: String
    )

    fun pinnedVersion(): String = BuildConfig.ARCH_ROOTFS_VERSION
    fun releaseTag(): String = BuildConfig.ARCH_ROOTFS_RELEASE_TAG
    fun releaseBaseUrl(): String = BuildConfig.ARCH_ROOTFS_RELEASE_BASE_URL.trimEnd('/')
    fun isConfigured(): Boolean = pinnedVersion() != "unconfigured" && releaseTag() != "unconfigured" &&
        releaseBaseUrl().startsWith("https://") && releaseBaseUrl().endsWith("/${releaseTag()}")
    fun archiveName(version: String = pinnedVersion()) = "$ARCHIVE_PREFIX$version.tar.gz"

    fun isPreparedImage(root: File): Boolean {
        val marker = File(root, "etc/tritonterm/rootfs-release")
        return marker.isFile && !java.nio.file.Files.isSymbolicLink(marker.toPath()) &&
            runCatching { marker.readText().trim() == pinnedVersion() }.getOrDefault(false) && pinnedVersion() != "unconfigured"
    }

    fun parseAndVerify(context: Context, manifestFile: File, signatureFile: File): Manifest {
        val manifestBytes = manifestFile.readBytes()
        val signature = signatureFile.readBytes()
        require(signature.size == 64) { "Rootfs manifest signature must be 64 bytes." }
        val publicKeyEncoded = decodePublicKeyPem(
            context.assets.open("tritonterm-rootfs-release-key.pem").bufferedReader().use { it.readText() }
        )
        require(verifySignature(manifestBytes, signature, publicKeyEncoded)) {
            "Rootfs release manifest signature is invalid."
        }
        return parseManifest(manifestBytes.toString(Charsets.UTF_8), pinnedVersion())
    }

    internal fun decodePublicKeyPem(pemText: String): ByteArray {
        val lines = pemText.lineSequence().filter(String::isNotBlank).toList()
        require(lines.firstOrNull() == "-----BEGIN PUBLIC KEY-----" && lines.lastOrNull() == "-----END PUBLIC KEY-----") {
            "Embedded rootfs release key is not a SubjectPublicKeyInfo PEM."
        }
        val body = lines.subList(1, lines.lastIndex).joinToString("")
        val der = Base64.getDecoder().decode(body)
        val key = PublicKeyFactory.createKey(SubjectPublicKeyInfo.getInstance(der))
        require(key is Ed25519PublicKeyParameters) { "Embedded rootfs release key is not Ed25519." }
        return key.encoded
    }

    internal fun verifySignature(manifestBytes: ByteArray, signature: ByteArray, publicKeyEncoded: ByteArray): Boolean {
        if (signature.size != 64) return false
        val publicKey = runCatching { Ed25519PublicKeyParameters(publicKeyEncoded, 0) }.getOrNull() ?: return false
        val verifier = Ed25519Signer().apply {
            init(false, publicKey)
            update(manifestBytes, 0, manifestBytes.size)
        }
        return verifier.verifySignature(signature)
    }

    internal fun parseManifest(text: String, expectedVersion: String): Manifest {
        require(text.endsWith("\n")) { "Rootfs manifest is not newline terminated." }
        val values = linkedMapOf<String, String>()
        text.lineSequence().filter(String::isNotEmpty).forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed rootfs manifest line." }
            val key = line.substring(0, separator)
            require(key !in values) { "Duplicate rootfs manifest field: $key" }
            values[key] = line.substring(separator + 1)
        }
        require(values["format"] == MANIFEST_FORMAT) { "Unsupported rootfs manifest format." }
        val version = values["version"] ?: error("Rootfs manifest has no version.")
        require(version == expectedVersion) { "APK expects rootfs $expectedVersion, release provides $version." }
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) { "Invalid rootfs version." }
        val archive = values["archive"] ?: error("Rootfs manifest has no archive name.")
        require(archive == archiveName(version)) { "Rootfs manifest archive name does not match the pinned version." }
        val size = values["size"]?.toLongOrNull()?.takeIf { it > 0 && it <= 4L * 1024 * 1024 * 1024 }
            ?: error("Rootfs manifest has an invalid archive size.")
        val hash = values["sha256"] ?: error("Rootfs manifest has no archive checksum.")
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Rootfs manifest checksum is malformed." }
        val baseHash = values["base_archive_sha256"] ?: error("Rootfs manifest has no source archive checksum.")
        require(baseHash.matches(Regex("[0-9a-f]{64}"))) { "Rootfs source checksum is malformed." }
        require(values.keys == linkedSetOf("format", "version", "archive", "size", "sha256", "base_archive_sha256")) {
            "Rootfs manifest contains unexpected or missing fields."
        }
        return Manifest(version, archive, size, hash, baseHash)
    }

    fun verifyArchive(file: File, manifest: Manifest) {
        require(file.isFile && file.length() == manifest.size) {
            "Rootfs asset size does not match its signed manifest (expected ${manifest.size}, found ${file.length()})."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == manifest.sha256) { "Rootfs asset SHA-256 does not match the signed manifest." }
    }
}
