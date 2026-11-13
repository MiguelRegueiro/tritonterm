package com.tritonterm.android.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ArchRootfsReadinessTest {
    @Test
    fun extractedRootfsCanResumePreparationButIsNotShellReady() {
        val root = Files.createTempDirectory("tritonterm-rootfs-").toFile()
        try {
            val osRelease = java.io.File(root, "etc/os-release")
            checkNotNull(osRelease.parentFile).mkdirs()
            osRelease.writeText("ID=arch\n")
            val marker = java.io.File(checkNotNull(root.parentFile), ".archlinuxarm.pr-cli-installing")

            assertTrue(ArchRootfsReadiness.isExtracted(root, marker))
            assertFalse(ArchRootfsReadiness.isPrepared(root, marker))

            java.io.File(root, "root/.tritonterm-userland-profile-v1").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("validated\n")
            }
            assertTrue(ArchRootfsReadiness.isPrepared(root, marker))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun incompleteExtractionIsNeverReadyOrResumable() {
        val root = Files.createTempDirectory("tritonterm-rootfs-partial-").toFile()
        val marker = java.io.File(checkNotNull(root.parentFile), ".archlinuxarm.pr-cli-installing")
        try {
            marker.writeText("in progress")
            java.io.File(root, "etc/os-release").apply {
                checkNotNull(parentFile).mkdirs()
                writeText("ID=arch\n")
            }

            assertFalse(ArchRootfsReadiness.isExtracted(root, marker))
            assertFalse(ArchRootfsReadiness.isPrepared(root, marker))
            assertTrue(ArchRootfsReadiness.isInterruptedExtractionRetryable(root, marker))
        } finally {
            marker.delete()
            root.deleteRecursively()
        }
    }

    @Test
    fun incompleteRootfsWithoutPrCliInstallMarkerIsNotAutomaticallyRetryable() {
        val root = Files.createTempDirectory("tritonterm-rootfs-unknown-").toFile()
        val marker = java.io.File(checkNotNull(root.parentFile), ".archlinuxarm.pr-cli-installing")
        try {
            assertFalse(ArchRootfsReadiness.isInterruptedExtractionRetryable(root, marker))
        } finally {
            root.deleteRecursively()
        }
    }
}
