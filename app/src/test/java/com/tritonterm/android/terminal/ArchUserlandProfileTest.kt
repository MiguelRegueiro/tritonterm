package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchUserlandProfileTest {
    @Test
    fun removesOnlyKernelFirmwareAndInitramfsPackages() {
        val installed = setOf(
            "bash", "glibc", "pacman", "systemd", "ca-certificates", "git", "linux-aarch64",
            "linux-firmware", "linux-firmware-nvidia", "linux-firmware-amdgpu", "mkinitcpio",
            "mkinitcpio-busybox", "linux-api-headers", "linux-firmware-tools"
        )

        assertEquals(
            listOf(
                "linux-aarch64", "linux-firmware", "linux-firmware-amdgpu", "linux-firmware-nvidia",
                "mkinitcpio", "mkinitcpio-busybox"
            ),
            ArchUserlandProfile.removalCandidates(installed)
        )
    }

    @Test
    fun allowsAnAlreadyMinimalPackageDatabase() {
        assertEquals(emptyList<String>(), ArchUserlandProfile.removalCandidates(setOf("bash", "glibc", "pacman")))
    }
}
