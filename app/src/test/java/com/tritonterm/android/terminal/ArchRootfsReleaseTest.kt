package com.tritonterm.android.terminal

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchRootfsReleaseTest {
    @Test
    fun embeddedReleaseKeyIsValidEd25519SubjectPublicKeyInfo() {
        val pem = java.io.File("src/main/assets/tritonterm-rootfs-release-key.pem").readText()
        val rawPublicKey = ArchRootfsRelease.decodePublicKeyPem(pem)
        assertEquals(32, rawPublicKey.size)
    }

    @Test
    fun manifestPinsExactVersionNameSizeAndBothChecksums() {
        val version = "2026.11.12.1"
        val hash = "a".repeat(64)
        val baseHash = "b".repeat(64)
        val text = """
            format=${ArchRootfsRelease.MANIFEST_FORMAT}
            version=$version
            archive=${ArchRootfsRelease.archiveName(version)}
            size=123456
            sha256=$hash
            base_archive_sha256=$baseHash

        """.trimIndent() + "\n"

        val parsed = ArchRootfsRelease.parseManifest(text, version)
        assertEquals(version, parsed.version)
        assertEquals(ArchRootfsRelease.archiveName(version), parsed.archive)
        assertEquals(123456L, parsed.size)
        assertEquals(hash, parsed.sha256)
        assertEquals(baseHash, parsed.baseArchiveSha256)
    }

    @Test
    fun manifestRejectsWrongVersionDuplicateAndUnexpectedFields() {
        val version = "2026.11.12.1"
        val base = """
            format=${ArchRootfsRelease.MANIFEST_FORMAT}
            version=$version
            archive=${ArchRootfsRelease.archiveName(version)}
            size=123456
            sha256=${"a".repeat(64)}
            base_archive_sha256=${"b".repeat(64)}
        """.trimIndent() + "\n"
        assertFalse(runCatching { ArchRootfsRelease.parseManifest(base, "other") }.isSuccess)
        assertFalse(runCatching { ArchRootfsRelease.parseManifest(base + "version=$version\n", version) }.isSuccess)
        assertFalse(runCatching { ArchRootfsRelease.parseManifest(base + "unknown=value\n", version) }.isSuccess)
    }

    @Test
    fun signedAssetNameIsValidatedIndependentlyOfHashedCacheFilename() {
        val version = ArchRootfsRelease.pinnedVersion()
        val publicAssetName = ArchRootfsRelease.archiveName(version)
        val cacheName = ArchLinuxArmBootstrap.archiveName(
            "https://example.org/releases/$publicAssetName"
        )
        val manifest = ArchRootfsRelease.Manifest(
            version = version,
            archive = publicAssetName,
            size = 1,
            sha256 = "a".repeat(64),
            baseArchiveSha256 = "b".repeat(64)
        )

        assertTrue(ArchLinuxArmBootstrap.isExpectedSignedArchiveName(manifest))
        assertFalse(publicAssetName == cacheName)
        assertFalse(
            ArchLinuxArmBootstrap.isExpectedSignedArchiveName(
                manifest.copy(archive = cacheName)
            )
        )
    }

    @Test
    fun ed25519SignatureCoversTheExactManifestBytes() {
        val key = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
        val publicKey = key.generatePublicKey()
        val payload = "format=test\nversion=one\n".toByteArray()
        val signer = Ed25519Signer().apply {
            init(true, key)
            update(payload, 0, payload.size)
        }
        val signature = signer.generateSignature()
        assertTrue(ArchRootfsRelease.verifySignature(payload, signature, publicKey.encoded))
        assertFalse(ArchRootfsRelease.verifySignature(payload + 0, signature, publicKey.encoded))
    }
}
