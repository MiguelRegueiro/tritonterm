package com.tritonterm.android.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.PGPUtil
import java.io.File

class OpenPgpDetachedVerifierTest {
    private val fingerprint = "B2C5436DFCFB56C92271D891C3C2CB07A9CBCCCF"

    @Test
    fun verifiesDetachedSignatureFromPinnedKey() {
        val signedFile = fixtureFile("tritonterm-fixture.txt")
        val signatureFile = fixtureFile("tritonterm-fixture.sig")

        assertTrue(verify(signedFile, signatureFile, fingerprint))
    }

    @Test
    fun rejectsWrongPinnedFingerprint() {
        val signedFile = fixtureFile("tritonterm-fixture.txt")
        val signatureFile = fixtureFile("tritonterm-fixture.sig")

        assertFalse(verify(signedFile, signatureFile, "0000000000000000000000000000000000000000"))
    }

    @Test
    fun rejectsModifiedArchive() {
        val signedFile = fixtureFile("tritonterm-fixture.txt")
        val signatureFile = fixtureFile("tritonterm-fixture.sig")
        val changedFile = File.createTempFile("tritonterm-pgp-changed", ".txt")
        changedFile.writeBytes(signedFile.readBytes() + "changed".toByteArray())

        assertFalse(verify(changedFile, signatureFile, fingerprint))
        changedFile.delete()
    }

    @Test
    fun bundledArchCertificateMatchesPinnedFingerprint() {
        File("src/main/assets/archlinuxarm-builder.asc").inputStream().use { raw ->
            val rings = PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(raw),
                JcaKeyFingerprintCalculator()
            )
            assertTrue(
                rings.keyRings.asSequence()
                    .flatMap { it.publicKeys.asSequence() }
                    .any { it.fingerprint.joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) } ==
                        "68B3537F39A313B3E574D06777193F152BDBE6A6" }
            )
        }
    }

    @Test
    fun rootfsCacheNameMatchesPrCliLowercaseSha256Convention() {
        assertTrue(
            ArchLinuxArmBootstrap.archiveName() ==
                "04786b808f80c4f6-ArchLinuxARM-aarch64-latest.tar.gz"
        )
    }

    @Test
    fun verifiesLiveRootfsWhenArchivePathIsProvided() {
        val archivePath = System.getenv("TRITONTERM_LIVE_ROOTFS")
        assumeTrue("Set TRITONTERM_LIVE_ROOTFS to run the optional live archive check", archivePath != null)
        val archive = File(requireNotNull(archivePath))
        val signature = File("$archivePath.sig")
        val keyRing = File(System.getenv("TRITONTERM_LIVE_KEYRING") ?: "src/main/assets/archlinuxarm-builder.asc")

        keyRing.inputStream().use { keys ->
            assertTrue(
                OpenPgpDetachedVerifier.verifyOrThrow(
                    keys,
                    archive,
                    signature,
                    "68B3537F39A313B3E574D06777193F152BDBE6A6"
                )
            )
        }
    }

    private fun verify(file: File, signature: File, expectedFingerprint: String): Boolean =
        File("src/test/resources/pgp/test-keyring.gpg").inputStream().use { keyRing ->
            OpenPgpDetachedVerifier.verify(keyRing, file, signature, expectedFingerprint)
        }

    private fun fixtureFile(name: String): File = File.createTempFile("tritonterm-pgp-", name).apply {
        File("src/test/resources/pgp/$name").inputStream().use { input ->
            outputStream().use(input::copyTo)
        }
    }
}
